package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.riverd.FileIdentity;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverLock;
import io.riverdb.platform.riverd.RiverLockResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import java.nio.file.Path;
import java.util.Arrays;

final class RiverDaemonIdentityRecovery {
  private static final int MAX_RECORD_BYTES = RiverDaemonRecordEnvelope.MAX_RECORD_BYTES;
  private RiverDaemonIdentityRecovery() {}

  static StatusCode recoverCreate(Path datadir, RiverDirectory directory,
      RiverDaemonFileSystem filesystem, long pid, long start, DirectoryListResult entries,
      RiverDaemonIdentity.IdentityResult result) {
    if (!RiverDaemonIdentityNamespace.validDatadir(datadir)
        || directory == null || filesystem == null || entries == null) {
      return close(directory, StatusCode.INVALID_EXTERNAL_INPUT);
    }
    if (RiverDaemonIdentityNamespace.hasEntry(entries, RiverDaemonIdentity.INSTANCE_FILE)) {
      return close(directory, StatusCode.CONFLICT);
    }
    HeldLock held = acquire(directory, filesystem);
    if (!held.status.isOk()) return close(directory, held.status);
    RecoveryState state = RecoveryState.read(datadir, directory, held.file);
    if (!state.status.isOk()) return release(directory, held, state.status);
    StatusCode status = state.validateNamespace(entries, directory);
    if (!status.isOk()) return release(directory, held, status);
    Components components = Components.open(directory, entries, state);
    if (!components.status.isOk()) {
      closeQuiet(components.security);
      closeQuiet(components.database);
      closeQuiet(components.staging);
      return release(directory, held, components.status);
    }
    result.complete(directory, held.lock, state.bootstrap.incarnation, 1L);
    result.setBootstrap(state.bootstrap.nonce, components.staging, components.database,
        components.security, held.file, components.databasePublished, components.securityPublished,
        RiverDaemonIdentityNamespace.canonicalPath(datadir), pid, start, true,
        state.repair.name, state.repair.identity);
    result.setPriorOwner(state.lock);
    return StatusCode.OK;
  }

  private static HeldLock acquire(RiverDirectory directory, RiverDaemonFileSystem filesystem) {
    RiverFileResult fileResult = new RiverFileResult();
    StatusCode status = directory.openFile(RiverDaemonIdentity.LOCK_FILE,
        RiverOpenMode.EXISTING, fileResult);
    if (!status.isOk()) return new HeldLock(status, null, null);
    RiverFile file = fileResult.file();
    RiverLockResult lockResult = new RiverLockResult();
    status = filesystem.acquireExclusive(file, lockResult);
    if (!status.isOk()) {
      file.close();
      return new HeldLock(status, null, null);
    }
    return new HeldLock(StatusCode.OK, file, lockResult.lock());
  }

  private static StatusCode release(RiverDirectory directory, HeldLock held, StatusCode status) {
    held.lock.close();
    held.file.close();
    return close(directory, status);
  }

  private static StatusCode close(RiverDirectory directory, StatusCode status) {
    if (directory != null) directory.close();
    return status;
  }

  private static void closeQuiet(RiverDirectory directory) {
    if (directory != null) directory.close();
  }

  private static final class HeldLock {
    private final StatusCode status;
    private final RiverFile file;
    private final RiverLock lock;
    private HeldLock(StatusCode status, RiverFile file, RiverLock lock) {
      this.status = status; this.file = file; this.lock = lock;
    }
  }

  private static final class RecoveryState {
    private final StatusCode status;
    private final RiverDaemonIdentityRecords.BootstrapRecord bootstrap;
    private final RiverDaemonIdentityRecords.LockRecord lock;
    private final StageRepairResult repair;
    private boolean databasePublished;
    private boolean securityPublished;
    private RecoveryState(StatusCode status,
        RiverDaemonIdentityRecords.BootstrapRecord bootstrap,
        RiverDaemonIdentityRecords.LockRecord lock, StageRepairResult repair) {
      this.status = status; this.bootstrap = bootstrap; this.lock = lock; this.repair = repair;
    }

    private static RecoveryState read(Path datadir, RiverDirectory directory, RiverFile lockFile) {
      LockRead lock = readLock(lockFile);
      BootstrapRead bootstrap = readBootstrap(directory);
      if (!bootstrap.status.isOk()) {
        return new RecoveryState(bootstrap.status, bootstrap.record, lock.owner, null);
      }
      if (lock.status != StatusCode.OK && lock.status != StatusCode.CORRUPTION) {
        return new RecoveryState(lock.status, bootstrap.record, lock.owner, null);
      }
      StatusCode status = validateOwners(datadir, lock.status, lock.owner, bootstrap.record);
      return new RecoveryState(status, bootstrap.record, lock.owner, new StageRepairResult());
    }

