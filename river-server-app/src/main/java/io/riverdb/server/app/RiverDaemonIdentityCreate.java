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
      RiverDaemonIdentityFiles.closeDirectory(directory, status.isOk() ? StatusCode.CONFLICT : status);
      return status.isOk() ? StatusCode.CONFLICT : status;
    }
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

    RiverFileResult lockFileResult = new RiverFileResult();
    status = directory.openFile(RiverDaemonIdentity.LOCK_FILE, hasLock ? RiverOpenMode.EXISTING : RiverOpenMode.CREATE_NEW,
        lockFileResult);
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
    RiverDaemonIdentityRecords.LockRecord priorOwner = null;
    if (hasLock) {
      DirectoryListResult heldEntries = new DirectoryListResult(16);
      status = directory.list(heldEntries);
      if (status.isOk() && (heldEntries.size() != 1 || !RiverDaemonIdentityNamespace.hasEntry(heldEntries, RiverDaemonIdentity.LOCK_FILE))) {
        status = StatusCode.CONFLICT;
      }
      byte[] priorBytes = new byte[MAX_RECORD_BYTES];
      if (status.isOk()) {
        status = RiverDaemonIdentityFiles.readRecord(lockFile, priorBytes, RiverDaemonIdentity.LOCK_FILE);
        if (status == StatusCode.CORRUPTION) {
          status = StatusCode.OK;
        } else if (status.isOk()) {
          priorOwner = RiverDaemonIdentityRecords.parseLock(priorBytes);
          if (priorOwner != null) {
            if (!RiverDaemonIdentityNamespace.canonicalPath(datadir).equals(priorOwner.datadir)) {
              status = StatusCode.CORRUPTION;
            } else {
              status = RiverDaemonIdentityNamespace.proveOwnerAbsent(priorOwner);
            }
          }
        }
      }
      Arrays.fill(priorBytes, (byte) 0);
    }
    if (!status.isOk()) {
      lockResult.lock().close();
      lockFile.close();
      RiverDaemonIdentityFiles.closeDirectory(directory, status);
      return status;
    }
    return RiverDaemonIdentityBootstrapCreate.createBootstrap(datadir, directory, lockFile, lockResult.lock(), incarnation, random,
        pid, processStartEpochMillis, result, priorOwner, hasLock);
  }


}
