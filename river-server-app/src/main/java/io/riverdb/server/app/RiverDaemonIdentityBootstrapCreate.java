package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.riverd.*;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;

final class RiverDaemonIdentityBootstrapCreate {
  private static final int MAX_RECORD_BYTES = RiverDaemonIdentityRecords.MAX_RECORD_BYTES;
  private RiverDaemonIdentityBootstrapCreate() {}
  static StatusCode createBootstrap(
      Path datadir,
      RiverDirectory directory,
      RiverFile lockFile,
      RiverLock lock,
      DatabaseIncarnation incarnation,
      SecureRandom random,
      long pid,
      long processStartEpochMillis,
      RiverDaemonIdentity.IdentityResult result,
      RiverDaemonIdentityRecords.LockRecord priorOwner,
      boolean replaceExistingLock) {
    StatusCode status = StatusCode.OK;
    if (replaceExistingLock) status = lockFile.truncate(0);
    String nonce = RiverDaemonIdentityNamespace.nonce(random);
    if (status.isOk()) status = RiverDaemonIdentityFiles.writeLock(lockFile, datadir, incarnation, pid,
        processStartEpochMillis, nonce);
    if (status.isOk() && replaceExistingLock) status = RiverDaemonIdentityFiles.forceDirectory(directory);
    if (status.isOk()) {
      status = RiverDaemonIdentityFiles.writeBootstrap(directory, incarnation, pid, processStartEpochMillis, nonce);
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
        status = staging.createDirectory(RiverDaemonIdentity.DATABASE_NAME, child);
        database = child.directory();
      }
      if (status.isOk()) {
        RiverDirectoryResult child = new RiverDirectoryResult();
        status = staging.createDirectory(RiverDaemonIdentity.SECURITY_NAME, child);
        security = child.directory();
      }
    }
    if (!status.isOk()) {
      RiverDaemonIdentityFiles.closeQuiet(security);
      RiverDaemonIdentityFiles.closeQuiet(database);
      RiverDaemonIdentityFiles.closeQuiet(staging);
      lock.close();
      lockFile.close();
      RiverDaemonIdentityFiles.closeDirectory(directory, status);
      return status;
    }
    result.complete(directory, lock, incarnation, 1L);
    result.setBootstrap(nonce, staging, database, security, lockFile,
        false, false, RiverDaemonIdentityNamespace.canonicalPath(datadir), pid, processStartEpochMillis, false,
        null, null);
    result.setPriorOwner(priorOwner);
    return StatusCode.OK;
  }
}
