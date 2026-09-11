package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.riverd.*;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;

final class RiverDaemonIdentityPrebootstrapRecovery {
  private static final int MAX_RECORD_BYTES = RiverDaemonRecordEnvelope.MAX_RECORD_BYTES;
  private RiverDaemonIdentityPrebootstrapRecovery() {}

  static StatusCode recoverPrebootstrapStage(Path datadir, RiverDirectory directory,
      RiverDaemonFileSystem filesystem, DatabaseIncarnation incarnation, SecureRandom random,
      long pid, long start, String stageName, RiverDaemonIdentity.IdentityResult result) {
    RiverFileResult lockResult = new RiverFileResult();
    StatusCode status = directory.openFile(RiverDaemonIdentity.LOCK_FILE,
        RiverOpenMode.EXISTING, lockResult);
    if (!status.isOk()) return close(directory, status);
    RiverFile lockFile = lockResult.file();
    RiverLockResult heldResult = new RiverLockResult();
    status = filesystem.acquireExclusive(lockFile, heldResult);
    if (!status.isOk()) {
      lockFile.close();
      return close(directory, status);
    }
    RiverLock held = heldResult.lock();
    Evidence evidence = readEvidence(datadir, directory, lockFile, stageName);
    if (!evidence.status.isOk()) return release(directory, lockFile, held, evidence.status);
    status = directory.removeOwned(stageName, evidence.stageIdentity, new DirectoryOperationResult());
    if (status.isOk()) status = RiverDaemonIdentityFiles.forceDirectory(directory);
    if (!status.isOk()) return release(directory, lockFile, held, status);
    return RiverDaemonIdentityBootstrapCreate.createBootstrap(datadir, directory, lockFile, held,
        incarnation, random, pid, start, result, evidence.priorOwner, true);
  }

  private static Evidence readEvidence(Path datadir, RiverDirectory directory,
      RiverFile lockFile, String stageName) {
    DirectoryListResult entries = new DirectoryListResult(16);
    StatusCode status = directory.list(entries);
    if (!status.isOk()) return new Evidence(status, null, null);
    if (entries.size() != 2
        || !RiverDaemonIdentityNamespace.hasEntry(entries, RiverDaemonIdentity.LOCK_FILE)
        || !RiverDaemonIdentityNamespace.hasEntry(entries, stageName)) {
      return new Evidence(StatusCode.CONFLICT, null, null);
    }
    Evidence owner = readPriorOwner(datadir, lockFile);
    if (!owner.status.isOk()) return owner;
    return readStage(datadir, directory, stageName, owner.priorOwner);
  }

  private static Evidence readPriorOwner(Path datadir, RiverFile lockFile) {
    byte[] bytes = new byte[MAX_RECORD_BYTES];
    StatusCode status = RiverDaemonIdentityFiles.readRecord(
        lockFile, bytes, RiverDaemonIdentity.LOCK_FILE);
    RiverDaemonIdentityRecords.LockRecord priorOwner = status.isOk()
        ? RiverDaemonIdentityRecords.parseLock(bytes) : null;
    Arrays.fill(bytes, (byte) 0);
    if (status == StatusCode.CORRUPTION) return new Evidence(StatusCode.OK, null, null);
    if (!status.isOk()) return new Evidence(status, null, null);
    if (priorOwner == null) return new Evidence(StatusCode.OK, null, null);
    if (!RiverDaemonIdentityNamespace.canonicalPath(datadir).equals(priorOwner.datadir)) {
      return new Evidence(StatusCode.CORRUPTION, null, null);
    }
    status = RiverDaemonIdentityNamespace.proveOwnerAbsent(priorOwner);
    return new Evidence(status, priorOwner, null);
  }

  private static Evidence readStage(Path datadir, RiverDirectory directory, String stageName,
      RiverDaemonIdentityRecords.LockRecord priorOwner) {
    RiverFileResult stageResult = new RiverFileResult();
    StatusCode status = directory.openFile(stageName, RiverOpenMode.EXISTING, stageResult);
    if (!status.isOk()) return new Evidence(status, priorOwner, null);
    RiverFile stage = stageResult.file();
    FileIdentity stageIdentity = stage.identity();
    byte[] bytes = new byte[MAX_RECORD_BYTES];
    status = RiverDaemonIdentityFiles.readRecord(stage, bytes, stageName);
    RiverDaemonIdentityRecords.BootstrapRecord bootstrap = status.isOk()
        ? RiverDaemonIdentityRecords.parseBootstrap(bytes) : null;
    Arrays.fill(bytes, (byte) 0);
    StatusCode closeStatus = stage.close();
    if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
      status = closeStatus;
    }
    if (!status.isOk()) return new Evidence(status, priorOwner, null);
    status = validateStage(datadir, stageName, priorOwner, bootstrap, stageIdentity);
    return new Evidence(status, priorOwner, status.isOk() ? stageIdentity : null);
  }

  private static StatusCode validateStage(Path datadir, String stageName,
      RiverDaemonIdentityRecords.LockRecord priorOwner,
      RiverDaemonIdentityRecords.BootstrapRecord bootstrap, FileIdentity stageIdentity) {
    if (stageIdentity == null || bootstrap == null
        || !stageName.equals(".bootstrap-" + bootstrap.nonce + ".stage")
        || !bootstrap.stagingName.equals(".riverd-bootstrap-" + bootstrap.nonce)
        || !bootstrap.instanceStageName.equals(".instance-" + bootstrap.nonce + ".stage")) {
      return StatusCode.CORRUPTION;
    }
    if (priorOwner != null
        && (priorOwner.high != bootstrap.high || priorOwner.low != bootstrap.low
            || priorOwner.pid != bootstrap.pid || priorOwner.start != bootstrap.start
            || !priorOwner.nonce.equals(bootstrap.nonce))) return StatusCode.CORRUPTION;
    return RiverDaemonIdentityNamespace.proveOwnerAbsent(
        RiverDaemonIdentityNamespace.bootstrapOwner(datadir, bootstrap));
  }

  private static StatusCode release(RiverDirectory directory, RiverFile lockFile,
      RiverLock lock, StatusCode status) {
    lock.close();
    lockFile.close();
    return close(directory, status);
  }

  private static StatusCode close(RiverDirectory directory, StatusCode status) {
    if (directory != null) directory.close();
    return status;
  }

  private static final class Evidence {
    private final StatusCode status;
    private final RiverDaemonIdentityRecords.LockRecord priorOwner;
    private final FileIdentity stageIdentity;
    private Evidence(StatusCode status, RiverDaemonIdentityRecords.LockRecord priorOwner,
        FileIdentity stageIdentity) {
      this.status = status; this.priorOwner = priorOwner; this.stageIdentity = stageIdentity;
    }
  }
}
