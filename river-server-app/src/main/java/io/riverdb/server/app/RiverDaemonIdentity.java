package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.FileIdentity;
import io.riverdb.platform.riverd.RiverLock;
import io.riverdb.platform.riverd.RiverLockResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

/** Descriptor-relative first-create and restart identity state for the installed daemon. */
public final class RiverDaemonIdentity {
  public static final String INSTANCE_FILE = "instance.properties";
  public static final String LOCK_FILE = "instance.lock";
  public static final String DATABASE_NAME = "database";
  public static final String SECURITY_NAME = "security";
  private static final int MAX_RECORD_BYTES = RiverDaemonIdentityRecords.MAX_RECORD_BYTES;

  private RiverDaemonIdentity() {
  }

  /** Opens an existing instance and validates its sole restart authority. */
  public static StatusCode openExisting(
      Path datadir,
      RiverDaemonFileSystem filesystem,
      SecureRandom random,
      long pid,
      long processStartEpochMillis,
      IdentityResult result) {
    if (result == null || filesystem == null || !validDatadir(datadir) || random == null
        || pid <= 0 || processStartEpochMillis < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    RiverDirectoryResult directoryResult = new RiverDirectoryResult();
    StatusCode status = filesystem.openDirectory(datadir, directoryResult);
    if (!status.isOk()) return status;
    RiverDirectory directory = directoryResult.directory();
    RiverFileResult lockFileResult = new RiverFileResult();
    status = directory.openFile(LOCK_FILE, RiverOpenMode.EXISTING, lockFileResult);
    if (!status.isOk()) {
      closeDirectory(directory, status);
      return status;
    }
    RiverFile lockFile = lockFileResult.file();
    RiverLockResult lockResult = new RiverLockResult();
    status = filesystem.acquireExclusive(lockFile, lockResult);
    if (!status.isOk()) {
      lockFile.close();
      closeDirectory(directory, status);
      return status;
    }
    byte[] lockBytes = new byte[MAX_RECORD_BYTES];
    RiverDaemonIdentityRecords.LockRecord owner = null;
    status = readRecord(lockFile, lockBytes, LOCK_FILE);
    if (status.isOk()) owner = RiverDaemonIdentityRecords.parseLock(lockBytes);
    Arrays.fill(lockBytes, (byte) 0);
    if (status.isOk() && owner == null) status = StatusCode.CORRUPTION;
    if (status.isOk() && owner != null && !canonicalPath(datadir).equals(owner.datadir)) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk() && owner != null) status = proveOwnerAbsent(owner);
    if (!status.isOk()) {
      lockResult.lock().close();
      lockFile.close();
      closeDirectory(directory, status);
      return status;
    }
    RiverFileResult fileResult = new RiverFileResult();
    status = directory.openFile(INSTANCE_FILE, RiverOpenMode.EXISTING, fileResult);
    if (!status.isOk()) {
      lockResult.lock().close();
      lockFile.close();
      closeDirectory(directory, status);
      return status;
    }
    RiverFile file = fileResult.file();
    byte[] bytes = new byte[MAX_RECORD_BYTES];
    RiverDaemonIdentityRecords.InstanceRecord acceptedInstance = null;
    try {
      status = readRecord(file, bytes, INSTANCE_FILE);
      if (status.isOk()) {
        RiverDaemonIdentityRecords.InstanceRecord instance =
            RiverDaemonIdentityRecords.parseInstance(bytes);
        if (instance == null || owner == null
            || owner.high != instance.incarnation.high()
            || owner.low != instance.incarnation.low()) {
          status = StatusCode.CORRUPTION;
        } else {
          acceptedInstance = instance;
        }
      }
    } finally {
      Arrays.fill(bytes, (byte) 0);
      StatusCode closeFile = file.close();
      if (status.isOk() && !closeFile.isOk()) status = closeFile;
      if (!status.isOk()) {
        lockResult.lock().close();
        lockFile.close();
        closeDirectory(directory, status);
      } else {
        result.complete(directory, lockResult.lock(), acceptedInstance.incarnation,
            acceptedInstance.generation);
        result.setLockFile(lockFile);
        result.prepareRestart(canonicalPath(datadir), owner, nonce(random), pid,
            processStartEpochMillis);
      }
    }
    return status;
  }

  /**
   * Removes only validated bootstrap residue after component owners have checked an instance.
   * The caller must retain the lock returned by {@link #openExisting} while invoking this method;
   * identity does not validate database or credential contents.
   */
  static StatusCode cleanupCommittedResidue(IdentityResult result) {
    if (result == null || result.directory == null || result.lock == null
        || result.incarnation == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    RiverDirectory directory = result.directory;
    DirectoryListResult entries = new DirectoryListResult(16);
    StatusCode status = directory.list(entries);
    if (!status.isOk()) return status;
    if (!hasEntry(entries, INSTANCE_FILE)) return StatusCode.CONFLICT;
    if (!hasEntry(entries, "bootstrap.properties")) {
      for (int index = 0; index < entries.size(); index++) {
        if (isIdentityResidueEntry(entries.name(index))) return StatusCode.CORRUPTION;
      }
      return StatusCode.OK;
    }

    RiverFileResult bootstrapResult = new RiverFileResult();
    status = directory.openFile("bootstrap.properties", RiverOpenMode.EXISTING, bootstrapResult);
    if (!status.isOk()) return status;
    RiverFile bootstrapFile = bootstrapResult.file();
    FileIdentity bootstrapIdentity = bootstrapFile.identity();
    byte[] bootstrapBytes = new byte[MAX_RECORD_BYTES];
    RiverDaemonIdentityRecords.BootstrapRecord bootstrap = null;
    status = readRecord(bootstrapFile, bootstrapBytes, "bootstrap.properties");
    if (status.isOk()) bootstrap = RiverDaemonIdentityRecords.parseBootstrap(bootstrapBytes);
    Arrays.fill(bootstrapBytes, (byte) 0);
    StatusCode closeStatus = bootstrapFile.close();
    if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
      status = closeStatus;
    }
    if (!status.isOk()) return status;
    if (bootstrap == null || bootstrapIdentity == null
        || bootstrap.high != result.incarnation.high()
        || bootstrap.low != result.incarnation.low()
        || !bootstrap.stagingName.equals(".riverd-bootstrap-" + bootstrap.nonce)
        || !bootstrap.instanceStageName.equals(".instance-" + bootstrap.nonce + ".stage")) {
      return StatusCode.CORRUPTION;
    }

    String stagingName = bootstrap.stagingName;
    String instanceStageName = bootstrap.instanceStageName;
    String[] allowed = {LOCK_FILE, INSTANCE_FILE, DATABASE_NAME, SECURITY_NAME,
      "bootstrap.properties", stagingName, instanceStageName};
    for (int index = 0; index < entries.size(); index++) {
      boolean known = false;
      for (String name : allowed) known |= name.equals(entries.name(index));
      if (!known && !isLifecycleEntry(entries.name(index))) return StatusCode.CORRUPTION;
    }

    FileIdentity stagingIdentity = null;
    if (hasEntry(entries, stagingName)) {
      RiverDirectoryResult stagingResult = new RiverDirectoryResult();
      status = directory.openDirectory(stagingName, stagingResult);
      if (!status.isOk()) return status;
      RiverDirectory staging = stagingResult.directory();
      stagingIdentity = staging.identity();
      DirectoryListResult stagingEntries = new DirectoryListResult(8);
      status = staging.list(stagingEntries);
      closeStatus = staging.close();
      if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
        status = closeStatus;
      }
      if (!status.isOk()) return status;
      if (stagingIdentity == null || stagingEntries.size() != 0) return StatusCode.CORRUPTION;
    }