    private static LockRead readLock(RiverFile lockFile) {
      byte[] bytes = new byte[MAX_RECORD_BYTES];
      StatusCode status = RiverDaemonIdentityFiles.readRecord(
          lockFile, bytes, RiverDaemonIdentity.LOCK_FILE);
      RiverDaemonIdentityRecords.LockRecord owner = status.isOk()
          ? RiverDaemonIdentityRecords.parseLock(bytes) : null;
      Arrays.fill(bytes, (byte) 0);
      return new LockRead(status, owner);
    }

    private static BootstrapRead readBootstrap(RiverDirectory directory) {
      RiverFileResult fileResult = new RiverFileResult();
      StatusCode status = directory.openFile("bootstrap.properties",
          RiverOpenMode.EXISTING, fileResult);
      if (!status.isOk()) return new BootstrapRead(status, null);
      RiverFile file = fileResult.file();
      byte[] bytes = new byte[MAX_RECORD_BYTES];
      status = RiverDaemonIdentityFiles.readRecord(file, bytes, "bootstrap.properties");
      RiverDaemonIdentityRecords.BootstrapRecord bootstrap = status.isOk()
          ? RiverDaemonIdentityRecords.parseBootstrap(bytes) : null;
      Arrays.fill(bytes, (byte) 0);
      StatusCode closeStatus = file.close();
      if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
        status = closeStatus;
      }
      if (status.isOk() && (bootstrap == null || !bootstrap.incarnation.isValid())) {
        status = StatusCode.CORRUPTION;
      }
      return new BootstrapRead(status, bootstrap);
    }

    private static StatusCode validateOwners(Path datadir, StatusCode lockStatus,
        RiverDaemonIdentityRecords.LockRecord owner,
        RiverDaemonIdentityRecords.BootstrapRecord bootstrap) {
      if (owner != null) {
        lockStatus = RiverDaemonIdentityNamespace.proveOwnerAbsent(owner);
        if (!lockStatus.isOk()) return lockStatus;
      }
      return RiverDaemonIdentityNamespace.proveOwnerAbsent(
          RiverDaemonIdentityNamespace.bootstrapOwner(datadir, bootstrap));
    }

    private record LockRead(StatusCode status, RiverDaemonIdentityRecords.LockRecord owner) { }

    private record BootstrapRead(StatusCode status,
        RiverDaemonIdentityRecords.BootstrapRecord record) { }

    private StatusCode validateNamespace(DirectoryListResult entries, RiverDirectory directory) {
      if (bootstrap == null || !validNames() || !allowedEntries(entries)) {
        return StatusCode.CORRUPTION;
      }
      databasePublished = RiverDaemonIdentityNamespace.hasEntry(
          entries, RiverDaemonIdentity.DATABASE_NAME);
      securityPublished = RiverDaemonIdentityNamespace.hasEntry(
          entries, RiverDaemonIdentity.SECURITY_NAME);
      if (securityPublished && !databasePublished) return StatusCode.CORRUPTION;
      if (!RiverDaemonIdentityNamespace.hasEntry(entries, bootstrap.instanceStageName)) {
        return StatusCode.OK;
      }
      StatusCode status = StageRepairResult.inspect(directory, bootstrap.instanceStageName,
          bootstrap.incarnation, repair);
      if (!status.isOk()) return status;
      return repair.identity != null && !(databasePublished && securityPublished)
          ? StatusCode.CORRUPTION : StatusCode.OK;
    }

    private boolean validNames() {
      return (".riverd-bootstrap-" + bootstrap.nonce).equals(bootstrap.stagingName)
          && (".instance-" + bootstrap.nonce + ".stage").equals(bootstrap.instanceStageName);
    }

