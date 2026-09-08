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
  public static final String AUDIT_NAME = "audit";
  private static final int MAX_RECORD_BYTES = RiverDaemonIdentityRecords.MAX_RECORD_BYTES;

  private RiverDaemonIdentity() {
  }

  /** Opens an existing instance and validates its sole restart authority. */
  public static StatusCode openExisting(
      Path datadir,
      RiverDaemonFileSystem filesystem,
      IdentityResult result) {
    if (result == null || filesystem == null || !validDatadir(datadir)) {
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
      }
    }
    return status;
  }

  /** Begins a first-create transaction, leaving database/security/audit initialization to owners. */
  public static StatusCode beginCreate(
      Path datadir,
      RiverDaemonFileSystem filesystem,
      DatabaseIncarnation incarnation,
      SecureRandom random,
      long pid,
      long processStartEpochMillis,
      String command,
      IdentityResult result) {
    if (result == null || filesystem == null || !validDatadir(datadir)
        || incarnation == null || !incarnation.isValid() || random == null || pid <= 0
        || processStartEpochMillis < 0 || !validCommand(command)) {
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
    if (hasEntry(entries, INSTANCE_FILE) || hasEntry(entries, DATABASE_NAME)
        || hasEntry(entries, SECURITY_NAME) || hasEntry(entries, AUDIT_NAME)) {
      if (hasEntry(entries, "bootstrap.properties")) {
        return recoverCreate(datadir, directory, filesystem, incarnation, pid,
            processStartEpochMillis, command, entries, result);
      }
      closeDirectory(directory, StatusCode.CONFLICT);
      return StatusCode.CONFLICT;
    }
    if (hasEntry(entries, "bootstrap.properties")) {
      return recoverCreate(datadir, directory, filesystem, incarnation, pid,
          processStartEpochMillis, command, entries, result);
    }

    RiverFileResult lockFileResult = new RiverFileResult();
    status = directory.openFile(LOCK_FILE, RiverOpenMode.CREATE_NEW, lockFileResult);
    // An existing lock file is a recovery case and remains preserved for the
    // lifecycle owner to inspect with its process proof.
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
    String nonce = nonce(random);
    status = writeLock(lockFile, datadir, incarnation, pid, processStartEpochMillis, command, nonce);
    if (status.isOk()) {
      status = writeBootstrap(directory, incarnation, pid, processStartEpochMillis, command, nonce);
    }
    RiverDirectory staging = null;
    RiverDirectory database = null;
    RiverDirectory security = null;
    RiverDirectory audit = null;
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
      if (status.isOk()) {
        RiverDirectoryResult child = new RiverDirectoryResult();
        status = staging.createDirectory(AUDIT_NAME, child);
        audit = child.directory();
      }
    }
    if (!status.isOk()) {
      closeQuiet(audit);
      closeQuiet(security);
      closeQuiet(database);
      closeQuiet(staging);
      lockResult.lock().close();
      lockFile.close();
      closeDirectory(directory, status);
      return status;
    }
    result.complete(directory, lockResult.lock(), incarnation, 1L);
    result.setBootstrap(nonce, staging, database, security, audit, lockFile,
        false, false, false, canonicalPath(datadir), pid, processStartEpochMillis, command, false,
        null, null);
    return StatusCode.OK;
  }

  /**
   * Reopens a valid bootstrap transaction and resumes only its fixed, recorded namespace.
   *
   * <p>The caller has already opened {@code datadir}; every child operation below is relative to
   * that capability. A new identity, nonce, path, or child name is never inferred during recovery.
   */
  private static StatusCode recoverCreate(
      Path datadir,
      RiverDirectory directory,
      RiverDaemonFileSystem filesystem,
      DatabaseIncarnation requestedIncarnation,
      long pid,
      long processStartEpochMillis,
      String command,
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
      DATABASE_NAME, SECURITY_NAME, AUDIT_NAME, stagingName, instanceStageName};
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
    boolean auditPublished = hasEntry(entries, AUDIT_NAME);
    if ((securityPublished && !databasePublished) || (auditPublished && !securityPublished)) {
      held.close();
      lockFile.close();
      closeDirectory(directory, StatusCode.CORRUPTION);
      return StatusCode.CORRUPTION;
    }

    RiverDirectory staging = null;
    RiverDirectory database = null;
    RiverDirectory security = null;
    RiverDirectory audit = null;
    RiverDirectoryResult opened = new RiverDirectoryResult();
    StageRepairResult stageRepair = new StageRepairResult();
    if (hasEntry(entries, instanceStageName)) {
      status = inspectInstanceStage(directory, instanceStageName, bootstrap.incarnation,
          stageRepair);
      if (status.isOk() && stageRepair.identity != null
          && !(databasePublished && securityPublished && auditPublished)) {
        status = StatusCode.CORRUPTION;
      }
    }
    if (hasEntry(entries, stagingName)) {
      if (status.isOk()) {
        status = directory.openDirectory(stagingName, opened);
        staging = opened.directory();
      }
      if (status.isOk()) status = validateStagingNames(staging, databasePublished,
          securityPublished, auditPublished);
    }
    if (status.isOk()) status = validateExistingComponents(
        directory, staging, databasePublished, securityPublished, auditPublished);
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
    if (status.isOk()) status = openOrCreateRecoveryChild(
        directory, staging, AUDIT_NAME, auditPublished, opened);
    if (status.isOk()) audit = opened.directory();
    if (!status.isOk()) {
      closeQuiet(audit);
      closeQuiet(security);
      closeQuiet(database);
      closeQuiet(staging);
      held.close();
      lockFile.close();
      closeDirectory(directory, status);
      return status;
    }
    result.complete(directory, held, bootstrap.incarnation, 1L);
    result.setBootstrap(bootstrap.nonce, staging, database, security, audit, lockFile,
        databasePublished, securityPublished, auditPublished, canonicalPath(datadir), pid,
        processStartEpochMillis, command, true, stageRepair.name, stageRepair.identity);
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
      boolean securityPublished,
      boolean auditPublished) {
    String[] names = {DATABASE_NAME, SECURITY_NAME, AUDIT_NAME};
    boolean[] published = {databasePublished, securityPublished, auditPublished};
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
    if (!result.needsOwnerHandoff) return StatusCode.OK;
    StatusCode status = validateCurrentOwner(result.ownerPid, result.ownerStart, result.ownerCommand);
    if (!status.isOk()) return status;
    status = result.lockFile.truncate(0);
    if (status.isOk()) {
      status = writeLockCanonical(result.lockFile, result.datadir, result.incarnation,
          result.ownerPid, result.ownerStart, result.ownerCommand, result.nonce);
    }
    if (status.isOk()) result.needsOwnerHandoff = false;
    return status;
  }

  private static StatusCode validateCurrentOwner(long pid, long start, String command) {
    ProcessHandle current = ProcessHandle.current();
    if (current.pid() != pid) return StatusCode.NOT_OWNER;
    ProcessHandle.Info info = current.info();
    if (info.startInstant().isEmpty() || info.command().isEmpty()) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    return info.startInstant().get().toEpochMilli() == start
        && command.equals(info.command().get()) ? StatusCode.OK : StatusCode.NOT_OWNER;
  }

  private static StatusCode validateStagingNames(
      RiverDirectory staging, boolean databasePublished, boolean securityPublished,
      boolean auditPublished) {
    DirectoryListResult entries = new DirectoryListResult(8);
    StatusCode status = staging.list(entries);
    if (!status.isOk()) return status;
    for (int index = 0; index < entries.size(); index++) {
      String name = entries.name(index);
      boolean allowed = (!databasePublished && DATABASE_NAME.equals(name))
          || (!securityPublished && SECURITY_NAME.equals(name))
          || (!auditPublished && AUDIT_NAME.equals(name));
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
   * binding; it does not interpret database, credential, or audit formats.
   */
  public static StatusCode completeCreate(IdentityResult result) {
    if (result == null || result.directory == null || result.lock == null
        || result.staging == null || result.database == null || result.security == null
        || result.audit == null || result.nonce == null || result.lockFile == null) {
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
    if (status.isOk() && !result.auditPublished) {
      status = publishDirectory(directory, result.staging, result.audit, AUDIT_NAME);
      if (status.isOk()) result.auditPublished = true;
    }
    if (status.isOk()) {
      String stageName = ".instance-" + result.nonce + ".stage";
      boolean stageExisted = hasEntryForStage(directory, stageName);
      status = directory.openFile(stageName, RiverOpenMode.CREATE_NEW, stageResult);
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
    if (!status.isOk() && stageResult.file() != null) {
      StatusCode closeStatus = stageResult.file().close();
      if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
        status = closeStatus;
      }
    }
    if (status.isOk()) {
      status = removeOwned(directory, "bootstrap.properties", null);
    }
    if (status.isOk()) {
      status = removeOwned(directory, ".riverd-bootstrap-" + result.nonce,
          result.staging.identity());
    }
    if (status.isOk()) {
      DirectoryOperationResult forced = new DirectoryOperationResult();
      status = directory.force(forced);
    }
    return status;
  }

  private static boolean hasEntryForStage(RiverDirectory directory, String wanted) {
    DirectoryListResult entries = new DirectoryListResult(16);
    return directory.list(entries).isOk() && hasEntry(entries, wanted);
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
      String command,
      String nonce) {
    return writeLockCanonical(file, canonicalPath(datadir), incarnation, pid, start, command, nonce);
  }

  private static StatusCode writeLockCanonical(
      RiverFile file,
      String datadir,
      DatabaseIncarnation incarnation,
      long pid,
      long start,
      String command,
      String nonce) {
    String body = RiverDaemonIdentityRecords.record(List.of(
      "format=" + RiverDaemonIdentityRecords.LOCK_FORMAT,
      "datadir=" + datadir,
        "database-incarnation-high=" + incarnation.high(),
        "database-incarnation-low=" + incarnation.low(),
        "pid=" + pid,
        "process-start-epoch-millis=" + start,
        "command=" + command,
        "owner-nonce=" + nonce));
    return write(file, body.getBytes(StandardCharsets.UTF_8));
  }

  private static StatusCode writeBootstrap(
      RiverDirectory directory,
      DatabaseIncarnation incarnation,
      long pid,
      long start,
      String command,
      String nonce) {
    String stageName = ".bootstrap-" + nonce + ".stage";
    String body = RiverDaemonIdentityRecords.record(List.of(
        "format=" + RiverDaemonIdentityRecords.BOOTSTRAP_FORMAT,
        "database-incarnation-high=" + incarnation.high(),
        "database-incarnation-low=" + incarnation.low(),
        "pid=" + pid,
        "process-start-epoch-millis=" + start,
        "command=" + command,
        "attempt-nonce=" + nonce,
        "database-name=" + DATABASE_NAME,
        "security-name=" + SECURITY_NAME,
        "audit-name=" + AUDIT_NAME,
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
    if (info.startInstant().isEmpty() || info.command().isEmpty()) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    long start = info.startInstant().get().toEpochMilli();
    String command = info.command().get();
    return owner.start == start && owner.command.equals(command)
        ? StatusCode.CONFLICT : StatusCode.OK;
  }

  private static RiverDaemonIdentityRecords.LockRecord bootstrapOwner(
      Path datadir, RiverDaemonIdentityRecords.BootstrapRecord bootstrap) {
    return new RiverDaemonIdentityRecords.LockRecord(
        canonicalPath(datadir), bootstrap.high, bootstrap.low, bootstrap.pid, bootstrap.start,
        bootstrap.command, bootstrap.nonce);
  }

  private static boolean hasEntry(DirectoryListResult entries, String wanted) {
    for (int index = 0; index < entries.size(); index++) {
      if (wanted.equals(entries.name(index))) return true;
    }
    return false;
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
    return path != null && path.isAbsolute() && path.equals(path.normalize())
        && path.getFileName() != null && path.getFileName().toString().indexOf('=') < 0;
  }

  private static boolean validCommand(String command) {
    if (command == null || command.isBlank() || !command.equals(command.trim())) return false;
    try {
      Path path = Path.of(command);
      return path.isAbsolute() && path.normalize().equals(path);
    } catch (RuntimeException failure) {
      return false;
    }
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
    private RiverDirectory audit;
    private RiverFile lockFile;
    private boolean databasePublished;
    private boolean securityPublished;
    private boolean auditPublished;
    private String datadir;
    private long ownerPid;
    private long ownerStart;
    private String ownerCommand;
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
      audit = null;
      lockFile = null;
      databasePublished = false;
      securityPublished = false;
      auditPublished = false;
      datadir = null;
      ownerPid = 0;
      ownerStart = 0;
      ownerCommand = null;
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
        RiverDirectory openedAudit,
        RiverFile openedLockFile,
        boolean openedDatabasePublished,
        boolean openedSecurityPublished,
        boolean openedAuditPublished,
        String openedDatadir,
        long openedOwnerPid,
        long openedOwnerStart,
        String openedOwnerCommand,
        boolean openedNeedsOwnerHandoff,
        String openedStageRepairName,
        io.riverdb.platform.riverd.FileIdentity openedStageRepairIdentity) {
      nonce = openedNonce;
      staging = openedStaging;
      database = openedDatabase;
      security = openedSecurity;
      audit = openedAudit;
      lockFile = openedLockFile;
      databasePublished = openedDatabasePublished;
      securityPublished = openedSecurityPublished;
      auditPublished = openedAuditPublished;
      datadir = openedDatadir;
      ownerPid = openedOwnerPid;
      ownerStart = openedOwnerStart;
      ownerCommand = openedOwnerCommand;
      needsOwnerHandoff = openedNeedsOwnerHandoff;
      stageRepairName = openedStageRepairName;
      stageRepairIdentity = openedStageRepairIdentity;
    }

    void setLockFile(RiverFile openedLockFile) {
      lockFile = openedLockFile;
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

    /** Verified private component capability; its owner must initialize and validate its contents. */
    RiverDirectory audit() { return audit; }

    boolean databasePublished() { return databasePublished; }
    boolean securityPublished() { return securityPublished; }
    boolean auditPublished() { return auditPublished; }
    String nonce() { return nonce; }
    RiverFile lockFile() { return lockFile; }

    /** Closes retained capabilities in reverse creation order, preserving the first failure. */
    public synchronized StatusCode close() {
      StatusCode status = StatusCode.OK;
      status = combine(status, closeDirectory(audit));
      status = combine(status, closeDirectory(security));
      status = combine(status, closeDirectory(database));
      status = combine(status, closeDirectory(staging));
      status = combine(status, lock == null ? StatusCode.OK : lock.close());
      status = combine(status, lockFile == null ? StatusCode.OK : lockFile.close());
      status = combine(status, closeDirectory(directory));
      audit = null;
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