    FileIdentity instanceStageIdentity = null;
    if (hasEntry(entries, instanceStageName)) {
      RiverFileResult stageResult = new RiverFileResult();
      status = directory.openFile(instanceStageName, RiverOpenMode.EXISTING, stageResult);
      if (!status.isOk()) return status;
      RiverFile stage = stageResult.file();
      instanceStageIdentity = stage.identity();
      byte[] instanceBytes = new byte[MAX_RECORD_BYTES];
      RiverDaemonIdentityRecords.InstanceRecord instanceStage = null;
      status = readRecord(stage, instanceBytes, instanceStageName);
      if (status.isOk()) instanceStage = RiverDaemonIdentityRecords.parseInstance(instanceBytes);
      Arrays.fill(instanceBytes, (byte) 0);
      closeStatus = stage.close();
      if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
        status = closeStatus;
      }
      if (!status.isOk()) return status;
      if (instanceStageIdentity == null || instanceStage == null
          || instanceStage.incarnation.high() != result.incarnation.high()
          || instanceStage.incarnation.low() != result.incarnation.low()) {
        return StatusCode.CORRUPTION;
      }
    }

    if (instanceStageIdentity != null) {
      status = directory.removeOwned(
          instanceStageName, instanceStageIdentity, new DirectoryOperationResult());
    }
    if (status.isOk() && stagingIdentity != null) {
      status = directory.removeOwned(stagingName, stagingIdentity, new DirectoryOperationResult());
    }
    if (status.isOk() && (instanceStageIdentity != null || stagingIdentity != null)) {
      status = forceDirectory(directory);
    }
    if (status.isOk()) {
      status = directory.removeOwned("bootstrap.properties", bootstrapIdentity,
          new DirectoryOperationResult());
    }
    if (status.isOk()) status = forceDirectory(directory);
    return status;
  }

  /**
   * Begins a first-create transaction, leaving database and credential initialization to owners.
   * The proposed incarnation applies only to a new bootstrap; an existing bootstrap owns its
   * recorded incarnation and is resumed unchanged.
   */
  public static StatusCode beginCreate(
      Path datadir,
      RiverDaemonFileSystem filesystem,
      DatabaseIncarnation incarnation,
      SecureRandom random,
      long pid,
      long processStartEpochMillis,
      IdentityResult result) {
    if (result == null || filesystem == null || !validDatadir(datadir)
        || incarnation == null || !incarnation.isValid() || random == null || pid <= 0
        || processStartEpochMillis < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    RiverDirectoryResult directoryResult = new RiverDirectoryResult();
    StatusCode status = openOrCreateDirectory(datadir, filesystem, directoryResult);
    if (!status.isOk()) return status;
    RiverDirectory directory = directoryResult.directory();
    DirectoryListResult entries = new DirectoryListResult(16);
    status = directory.list(entries);
    if (!status.isOk()) {
      closeDirectory(directory, status.isOk() ? StatusCode.CONFLICT : status);
      return status.isOk() ? StatusCode.CONFLICT : status;
    }
    boolean hasBootstrap = hasEntry(entries, "bootstrap.properties");
    boolean hasLock = hasEntry(entries, LOCK_FILE);
    String prebootstrapStage = prebootstrapStageName(entries, hasBootstrap, hasLock);
    // Before bootstrap, mutation is limited to an empty tree, sole lock, or bound stage residue.
    if (!hasBootstrap && entries.size() != 0
        && !(entries.size() == 1 && hasLock) && prebootstrapStage == null) {
      closeDirectory(directory, StatusCode.CORRUPTION);
      return StatusCode.CORRUPTION;
    }
    if (prebootstrapStage != null) {
      return recoverPrebootstrapStage(datadir, directory, filesystem, incarnation, random, pid,
          processStartEpochMillis, prebootstrapStage, result);
    }
    if (hasEntry(entries, INSTANCE_FILE) || hasEntry(entries, DATABASE_NAME)
        || hasEntry(entries, SECURITY_NAME)) {
      if (hasBootstrap) {
        return recoverCreate(datadir, directory, filesystem, pid,
            processStartEpochMillis, entries, result);
      }
      closeDirectory(directory, StatusCode.CONFLICT);
      return StatusCode.CONFLICT;
    }
    if (hasBootstrap) {
      return recoverCreate(datadir, directory, filesystem, pid,
          processStartEpochMillis, entries, result);
    }

    RiverFileResult lockFileResult = new RiverFileResult();
    status = directory.openFile(LOCK_FILE, hasLock ? RiverOpenMode.EXISTING : RiverOpenMode.CREATE_NEW,
        lockFileResult);
    // A sole prebootstrap lock is reopened and verified before its owner record is replaced.
    if (!status.isOk()) {
      closeDirectory(directory, status);
      return status;
    }
    RiverFile lockFile = lockFileResult.file();
    RiverLockResult lockResult = new RiverLockResult();
    status = filesystem.acquireExclusive(lockFile, lockResult);
    if (!status.isOk()) {
      lockFile.close();
      closeDirectory(directory, status);
      return status;
    }
    RiverDaemonIdentityRecords.LockRecord priorOwner = null;
    if (hasLock) {
      DirectoryListResult heldEntries = new DirectoryListResult(16);
      status = directory.list(heldEntries);
      if (status.isOk() && (heldEntries.size() != 1 || !hasEntry(heldEntries, LOCK_FILE))) {
        status = StatusCode.CONFLICT;
      }
      byte[] priorBytes = new byte[MAX_RECORD_BYTES];
      if (status.isOk()) {
        status = readRecord(lockFile, priorBytes, LOCK_FILE);
        if (status == StatusCode.CORRUPTION) {
          status = StatusCode.OK;
        } else if (status.isOk()) {
          priorOwner = RiverDaemonIdentityRecords.parseLock(priorBytes);
          if (priorOwner != null) {
            if (!canonicalPath(datadir).equals(priorOwner.datadir)) {
              status = StatusCode.CORRUPTION;
            } else {
              status = proveOwnerAbsent(priorOwner);
            }
          }
        }
      }
      Arrays.fill(priorBytes, (byte) 0);
    }
    if (!status.isOk()) {
      lockResult.lock().close();
      lockFile.close();
      closeDirectory(directory, status);
      return status;
    }
    return createBootstrap(datadir, directory, lockFile, lockResult.lock(), incarnation, random,
        pid, processStartEpochMillis, result, priorOwner, hasLock);
  }

  private static StatusCode createBootstrap(
      Path datadir,
      RiverDirectory directory,
      RiverFile lockFile,
      RiverLock lock,
      DatabaseIncarnation incarnation,
      SecureRandom random,
      long pid,
      long processStartEpochMillis,
      IdentityResult result,
      RiverDaemonIdentityRecords.LockRecord priorOwner,
      boolean replaceExistingLock) {
    StatusCode status = StatusCode.OK;
    if (replaceExistingLock) status = lockFile.truncate(0);
    String nonce = nonce(random);
    if (status.isOk()) status = writeLock(lockFile, datadir, incarnation, pid,
        processStartEpochMillis, nonce);
    if (status.isOk() && replaceExistingLock) status = forceDirectory(directory);
    if (status.isOk()) {
      status = writeBootstrap(directory, incarnation, pid, processStartEpochMillis, nonce);
    }
    RiverDirectory staging = null;
    RiverDirectory database = null;
    RiverDirectory security = null;
    if (status.isOk()) {
      RiverDirectoryResult stagingResult = new RiverDirectoryResult();
      status = directory.createDirectory(".riverd-bootstrap-" + nonce, stagingResult);
      staging = stagingResult.directory();
      if (status.isOk()) {
        RiverDirectoryResult child = new RiverDirectoryResult();
        status = staging.createDirectory(DATABASE_NAME, child);
        database = child.directory();
      }
      if (status.isOk()) {
        RiverDirectoryResult child = new RiverDirectoryResult();
        status = staging.createDirectory(SECURITY_NAME, child);
        security = child.directory();
      }
    }
    if (!status.isOk()) {
      closeQuiet(security);
      closeQuiet(database);
      closeQuiet(staging);
      lock.close();
      lockFile.close();
      closeDirectory(directory, status);
      return status;
    }
    result.complete(directory, lock, incarnation, 1L);
    result.setBootstrap(nonce, staging, database, security, lockFile,
        false, false, canonicalPath(datadir), pid, processStartEpochMillis, false,
        null, null);
    result.setPriorOwner(priorOwner);
    return StatusCode.OK;
  }

  private static StatusCode recoverPrebootstrapStage(
      Path datadir,
      RiverDirectory directory,
      RiverDaemonFileSystem filesystem,
      DatabaseIncarnation incarnation,
      SecureRandom random,
      long pid,
      long processStartEpochMillis,
      String stageName,
      IdentityResult result) {
    RiverFileResult lockResult = new RiverFileResult();
    StatusCode status = directory.openFile(LOCK_FILE, RiverOpenMode.EXISTING, lockResult);
    if (!status.isOk()) {
      closeDirectory(directory, status);
      return status;
    }
    RiverFile lockFile = lockResult.file();
    RiverLockResult heldResult = new RiverLockResult();
    status = filesystem.acquireExclusive(lockFile, heldResult);
    if (!status.isOk()) {
      lockFile.close();
      closeDirectory(directory, status);
      return status;
    }
    RiverLock held = heldResult.lock();
    DirectoryListResult heldEntries = new DirectoryListResult(16);
    status = directory.list(heldEntries);
    if (status.isOk() && (heldEntries.size() != 2 || !hasEntry(heldEntries, LOCK_FILE)
        || !hasEntry(heldEntries, stageName))) {
      status = StatusCode.CONFLICT;
    }
    RiverDaemonIdentityRecords.LockRecord priorOwner = null;
    byte[] lockBytes = new byte[MAX_RECORD_BYTES];
    if (status.isOk()) {
      status = readRecord(lockFile, lockBytes, LOCK_FILE);
      if (status == StatusCode.CORRUPTION) {
        status = StatusCode.OK;
      } else if (status.isOk()) {
        priorOwner = RiverDaemonIdentityRecords.parseLock(lockBytes);
        if (priorOwner != null) {
          if (!canonicalPath(datadir).equals(priorOwner.datadir)) {
            status = StatusCode.CORRUPTION;
          } else {
            status = proveOwnerAbsent(priorOwner);
          }
        }
      }
    }
    Arrays.fill(lockBytes, (byte) 0);

    RiverFileResult stageResult = new RiverFileResult();
    FileIdentity stageIdentity = null;
    RiverDaemonIdentityRecords.BootstrapRecord bootstrap = null;
    if (status.isOk()) {
      status = directory.openFile(stageName, RiverOpenMode.EXISTING, stageResult);
      if (status.isOk()) {
        RiverFile stage = stageResult.file();
        stageIdentity = stage.identity();
        byte[] stageBytes = new byte[MAX_RECORD_BYTES];
        status = readRecord(stage, stageBytes, stageName);
        if (status.isOk()) bootstrap = RiverDaemonIdentityRecords.parseBootstrap(stageBytes);
        Arrays.fill(stageBytes, (byte) 0);
        StatusCode closeStatus = stage.close();
        if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
          status = closeStatus;
        }
      }
    }
    if (status.isOk() && (stageIdentity == null || bootstrap == null
        || !stageName.equals(".bootstrap-" + bootstrap.nonce + ".stage")
        || !bootstrap.stagingName.equals(".riverd-bootstrap-" + bootstrap.nonce)
        || !bootstrap.instanceStageName.equals(".instance-" + bootstrap.nonce + ".stage"))) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk() && priorOwner != null
        && (priorOwner.high != bootstrap.high || priorOwner.low != bootstrap.low
            || priorOwner.pid != bootstrap.pid || priorOwner.start != bootstrap.start
            || !priorOwner.nonce.equals(bootstrap.nonce))) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk()) status = proveOwnerAbsent(bootstrapOwner(datadir, bootstrap));
    if (status.isOk()) {
      status = directory.removeOwned(stageName, stageIdentity, new DirectoryOperationResult());
    }
    if (status.isOk()) status = forceDirectory(directory);
    if (!status.isOk()) {
      held.close();
      lockFile.close();
      closeDirectory(directory, status);
      return status;
    }
    return createBootstrap(datadir, directory, lockFile, held, incarnation, random, pid,
        processStartEpochMillis, result, priorOwner, true);
  }

  /**
   * Reopens a valid bootstrap transaction and resumes only its fixed, recorded namespace.
   *
   * <p>The caller has already opened {@code datadir}; every child operation below is relative to
   * that capability. The recorded bootstrap identity, nonce, path, and child names remain
   * authoritative; a new proposal is never inferred during recovery.
   */
  private static StatusCode recoverCreate(
      Path datadir,
      RiverDirectory directory,
      RiverDaemonFileSystem filesystem,
      long pid,
      long processStartEpochMillis,
      DirectoryListResult entries,
      IdentityResult result) {
    if (!validDatadir(datadir) || directory == null || filesystem == null || entries == null) {
      closeDirectory(directory, StatusCode.INVALID_EXTERNAL_INPUT);
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (hasEntry(entries, INSTANCE_FILE)) {
      closeDirectory(directory, StatusCode.CONFLICT);
      return StatusCode.CONFLICT;
    }
    RiverFileResult lockResult = new RiverFileResult();
    StatusCode status = directory.openFile(LOCK_FILE, RiverOpenMode.EXISTING, lockResult);
    if (!status.isOk()) {
      closeDirectory(directory, status);
      return status;
    }
    RiverFile lockFile = lockResult.file();
    RiverLockResult heldResult = new RiverLockResult();
    status = filesystem.acquireExclusive(lockFile, heldResult);
    if (!status.isOk()) {
      lockFile.close();
      closeDirectory(directory, status);
      return status;
    }
    RiverLock held = heldResult.lock();
    byte[] lockBytes = new byte[MAX_RECORD_BYTES];
    RiverDaemonIdentityRecords.LockRecord lock = null;
    StatusCode lockReadStatus = readRecord(lockFile, lockBytes, LOCK_FILE);
    if (lockReadStatus.isOk()) lock = RiverDaemonIdentityRecords.parseLock(lockBytes);
    Arrays.fill(lockBytes, (byte) 0);

    RiverFileResult bootstrapResult = new RiverFileResult();
    status = directory.openFile("bootstrap.properties", RiverOpenMode.EXISTING, bootstrapResult);
    if (!status.isOk()) {
      held.close();
      lockFile.close();
      closeDirectory(directory, status);
      return status;
    }
    RiverFile bootstrapFile = bootstrapResult.file();
    byte[] bootstrapBytes = new byte[MAX_RECORD_BYTES];
    RiverDaemonIdentityRecords.BootstrapRecord bootstrap = null;
    status = readRecord(bootstrapFile, bootstrapBytes, "bootstrap.properties");
    if (status.isOk()) bootstrap = RiverDaemonIdentityRecords.parseBootstrap(bootstrapBytes);
    Arrays.fill(bootstrapBytes, (byte) 0);
    StatusCode bootstrapClose = bootstrapFile.close();
    if (status.isOk() && !bootstrapClose.isOk() && bootstrapClose != StatusCode.CLOSED) {
      status = bootstrapClose;
    }
    if (status.isOk() && bootstrap == null) status = StatusCode.CORRUPTION;
    if (status.isOk() && !bootstrap.incarnation.isValid()) status = StatusCode.CORRUPTION;
    if (status.isOk() && !lockReadStatus.isOk() && lockReadStatus != StatusCode.CORRUPTION) {
      status = lockReadStatus;
    }
    if (status.isOk() && lock != null) status = proveOwnerAbsent(lock);
    if (status.isOk()) status = proveOwnerAbsent(bootstrapOwner(datadir, bootstrap));
    if (!status.isOk()) {
      held.close();
      lockFile.close();
      closeDirectory(directory, status);
      return status;
    }

    String stagingName = bootstrap.stagingName;
    String instanceStageName = bootstrap.instanceStageName;
    if (!stagingName.equals(".riverd-bootstrap-" + bootstrap.nonce)
        || !instanceStageName.equals(".instance-" + bootstrap.nonce + ".stage")) {
      held.close();
      lockFile.close();
      closeDirectory(directory, StatusCode.CORRUPTION);
      return StatusCode.CORRUPTION;
    }
    String[] allowed = {LOCK_FILE, "bootstrap.properties", INSTANCE_FILE,
      DATABASE_NAME, SECURITY_NAME, stagingName, instanceStageName};
    for (int index = 0; index < entries.size(); index++) {
      boolean known = false;
      for (String name : allowed) known |= name.equals(entries.name(index));
      if (!known) {
        held.close();
        lockFile.close();
        closeDirectory(directory, StatusCode.CORRUPTION);
        return StatusCode.CORRUPTION;
      }
    }

    boolean databasePublished = hasEntry(entries, DATABASE_NAME);
    boolean securityPublished = hasEntry(entries, SECURITY_NAME);
    if (securityPublished && !databasePublished) {
      held.close();
      lockFile.close();
      closeDirectory(directory, StatusCode.CORRUPTION);
      return StatusCode.CORRUPTION;
    }

    RiverDirectory staging = null;
    RiverDirectory database = null;
    RiverDirectory security = null;
    RiverDirectoryResult opened = new RiverDirectoryResult();
    StageRepairResult stageRepair = new StageRepairResult();
    if (hasEntry(entries, instanceStageName)) {
      status = inspectInstanceStage(directory, instanceStageName, bootstrap.incarnation,
          stageRepair);
      if (status.isOk() && stageRepair.identity != null
          && !(databasePublished && securityPublished)) {
        status = StatusCode.CORRUPTION;
      }
    }
    if (hasEntry(entries, stagingName)) {
      if (status.isOk()) {
        status = directory.openDirectory(stagingName, opened);
        staging = opened.directory();
      }
      if (status.isOk()) status = validateStagingNames(staging, databasePublished,
          securityPublished);
    }
    if (status.isOk()) status = validateExistingComponents(
        directory, staging, databasePublished, securityPublished);
    if (status.isOk() && staging == null) {
      status = directory.createDirectory(stagingName, opened);
      staging = opened.directory();
      if (status.isOk()) status = forceDirectory(directory);
    }
    if (status.isOk()) status = openOrCreateRecoveryChild(
        directory, staging, DATABASE_NAME, databasePublished, opened);
    if (status.isOk()) database = opened.directory();
    if (status.isOk()) status = openOrCreateRecoveryChild(
        directory, staging, SECURITY_NAME, securityPublished, opened);
    if (status.isOk()) security = opened.directory();
    if (!status.isOk()) {
      closeQuiet(security);
      closeQuiet(database);
      closeQuiet(staging);
      held.close();
      lockFile.close();
      closeDirectory(directory, status);
      return status;
    }
    result.complete(directory, held, bootstrap.incarnation, 1L);
    result.setBootstrap(bootstrap.nonce, staging, database, security, lockFile,
        databasePublished, securityPublished, canonicalPath(datadir), pid,
        processStartEpochMillis, true, stageRepair.name, stageRepair.identity);
    result.setPriorOwner(lock);
    return StatusCode.OK;
  }

  private static StatusCode openOrCreateRecoveryChild(
      RiverDirectory directory,
      RiverDirectory staging,
      String name,
      boolean published,
      RiverDirectoryResult result) {
    result.reset();
    if (published) {
      StatusCode status = directory.openDirectory(name, result);
      return status;
    }
    StatusCode status = staging.openDirectory(name, result);
    if (status == StatusCode.CONFLICT) status = staging.createDirectory(name, result);
    if (status.isOk() && result.directory() != null && !published) status = forceDirectory(staging);
    return status;
  }

  private static StatusCode validateExistingComponents(
      RiverDirectory directory,
      RiverDirectory staging,
      boolean databasePublished,
      boolean securityPublished) {
    String[] names = {DATABASE_NAME, SECURITY_NAME};
    boolean[] published = {databasePublished, securityPublished};
    for (int index = 0; index < names.length; index++) {
      RiverDirectoryResult result = new RiverDirectoryResult();
      StatusCode status;
      if (published[index]) {
        status = directory.openDirectory(names[index], result);
      } else if (staging != null) {
        status = staging.openDirectory(names[index], result);
        if (status == StatusCode.CONFLICT) continue;
      } else {
        continue;
      }
      if (!status.isOk()) return status;
      status = result.directory().close();
      if (!status.isOk() && status != StatusCode.CLOSED) return status;
    }
    return StatusCode.OK;
  }

  private static StatusCode forceDirectory(RiverDirectory directory) {
    DirectoryOperationResult forced = new DirectoryOperationResult();
    return directory.force(forced);
  }

  /**
   * Transfers a recovered bootstrap lock to the current creator after component validation.
   * Fresh creates are already owned and return {@link StatusCode#OK}.
   */
  static StatusCode handoffOwner(IdentityResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!result.needsOwnerHandoff) return StatusCode.OK;
    if (result.lockFile == null || result.datadir == null || result.incarnation == null
        || result.ownerNonce == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = validateCurrentOwner(result.ownerPid, result.ownerStart);
    if (!status.isOk()) return status;
    status = result.lockFile.truncate(0);
    if (status.isOk()) {
      status = writeLockCanonical(result.lockFile, result.datadir, result.incarnation,
          result.ownerPid, result.ownerStart, result.ownerNonce);
    }
    if (status.isOk()) result.needsOwnerHandoff = false;
    return status;
  }

  private static StatusCode validateCurrentOwner(long pid, long start) {
    ProcessHandle current = ProcessHandle.current();
    if (current.pid() != pid) return StatusCode.NOT_OWNER;
    ProcessHandle.Info info = current.info();
    if (info.startInstant().isEmpty()) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    return info.startInstant().get().toEpochMilli() == start
        ? StatusCode.OK : StatusCode.NOT_OWNER;
  }

  private static StatusCode validateStagingNames(
      RiverDirectory staging, boolean databasePublished, boolean securityPublished) {
    DirectoryListResult entries = new DirectoryListResult(8);
    StatusCode status = staging.list(entries);
    if (!status.isOk()) return status;
    for (int index = 0; index < entries.size(); index++) {
      String name = entries.name(index);
      boolean allowed = (!databasePublished && DATABASE_NAME.equals(name))
          || (!securityPublished && SECURITY_NAME.equals(name));
      if (!allowed) return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  private static StatusCode inspectInstanceStage(
      RiverDirectory directory, String name, DatabaseIncarnation incarnation,
      StageRepairResult repair) {
    repair.reset();
    RiverFileResult result = new RiverFileResult();
    StatusCode status = directory.openFile(name, RiverOpenMode.EXISTING, result);
    if (!status.isOk()) return status;
    io.riverdb.platform.riverd.FileIdentity identity = result.file().identity();
    byte[] bytes = new byte[MAX_RECORD_BYTES];
    RiverDaemonIdentityRecords.InstanceRecord record = null;
    status = readRecord(result.file(), bytes, name);
    if (status.isOk()) record = RiverDaemonIdentityRecords.parseInstance(bytes);
    Arrays.fill(bytes, (byte) 0);
    StatusCode close = result.file().close();
    if (status.isOk() && !close.isOk() && close != StatusCode.CLOSED) status = close;
    if (!status.isOk()) {
      if (status == StatusCode.CORRUPTION && identity != null) {
        repair.name = name;
        repair.identity = identity;
        return StatusCode.OK;
      }
      return status;
    }
    if (record == null) {
      if (identity == null) return StatusCode.CORRUPTION;
      repair.name = name;
      repair.identity = identity;
      return StatusCode.OK;
    }
    if (record.incarnation.high() != incarnation.high()
        || record.incarnation.low() != incarnation.low()) {
      // A canonical record naming another incarnation is never repairable evidence.
      return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  /**
   * Publishes owner-initialized staged directories and commits instance authority.
   *
   * <p>Callers must validate each component's durable contents through its owning consumer before
   * calling this method. Identity validates only capability, type, ownership, and namespace
   * binding; it does not interpret database or credential formats.
   */
  public static StatusCode completeCreate(IdentityResult result) {
    if (result == null || result.directory == null || result.lock == null
        || result.staging == null || result.database == null || result.security == null
        || result.nonce == null || result.lockFile == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    RiverDirectory directory = result.directory;
    StatusCode status = handoffOwner(result);
    RiverFileResult stageResult = new RiverFileResult();
    if (status.isOk() && result.stageRepairIdentity != null) {
      status = removeOwned(directory, result.stageRepairName, result.stageRepairIdentity);
      if (status.isOk()) status = forceDirectory(directory);
      if (status.isOk()) {
        result.stageRepairName = null;
        result.stageRepairIdentity = null;
      }
    }
    if (status.isOk() && !result.databasePublished) {
      status = publishDirectory(directory, result.staging, result.database, DATABASE_NAME);
      if (status.isOk()) result.databasePublished = true;
    }
    if (status.isOk() && !result.securityPublished) {
      status = publishDirectory(directory, result.staging, result.security, SECURITY_NAME);
      if (status.isOk()) result.securityPublished = true;
    }
    if (status.isOk()) {
      String stageName = ".instance-" + result.nonce + ".stage";
      DirectoryListResult stageEntries = new DirectoryListResult(16);
      status = directory.list(stageEntries);
      boolean stageExisted = status.isOk() && hasEntry(stageEntries, stageName);
      if (status.isOk()) {
        status = directory.openFile(stageName, RiverOpenMode.CREATE_NEW, stageResult);
      }
      if (status == StatusCode.CONFLICT) {
        status = directory.openFile(stageName, RiverOpenMode.EXISTING, stageResult);
        if (status.isOk()) {
          byte[] existing = new byte[MAX_RECORD_BYTES];
          RiverDaemonIdentityRecords.InstanceRecord parsed = null;
          status = readRecord(stageResult.file(), existing, stageName);
          if (status.isOk()) parsed = RiverDaemonIdentityRecords.parseInstance(existing);
          Arrays.fill(existing, (byte) 0);
          if (status.isOk() && (parsed == null
              || parsed.incarnation.high() != result.incarnation.high()
              || parsed.incarnation.low() != result.incarnation.low())) {
            status = StatusCode.CORRUPTION;
          }
        }
      }
      if (status.isOk()) {
        RiverFile stage = stageResult.file();
        if (!stageExisted) {
          String body = RiverDaemonIdentityRecords.record(List.of(
              "format=" + RiverDaemonIdentityRecords.INSTANCE_FORMAT,
              "database-incarnation-high=" + result.incarnation.high(),
              "database-incarnation-low=" + result.incarnation.low(),
              "initial-wal-generation=1"));
          status = write(stage, body.getBytes(StandardCharsets.UTF_8));
        }
        if (status.isOk()) {
          DirectoryOperationResult publication = new DirectoryOperationResult();
          status = directory.publishExclusive(stage, stageName, INSTANCE_FILE, publication);
        }
        StatusCode closeStatus = stage.close();
        if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
          status = closeStatus;
        }
      }
    }
    // Namespace commit must be durable before bootstrap evidence is removed.
    if (status.isOk()) status = forceDirectory(directory);
    if (!status.isOk() && stageResult.file() != null) {
      StatusCode closeStatus = stageResult.file().close();
      if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
        status = closeStatus;
      }
    }
    if (status.isOk()) {
      status = removeOwned(directory, ".riverd-bootstrap-" + result.nonce,
          result.staging.identity());
    }
    if (status.isOk()) {
      status = forceDirectory(directory);
    }
    if (status.isOk()) {
      status = removeOwned(directory, "bootstrap.properties", null);
    }
    if (status.isOk()) {
      status = forceDirectory(directory);
    }
    return status;
  }

  private static StatusCode publishDirectory(
      RiverDirectory directory,
      RiverDirectory staging,
      RiverDirectory child,
      String name) {
    DirectoryOperationResult publication = new DirectoryOperationResult();
    StatusCode status = directory.publishDirectoryExclusive(
        staging, child, name, name, publication);
    if (!status.isOk()) return status;
    DirectoryOperationResult forced = new DirectoryOperationResult();
    return directory.force(forced);
  }

  private static StatusCode removeOwned(
      RiverDirectory directory, String name, io.riverdb.platform.riverd.FileIdentity knownIdentity) {
    io.riverdb.platform.riverd.FileIdentity identity = knownIdentity;
    if (identity == null) {
      RiverFileResult fileResult = new RiverFileResult();
      StatusCode status = directory.openFile(name, RiverOpenMode.EXISTING, fileResult);
      if (!status.isOk()) return status;
      RiverFile file = fileResult.file();
      identity = file.identity();
      status = file.close();
      if (!status.isOk() && status != StatusCode.CLOSED) return status;
    }
    DirectoryOperationResult removed = new DirectoryOperationResult();
    return directory.removeOwned(name, identity, removed);
  }

  private static StatusCode openOrCreateDirectory(
      Path datadir, RiverDaemonFileSystem filesystem, RiverDirectoryResult result) {
    Path parentPath = datadir.getParent();
    if (parentPath == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    RiverDirectoryResult parentResult = new RiverDirectoryResult();
    StatusCode status = filesystem.openAncestor(parentPath, parentResult);
    if (!status.isOk()) return status;
    RiverDirectory parent = parentResult.directory();
    try {
      status = parent.createDirectory(datadir.getFileName().toString(), result);
      if (status == StatusCode.CONFLICT) {
        status = filesystem.openDirectory(datadir, result);
      }
      return status;
    } finally {
      closeQuiet(parent);
    }
  }

  private static StatusCode writeLock(
      RiverFile file,
      Path datadir,
      DatabaseIncarnation incarnation,
      long pid,
      long start,
      String nonce) {
    return writeLockCanonical(file, canonicalPath(datadir), incarnation, pid, start, nonce);
  }

  private static StatusCode writeLockCanonical(
      RiverFile file,
      String datadir,
      DatabaseIncarnation incarnation,
      long pid,
      long start,
      String nonce) {
    String body = RiverDaemonIdentityRecords.record(List.of(
      "format=" + RiverDaemonIdentityRecords.LOCK_FORMAT,
      "datadir=" + datadir,
        "database-incarnation-high=" + incarnation.high(),
        "database-incarnation-low=" + incarnation.low(),
        "pid=" + pid,
        "process-start-epoch-millis=" + start,
        "owner-nonce=" + nonce));
    return write(file, body.getBytes(StandardCharsets.UTF_8));
  }

  private static StatusCode writeBootstrap(
      RiverDirectory directory,
      DatabaseIncarnation incarnation,
      long pid,
      long start,
      String nonce) {
    String stageName = ".bootstrap-" + nonce + ".stage";
    String body = RiverDaemonIdentityRecords.record(List.of(
        "format=" + RiverDaemonIdentityRecords.BOOTSTRAP_FORMAT,
        "database-incarnation-high=" + incarnation.high(),
        "database-incarnation-low=" + incarnation.low(),
        "pid=" + pid,
        "process-start-epoch-millis=" + start,
        "attempt-nonce=" + nonce,
        "database-name=" + DATABASE_NAME,
        "security-name=" + SECURITY_NAME,
        "staging-name=.riverd-bootstrap-" + nonce,
        "instance-stage-name=.instance-" + nonce + ".stage"));
    RiverFileResult stageResult = new RiverFileResult();
    StatusCode status = directory.openFile(stageName, RiverOpenMode.CREATE_NEW, stageResult);
    if (!status.isOk()) return status;
    RiverFile stage = stageResult.file();
    status = write(stage, body.getBytes(StandardCharsets.UTF_8));
    if (status.isOk()) {
      DirectoryOperationResult publication = new DirectoryOperationResult();
      status = directory.publishExclusive(stage, stageName, "bootstrap.properties", publication);
      if (status.isOk()) {
        DirectoryOperationResult forced = new DirectoryOperationResult();
        status = directory.force(forced);
      }
    } else {
      stage.close();
    }
    StatusCode closeStatus = stage.close();
    if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
      status = closeStatus;
    }
    return status;
  }

  private static StatusCode write(RiverFile file, byte[] bytes) {
    try {
      ByteBuffer source = ByteBuffer.wrap(bytes);
      IoResult io = new IoResult();
      long position = 0;
      while (source.hasRemaining()) {
        StatusCode status = file.write(position, source, io);
        if (!status.isOk()) return status;
        if (io.bytesTransferred() <= 0) return StatusCode.IO_FAILURE;
        position += io.bytesTransferred();
      }
      return file.force(ForceMode.CONTENT_AND_METADATA);
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  private static StatusCode readRecord(RiverFile file, byte[] target, String name) {
    FileSizeResult size = new FileSizeResult();
    StatusCode sizeStatus = file.size(size);
    if (!sizeStatus.isOk()) return sizeStatus;
    if (size.sizeBytes() <= 0 || size.sizeBytes() > target.length) return StatusCode.CORRUPTION;
    IoResult io = new IoResult();
    ByteBuffer bytes = ByteBuffer.wrap(target);
    bytes.limit((int) size.sizeBytes());
    int position = 0;
    while (position < size.sizeBytes()) {
      bytes.position(position);
      StatusCode status = file.read(position, bytes, io);
      if (!status.isOk()) return status;
      int count = io.bytesTransferred();
      if (count == 0) break;
      position += count;
    }
    if (position == 0 || position != size.sizeBytes()) return StatusCode.CORRUPTION;
    for (int index = 0; index < position; index++) {
      if (target[index] == 0) return StatusCode.CORRUPTION;
    }
    Arrays.fill(target, position, target.length, (byte) 0);
    return StatusCode.OK;
  }

  private static StatusCode proveOwnerAbsent(RiverDaemonIdentityRecords.LockRecord owner) {
    var process = ProcessHandle.of(owner.pid);
    if (process.isEmpty() || !process.get().isAlive()) return StatusCode.OK;
    var info = process.get().info();
    if (info.startInstant().isEmpty()) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    long start = info.startInstant().get().toEpochMilli();
    return owner.start == start
        ? StatusCode.CONFLICT : StatusCode.OK;
  }

  private static RiverDaemonIdentityRecords.LockRecord bootstrapOwner(
      Path datadir, RiverDaemonIdentityRecords.BootstrapRecord bootstrap) {
    return new RiverDaemonIdentityRecords.LockRecord(
        canonicalPath(datadir), bootstrap.high, bootstrap.low, bootstrap.pid, bootstrap.start,
        bootstrap.nonce);
  }

  private static boolean hasEntry(DirectoryListResult entries, String wanted) {
    for (int index = 0; index < entries.size(); index++) {
      if (wanted.equals(entries.name(index))) return true;
    }
    return false;
  }

  private static boolean isLifecycleEntry(String name) {
    if ("runtime.properties".equals(name) || "stop.request".equals(name)) return true;
    if (name.startsWith(".stop-request-") && name.endsWith(".stage")) {
      String nonce = name.substring(".stop-request-".length(), name.length() - ".stage".length());
      return nonce.matches("[0-9a-f]{32}");
    }
    if (name.startsWith(".stop-accepted-")) {
      String nonce = name.substring(".stop-accepted-".length());
      return nonce.matches("[0-9a-f]{32}");
    }
    return false;
  }

  private static boolean isIdentityResidueEntry(String name) {
    if (name.startsWith(".bootstrap-") && name.endsWith(".stage")) {
      String nonce = name.substring(".bootstrap-".length(), name.length() - ".stage".length());
      return nonce.matches("[0-9a-f]{32}");
    }
    if (name.startsWith(".riverd-bootstrap-")) {
      String nonce = name.substring(".riverd-bootstrap-".length());
      return nonce.matches("[0-9a-f]{32}");
    }
    if (name.startsWith(".instance-") && name.endsWith(".stage")) {
      String nonce = name.substring(".instance-".length(), name.length() - ".stage".length());
      return nonce.matches("[0-9a-f]{32}");
    }
    return false;
  }

  private static String prebootstrapStageName(
      DirectoryListResult entries, boolean hasBootstrap, boolean hasLock) {
    if (hasBootstrap || !hasLock || entries.size() != 2) return null;
    String stage = null;
    for (int index = 0; index < entries.size(); index++) {
      String name = entries.name(index);
      if (LOCK_FILE.equals(name)) continue;
      if (stage != null || !name.startsWith(".bootstrap-") || !name.endsWith(".stage")) {
        return null;
      }
      String value = name.substring(".bootstrap-".length(), name.length() - ".stage".length());
      if (!value.matches("[0-9a-f]{32}")) return null;
      stage = name;
    }
    return stage;
  }

  private static String nonce(SecureRandom random) {
    byte[] bytes = new byte[16];
    random.nextBytes(bytes);
    String value = java.util.HexFormat.of().formatHex(bytes);
    Arrays.fill(bytes, (byte) 0);
    return value;
  }

  private static String canonicalPath(Path path) {
    return path.toAbsolutePath().normalize().toString();
  }

  private static boolean validDatadir(Path path) {
    return path != null && RiverDaemonIdentityRecords.validDatadir(path.toString());
  }

  private static void closeQuiet(RiverDirectory directory) {
    if (directory != null) directory.close();
  }

  private static void closeDirectory(RiverDirectory directory, StatusCode status) {
    if (directory != null) directory.close();
  }

  private static final class StageRepairResult {
    private String name;
    private io.riverdb.platform.riverd.FileIdentity identity;

    private void reset() {
      name = null;
      identity = null;
    }
  }

  public static final class IdentityResult {
    private RiverDirectory directory;
    private RiverLock lock;
    private DatabaseIncarnation incarnation;
    private long generation;
    private String nonce;
    private RiverDirectory staging;
    private RiverDirectory database;
    private RiverDirectory security;
    private RiverFile lockFile;
    private boolean databasePublished;
    private boolean securityPublished;
    private String datadir;
    private long ownerPid;
    private long ownerStart;
    private String ownerNonce;
    private RiverDaemonIdentityRecords.LockRecord priorOwner;
    private boolean needsOwnerHandoff;
    private String stageRepairName;
    private io.riverdb.platform.riverd.FileIdentity stageRepairIdentity;

    public void reset() {
      directory = null;
      lock = null;
      incarnation = null;
      generation = 0;
      nonce = null;
      staging = null;
      database = null;
      security = null;
      lockFile = null;
      databasePublished = false;
      securityPublished = false;
      datadir = null;
      ownerPid = 0;
      ownerStart = 0;
      ownerNonce = null;
      priorOwner = null;
      needsOwnerHandoff = false;
      stageRepairName = null;
      stageRepairIdentity = null;
    }

    void complete(
        RiverDirectory openedDirectory,
        RiverLock openedLock,
        DatabaseIncarnation openedIncarnation,
        long openedGeneration) {
      directory = openedDirectory;
      lock = openedLock;
      incarnation = openedIncarnation;
      generation = openedGeneration;
    }

    void setBootstrap(
        String openedNonce,
        RiverDirectory openedStaging,
        RiverDirectory openedDatabase,
        RiverDirectory openedSecurity,
        RiverFile openedLockFile,
        boolean openedDatabasePublished,
        boolean openedSecurityPublished,
        String openedDatadir,
        long openedOwnerPid,
        long openedOwnerStart,
        boolean openedNeedsOwnerHandoff,
        String openedStageRepairName,
        io.riverdb.platform.riverd.FileIdentity openedStageRepairIdentity) {
      nonce = openedNonce;
      staging = openedStaging;
      database = openedDatabase;
      security = openedSecurity;
      lockFile = openedLockFile;
      databasePublished = openedDatabasePublished;
      securityPublished = openedSecurityPublished;
      datadir = openedDatadir;
      ownerPid = openedOwnerPid;
      ownerStart = openedOwnerStart;
      ownerNonce = openedNonce;
      needsOwnerHandoff = openedNeedsOwnerHandoff;
      stageRepairName = openedStageRepairName;
      stageRepairIdentity = openedStageRepairIdentity;
    }

    void setLockFile(RiverFile openedLockFile) {
      lockFile = openedLockFile;
    }

    void setPriorOwner(RiverDaemonIdentityRecords.LockRecord openedPriorOwner) {
      priorOwner = openedPriorOwner;
    }

    void prepareRestart(
        String openedDatadir,
        RiverDaemonIdentityRecords.LockRecord openedPriorOwner,
        String openedOwnerNonce,
        long openedOwnerPid,
        long openedOwnerStart) {
      datadir = openedDatadir;
      priorOwner = openedPriorOwner;
      ownerNonce = openedOwnerNonce;
      ownerPid = openedOwnerPid;
      ownerStart = openedOwnerStart;
      needsOwnerHandoff = true;
    }

    public RiverDirectory directory() { return directory; }
    public RiverLock lock() { return lock; }
    public DatabaseIncarnation incarnation() { return incarnation; }
    public long generation() { return generation; }
    RiverDirectory staging() { return staging; }

    /** Verified private component capability; its owner must initialize and validate its contents. */
    RiverDirectory database() { return database; }

    /** Verified private component capability; its owner must initialize and validate its contents. */
    RiverDirectory security() { return security; }

    boolean databasePublished() { return databasePublished; }
    boolean securityPublished() { return securityPublished; }
    String nonce() { return nonce; }
    RiverFile lockFile() { return lockFile; }
    RiverDaemonIdentityRecords.LockRecord priorOwner() { return priorOwner; }
    String ownerNonce() { return ownerNonce; }

    RiverDaemonIdentityRecords.LockRecord currentOwner() {
      return new RiverDaemonIdentityRecords.LockRecord(
          datadir, incarnation.high(), incarnation.low(), ownerPid, ownerStart,
          ownerNonce);
    }
    boolean needsOwnerHandoff() { return needsOwnerHandoff; }

    /** Closes retained capabilities in reverse creation order, preserving the first failure. */
    public synchronized StatusCode close() {
      StatusCode status = StatusCode.OK;
      status = combine(status, closeDirectory(security));
      status = combine(status, closeDirectory(database));
      status = combine(status, closeDirectory(staging));
      status = combine(status, lock == null ? StatusCode.OK : lock.close());
      status = combine(status, lockFile == null ? StatusCode.OK : lockFile.close());
      status = combine(status, closeDirectory(directory));
      security = null;
      database = null;
      staging = null;
      lock = null;
      lockFile = null;
      directory = null;
      return status;
    }

    private static StatusCode closeDirectory(RiverDirectory value) {
      return value == null ? StatusCode.OK : value.close();
    }

    private static StatusCode combine(StatusCode first, StatusCode next) {
      return first.isOk() && next != StatusCode.OK && next != StatusCode.CLOSED
          ? next : first;
    }
  }
}
