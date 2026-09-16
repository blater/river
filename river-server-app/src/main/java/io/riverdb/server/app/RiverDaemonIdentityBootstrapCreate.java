package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.riverd.*;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;

final class RiverDaemonIdentityBootstrapCreate {
  private static final int MAX_RECORD_BYTES = RiverDaemonRecordEnvelope.MAX_RECORD_BYTES;
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
    if (status.isOk()) status = writeBootstrapRecords(directory, lockFile, datadir,
        incarnation, pid, processStartEpochMillis, nonce, replaceExistingLock);
    OpenedDirectories opened = status.isOk() ? createDirectories(directory, nonce)
        : new OpenedDirectories(null, null, null, status);
    status = opened.status;
    if (!status.isOk()) {
      RiverDaemonIdentityFiles.closeQuiet(opened.security);
      RiverDaemonIdentityFiles.closeQuiet(opened.database);
      RiverDaemonIdentityFiles.closeQuiet(opened.staging);
      lock.close();
      lockFile.close();
      RiverDaemonIdentityFiles.closeDirectory(directory, status);
      return status;
    }
    result.complete(directory, lock, incarnation, 1L);
    result.setBootstrap(nonce, opened.staging, opened.database, opened.security, lockFile,
        false, false, RiverDaemonIdentityNamespace.canonicalPath(datadir), pid, processStartEpochMillis, false,
        null, null);
    result.setPriorOwner(priorOwner);
    return StatusCode.OK;
  }

  private static StatusCode writeBootstrapRecords(RiverDirectory directory, RiverFile lockFile,
      Path datadir, DatabaseIncarnation incarnation, long pid, long processStart,
      String nonce, boolean replaceExistingLock) {
    StatusCode status = RiverDaemonIdentityFiles.writeLock(lockFile, datadir, incarnation, pid,
        processStart, nonce);
    if (status.isOk() && replaceExistingLock) {
      status = RiverDaemonIdentityFiles.forceDirectory(directory);
    }
    if (status.isOk()) {
      status = RiverDaemonIdentityFiles.writeBootstrap(
          directory, incarnation, pid, processStart, nonce);
    }
    return status;
  }

  private static OpenedDirectories createDirectories(RiverDirectory directory, String nonce) {
    RiverDirectoryResult stagingResult = new RiverDirectoryResult();
    StatusCode status = directory.createDirectory(
        ".riverd-bootstrap-" + nonce, stagingResult);
    RiverDirectory staging = stagingResult.directory();
    RiverDirectory database = null;
    RiverDirectory security = null;
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
    return new OpenedDirectories(staging, database, security, status);
  }

  private record OpenedDirectories(RiverDirectory staging, RiverDirectory database,
      RiverDirectory security, StatusCode status) { }
}
