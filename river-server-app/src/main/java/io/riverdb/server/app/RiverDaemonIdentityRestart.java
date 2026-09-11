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

final class RiverDaemonIdentityRestart {
  private static final int MAX_RECORD_BYTES = RiverDaemonIdentityRecords.MAX_RECORD_BYTES;
  private RiverDaemonIdentityRestart() {}
  static StatusCode openExisting(
      Path datadir,
      RiverDaemonFileSystem filesystem,
      SecureRandom random,
      long pid,
      long processStartEpochMillis,
      RiverDaemonIdentity.IdentityResult result) {
    if (result == null || filesystem == null || !RiverDaemonIdentityNamespace.validDatadir(datadir) || random == null
        || pid <= 0 || processStartEpochMillis < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    RiverDirectoryResult directoryResult = new RiverDirectoryResult();
    StatusCode status = filesystem.openDirectory(datadir, directoryResult);
    if (!status.isOk()) return status;
    RiverDirectory directory = directoryResult.directory();
    RiverFileResult lockFileResult = new RiverFileResult();
    status = directory.openFile(RiverDaemonIdentity.LOCK_FILE, RiverOpenMode.EXISTING, lockFileResult);
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
    byte[] lockBytes = new byte[MAX_RECORD_BYTES];
    RiverDaemonIdentityRecords.LockRecord owner = null;
    status = RiverDaemonIdentityFiles.readRecord(lockFile, lockBytes, RiverDaemonIdentity.LOCK_FILE);
    if (status.isOk()) owner = RiverDaemonIdentityRecords.parseLock(lockBytes);
    Arrays.fill(lockBytes, (byte) 0);
    if (status.isOk() && owner == null) status = StatusCode.CORRUPTION;
    if (status.isOk() && owner != null && !RiverDaemonIdentityNamespace.canonicalPath(datadir).equals(owner.datadir)) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk() && owner != null) status = RiverDaemonIdentityNamespace.proveOwnerAbsent(owner);
    if (!status.isOk()) {
      lockResult.lock().close();
      lockFile.close();
      RiverDaemonIdentityFiles.closeDirectory(directory, status);
      return status;
    }
    RiverFileResult fileResult = new RiverFileResult();
    status = directory.openFile(RiverDaemonIdentity.INSTANCE_FILE, RiverOpenMode.EXISTING, fileResult);
    if (!status.isOk()) {
      lockResult.lock().close();
      lockFile.close();
      RiverDaemonIdentityFiles.closeDirectory(directory, status);
      return status;
    }
    RiverFile file = fileResult.file();
    byte[] bytes = new byte[MAX_RECORD_BYTES];
    RiverDaemonIdentityRecords.InstanceRecord acceptedInstance = null;
    try {
      status = RiverDaemonIdentityFiles.readRecord(file, bytes, RiverDaemonIdentity.INSTANCE_FILE);
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
        RiverDaemonIdentityFiles.closeDirectory(directory, status);
      } else {
        result.complete(directory, lockResult.lock(), acceptedInstance.incarnation,
            acceptedInstance.generation);
        result.setLockFile(lockFile);
        result.prepareRestart(RiverDaemonIdentityNamespace.canonicalPath(datadir), owner, RiverDaemonIdentityNamespace.nonce(random), pid,
            processStartEpochMillis);
      }
    }
    return status;
  }



  static StatusCode handoffOwner(RiverDaemonIdentity.IdentityResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!result.needsOwnerHandoff()) return StatusCode.OK;
    if (result.lockFile() == null || result.datadir() == null || result.incarnation() == null
        || result.ownerNonce() == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = RiverDaemonIdentityNamespaceValidation.validateCurrentOwner(
        result.ownerPid(), result.ownerStart());
    if (!status.isOk()) return status;
    status = result.lockFile().truncate(0);
    if (status.isOk()) {
      status = RiverDaemonIdentityFiles.writeLockCanonical(result.lockFile(), result.datadir(),
          result.incarnation(), result.ownerPid(), result.ownerStart(), result.ownerNonce());
    }
    if (status.isOk()) result.completeOwnerHandoff();
    return status;
  }
}
