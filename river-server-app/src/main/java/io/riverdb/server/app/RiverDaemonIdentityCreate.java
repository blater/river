package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.platform.file.*;
import io.riverdb.platform.riverd.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

final class RiverDaemonIdentityCreate {
  private static final int MAX_RECORD_BYTES = RiverDaemonRecordEnvelope.MAX_RECORD_BYTES;
  private RiverDaemonIdentityCreate() {}
  static StatusCode beginCreate(
      Path datadir,
      RiverDaemonFileSystem filesystem,
      DatabaseIncarnation incarnation,
      SecureRandom random,
      long pid,
      long processStartEpochMillis,
      RiverDaemonIdentity.IdentityResult result) {
    if (result == null || filesystem == null || !RiverDaemonIdentityNamespace.validDatadir(datadir)
        || incarnation == null || !incarnation.isValid() || random == null || pid <= 0
        || processStartEpochMillis < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    RiverDirectoryResult directoryResult = new RiverDirectoryResult();
    StatusCode status = RiverDaemonIdentityFiles.openOrCreateDirectory(datadir, filesystem, directoryResult);
    if (!status.isOk()) return status;
    RiverDirectory directory = directoryResult.directory();
    DirectoryListResult entries = new DirectoryListResult(16);
    status = directory.list(entries);
    if (!status.isOk()) {
      RiverDaemonIdentityFiles.closeDirectory(directory, status);
      return status;
    }
    return continueCreate(datadir, directory, filesystem, incarnation, random, pid,
        processStartEpochMillis, entries, result);
  }

  private static StatusCode continueCreate(
      Path datadir, RiverDirectory directory, RiverDaemonFileSystem filesystem,
      DatabaseIncarnation incarnation, SecureRandom random, long pid,
      long processStartEpochMillis, DirectoryListResult entries,
      RiverDaemonIdentity.IdentityResult result) {
    boolean hasBootstrap = RiverDaemonIdentityNamespace.hasEntry(entries, "bootstrap.properties");
    boolean hasLock = RiverDaemonIdentityNamespace.hasEntry(entries, RiverDaemonIdentity.LOCK_FILE);
    String prebootstrapStage = RiverDaemonIdentityNamespace.prebootstrapStageName(entries, hasBootstrap, hasLock);
    // Before bootstrap, mutation is limited to an empty tree, sole lock, or bound stage residue.
    if (!hasBootstrap && entries.size() != 0
        && !(entries.size() == 1 && hasLock) && prebootstrapStage == null) {
      RiverDaemonIdentityFiles.closeDirectory(directory, StatusCode.CORRUPTION);
      return StatusCode.CORRUPTION;
    }
    if (prebootstrapStage != null) {
      return RiverDaemonIdentityPrebootstrapRecovery.recoverPrebootstrapStage(datadir, directory, filesystem, incarnation, random, pid,
          processStartEpochMillis, prebootstrapStage, result);
    }
    if (RiverDaemonIdentityNamespace.hasEntry(entries, RiverDaemonIdentity.INSTANCE_FILE) || RiverDaemonIdentityNamespace.hasEntry(entries, RiverDaemonIdentity.DATABASE_NAME)
        || RiverDaemonIdentityNamespace.hasEntry(entries, RiverDaemonIdentity.SECURITY_NAME)) {
      if (hasBootstrap) {
        return RiverDaemonIdentityRecovery.recoverCreate(datadir, directory, filesystem, pid,
            processStartEpochMillis, entries, result);
      }
      RiverDaemonIdentityFiles.closeDirectory(directory, StatusCode.CONFLICT);
      return StatusCode.CONFLICT;
    }
    if (hasBootstrap) {
      return RiverDaemonIdentityRecovery.recoverCreate(datadir, directory, filesystem, pid,
          processStartEpochMillis, entries, result);
    }

    return openBootstrapLock(datadir, directory, filesystem, incarnation, random, pid,
        processStartEpochMillis, hasLock, result);
  }

  private static StatusCode openBootstrapLock(
      Path datadir, RiverDirectory directory, RiverDaemonFileSystem filesystem,
      DatabaseIncarnation incarnation, SecureRandom random, long pid,
      long processStartEpochMillis, boolean hasLock,
      RiverDaemonIdentity.IdentityResult result) {
    RiverFileResult lockFileResult = new RiverFileResult();
    StatusCode status = directory.openFile(RiverDaemonIdentity.LOCK_FILE,
        hasLock ? RiverOpenMode.EXISTING : RiverOpenMode.CREATE_NEW, lockFileResult);
    // A sole prebootstrap lock is reopened and verified before its owner record is replaced.
    if (!status.isOk()) {
      RiverDaemonIdentityFiles.closeDirectory(directory, status);
      return status;
    }
    RiverFile lockFile = lockFileResult.file();
    RiverLockResult lockResult = new RiverLockResult();
    status = filesystem.acquireExclusive(lockFile, lockResult);
    if (!status.isOk()) {
      lockFile.close();
      RiverDaemonIdentityFiles.closeDirectory(directory, status);
      return status;
    }
    PriorRead prior = hasLock ? readPriorOwner(directory, lockFile, datadir)
        : new PriorRead(StatusCode.OK, null);
    status = prior.status;
    if (!status.isOk()) {
      lockResult.lock().close();
      lockFile.close();
      RiverDaemonIdentityFiles.closeDirectory(directory, status);
      return status;
    }
    return RiverDaemonIdentityBootstrapCreate.createBootstrap(datadir, directory, lockFile,
        lockResult.lock(), incarnation, random, pid, processStartEpochMillis, result,
        prior.owner, hasLock);
  }

  private static PriorRead readPriorOwner(RiverDirectory directory, RiverFile lockFile,
      Path datadir) {
    DirectoryListResult entries = new DirectoryListResult(16);
    StatusCode status = directory.list(entries);
    if (status.isOk() && (entries.size() != 1
        || !RiverDaemonIdentityNamespace.hasEntry(entries, RiverDaemonIdentity.LOCK_FILE))) {
      status = StatusCode.CONFLICT;
    }
    byte[] bytes = new byte[MAX_RECORD_BYTES];
    RiverDaemonIdentityRecords.LockRecord owner = null;
    if (status.isOk()) {
      status = RiverDaemonIdentityFiles.readRecord(lockFile, bytes, RiverDaemonIdentity.LOCK_FILE);
      if (status == StatusCode.CORRUPTION) {
        status = StatusCode.OK;
      } else if (status.isOk()) {
        owner = RiverDaemonIdentityRecords.parseLock(bytes);
        if (owner != null) {
          status = RiverDaemonIdentityNamespace.canonicalPath(datadir).equals(owner.datadir)
              ? RiverDaemonIdentityNamespace.proveOwnerAbsent(owner) : StatusCode.CORRUPTION;
        }
      }
    }
    Arrays.fill(bytes, (byte) 0);
    return new PriorRead(status, owner);
  }

  private record PriorRead(StatusCode status, RiverDaemonIdentityRecords.LockRecord owner) { }


}
