package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.riverd.FileIdentity;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import java.nio.charset.StandardCharsets;

final class RiverDaemonStopRecords {
  private RiverDaemonStopRecords() {
  }

  static StatusCode recoverStale(RiverDaemonFileSystem filesystem,
      RiverDaemonIdentity.IdentityResult identity) {
    if (filesystem == null || identity == null || identity.directory() == null
        || identity.lock() == null || identity.priorOwner() == null) return StatusCode.OK;
    RiverDaemonStopDirectory.Scan scan = RiverDaemonStopDirectory.scan(identity.directory(), true);
    if (!scan.status.isOk()) return scan.status;
    RiverDaemonIdentityRecords.LockRecord old = identity.priorOwner();
    boolean removed = false;
    for (int index = 0; index < scan.entryCount; index++) {
      RiverDaemonStopDirectory.ControlEntry entry = scan.entries[index];
      if (entry.record.high != old.high || entry.record.low != old.low
          || !entry.record.ownerNonce.equals(old.nonce)) continue;
      StatusCode status = identity.directory().removeOwned(entry.name, entry.identity,
          new DirectoryOperationResult());
      if (!status.isOk()) return status;
      removed = true;
    }
    return removed ? RiverDaemonRuntimeStorage.force(identity.directory()) : StatusCode.OK;
  }

  static StatusCode removeIfOwned(RiverDirectory directory, String name, FileIdentity identity) {
    if (directory == null || identity == null) return StatusCode.OK;
    StatusCode status = directory.removeOwned(name, identity, new DirectoryOperationResult());
    if (status == StatusCode.CONFLICT) return StatusCode.OK;
    if (status.isOk()) status = RiverDaemonRuntimeStorage.force(directory);
    return status;
  }

  static StatusCode publish(RiverDirectory directory, String stageName) {
    RiverFileResult result = new RiverFileResult();
    StatusCode status = directory.openFile(stageName, RiverOpenMode.EXISTING, result);
    if (!status.isOk()) return status;
    RiverFile stage = result.file();
    status = directory.publishExclusive(stage, stageName, RiverDaemonStopRequest.REQUEST_NAME,
        new DirectoryOperationResult());
    StatusCode closeStatus = stage.close();
    if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) status = closeStatus;
    if (status.isOk()) status = RiverDaemonRuntimeStorage.force(directory);
    return status;
  }

  static StatusCode publishAccepted(RiverDirectory directory, String requestName,
      String acceptedName) {
    RiverFileResult result = new RiverFileResult();
    StatusCode status = directory.openFile(requestName, RiverOpenMode.EXISTING, result);
    if (!status.isOk()) return status;
    RiverFile request = result.file();
    status = directory.publishExclusive(request, requestName, acceptedName,
        new DirectoryOperationResult());
    StatusCode closeStatus = request.close();
    if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) status = closeStatus;
    return status;
  }

  static Stage createStage(RiverDaemonTarget target, String name) {
    RiverFileResult result = new RiverFileResult();
    StatusCode status = target.directory.openFile(name, RiverOpenMode.CREATE_NEW, result);
    if (!status.isOk()) return Stage.failure(status);
    RiverFile file = result.file();
    String nonce = name.substring(RiverDaemonStopRequest.STAGE_PREFIX.length(),
        name.length() - RiverDaemonStopRequest.STAGE_SUFFIX.length());
    String body = RiverDaemonStopRequest.encode(target.owner.high, target.owner.low,
        target.owner.nonce, nonce, target.runtimeChecksum, System.currentTimeMillis());
    status = RiverDaemonRuntimeStorage.write(file, body.getBytes(StandardCharsets.UTF_8));
    FileIdentity identity = file.identity();
    StatusCode closeStatus = file.close();
    if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) status = closeStatus;
    if (status.isOk()) return new Stage(StatusCode.OK, identity);
    StatusCode cleanup = removeIfOwned(target.directory, name, identity);
    return Stage.failure(cleanup.isOk() ? status : cleanup);
  }

  record Stage(StatusCode status, FileIdentity identity) {
    static Stage failure(StatusCode status) { return new Stage(status, null); }
  }

}