    private boolean allowedEntries(DirectoryListResult entries) {
      String[] allowed = {RiverDaemonIdentity.LOCK_FILE, "bootstrap.properties",
        RiverDaemonIdentity.INSTANCE_FILE, RiverDaemonIdentity.DATABASE_NAME,
        RiverDaemonIdentity.SECURITY_NAME, bootstrap.stagingName, bootstrap.instanceStageName};
      for (int index = 0; index < entries.size(); index++) {
        boolean known = false;
        for (String name : allowed) known |= name.equals(entries.name(index));
        if (!known) return false;
      }
      return true;
    }
  }

  private static final class Components {
    private final StatusCode status;
    private final RiverDirectory staging;
    private final RiverDirectory database;
    private final RiverDirectory security;
    private final boolean databasePublished;
    private final boolean securityPublished;
    private Components(StatusCode status, RiverDirectory staging, RiverDirectory database,
        RiverDirectory security, boolean databasePublished, boolean securityPublished) {
      this.status = status; this.staging = staging; this.database = database;
      this.security = security; this.databasePublished = databasePublished;
      this.securityPublished = securityPublished;
    }

    private static Components open(RiverDirectory directory, DirectoryListResult entries,
        RecoveryState state) {
      RiverDirectory staging = null;
      RiverDirectory database = null;
      RiverDirectory security = null;
      RiverDirectoryResult opened = new RiverDirectoryResult();
      StatusCode status = StatusCode.OK;
      if (RiverDaemonIdentityNamespace.hasEntry(entries, state.bootstrap.stagingName)) {
        status = directory.openDirectory(state.bootstrap.stagingName, opened);
        if (status.isOk()) {
          staging = opened.directory();
          status = RiverDaemonIdentityNamespaceValidation.validateStagingNames(
              staging, state.databasePublished, state.securityPublished);
        }
      }
      if (status.isOk()) status = RiverDaemonIdentityNamespaceValidation.validateExistingComponents(
          directory, staging, state.databasePublished, state.securityPublished);
      if (status.isOk() && staging == null) {
        status = directory.createDirectory(state.bootstrap.stagingName, opened);
        staging = opened.directory();
        if (status.isOk()) status = RiverDaemonIdentityFiles.forceDirectory(directory);
      }
      if (status.isOk()) {
        status = openChild(directory, staging, RiverDaemonIdentity.DATABASE_NAME,
            state.databasePublished, opened);
        database = opened.directory();
      }
      if (status.isOk()) {
        status = openChild(directory, staging, RiverDaemonIdentity.SECURITY_NAME,
            state.securityPublished, opened);
        security = opened.directory();
      }
      return new Components(status, staging, database, security,
          state.databasePublished, state.securityPublished);
    }

    private static StatusCode openChild(RiverDirectory directory, RiverDirectory staging,
        String name, boolean published, RiverDirectoryResult result) {
      result.reset();
      if (published) return directory.openDirectory(name, result);
      StatusCode status = staging.openDirectory(name, result);
      if (status == StatusCode.CONFLICT) status = staging.createDirectory(name, result);
      if (status.isOk()) status = RiverDaemonIdentityFiles.forceDirectory(staging);
      return status;
    }
  }

  private static final class StageRepairResult {
    private String name;
    private FileIdentity identity;
    private void reset() { name = null; identity = null; }

    private static StatusCode inspect(RiverDirectory directory, String name,
        DatabaseIncarnation incarnation, StageRepairResult repair) {
      repair.reset();
      RiverFileResult result = new RiverFileResult();
      StatusCode status = directory.openFile(name, RiverOpenMode.EXISTING, result);
      if (!status.isOk()) return status;
      FileIdentity identity = result.file().identity();
      byte[] bytes = new byte[MAX_RECORD_BYTES];
      RiverDaemonIdentityRecords.InstanceRecord record = null;
      status = RiverDaemonIdentityFiles.readRecord(result.file(), bytes, name);
      if (status.isOk()) record = RiverDaemonIdentityRecords.parseInstance(bytes);
      Arrays.fill(bytes, (byte) 0);
      StatusCode close = result.file().close();
      if (status.isOk() && !close.isOk() && close != StatusCode.CLOSED) status = close;
      return inspectRecord(status, record, identity, incarnation, name, repair);
    }

    private static StatusCode inspectRecord(StatusCode status,
        RiverDaemonIdentityRecords.InstanceRecord record, FileIdentity identity,
        DatabaseIncarnation incarnation, String name, StageRepairResult repair) {
      if (!status.isOk() && status != StatusCode.CORRUPTION) return status;
      if (record == null || !status.isOk()) {
        if (identity == null) return StatusCode.CORRUPTION;
        repair.name = name;
        repair.identity = identity;
        return StatusCode.OK;
      }
      return record.incarnation.high() == incarnation.high()
          && record.incarnation.low() == incarnation.low()
          ? StatusCode.OK : StatusCode.CORRUPTION;
    }
  }
}
