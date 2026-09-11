package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.riverd.FileIdentity;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import java.nio.file.Path;

final class RiverDaemonRuntimeCleanup {
  private RiverDaemonRuntimeCleanup() {
  }

  static StatusCode cleanup(RiverDaemonFileSystem filesystem,
      RiverDaemonIdentity.IdentityResult identity, Path runtimeRoot,
      RiverDaemonRuntimeRecords.Metadata metadata) {
    if (filesystem == null || identity == null || identity.directory() == null
        || identity.lock() == null || metadata == null || !metadata.valid()
        || runtimeRoot == null || !RiverDaemonRuntimeStorage.validDirectoryPath(runtimeRoot.toString())
        || !runtimeRoot.equals(metadata.runtimeRoot)
        || !metadata.incarnation.equals(identity.incarnation())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    RiverDirectoryResult rootResult = new RiverDirectoryResult();
    StatusCode status = filesystem.openDirectory(runtimeRoot, rootResult);
    if (!status.isOk()) return status;
    RiverDirectory runtimeDirectory = rootResult.directory();
    status = cleanupIn(filesystem, runtimeDirectory, metadata);
    StatusCode closeStatus = runtimeDirectory.close();
    if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
      return closeStatus;
    }
    return status;
  }

  static StatusCode removeValidated(RiverDirectory runtimeRoot, String runtimeName,
      FileIdentity runtimeIdentity, RiverDaemonRuntimeModel.ReadyTarget ready) {
    StatusCode status = StatusCode.OK;
    if (ready != null && ready.present) {
      status = ready.parent.removeOwned(ready.name, ready.identity,
          new DirectoryOperationResult());
      if (status.isOk()) status = RiverDaemonRuntimeStorage.force(ready.parent);
    }
    if (status.isOk() && runtimeIdentity != null) {
      status = runtimeRoot.removeOwned(runtimeName, runtimeIdentity,
          new DirectoryOperationResult());
      if (status.isOk()) status = RiverDaemonRuntimeStorage.force(runtimeRoot);
    }
    if (ready != null) {
      StatusCode closeStatus = ready.parent.close();
      if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
        status = closeStatus;
      }
    }
    return status;
  }

  private static StatusCode cleanupIn(RiverDaemonFileSystem filesystem, RiverDirectory runtimeRoot,
      RiverDaemonRuntimeRecords.Metadata metadata) {
    RiverDaemonRuntimeModel.RuntimeRecord runtime = null;
    FileIdentity runtimeIdentity = null;
    RiverFileResult result = new RiverFileResult();
    StatusCode status = runtimeRoot.openFile(
        RiverDaemonRuntimeStorage.runtimeName(metadata.datadir), RiverOpenMode.EXISTING, result);
    if (status != StatusCode.CONFLICT) {
      if (!status.isOk()) return status;
      RiverFile file = result.file();
      runtimeIdentity = file.identity();
      RiverDaemonRuntimeModel.ReadResult read = RiverDaemonRuntimeStorage.read(file);
      StatusCode closeStatus = file.close();
      status = read.status;
      if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
        status = closeStatus;
      }
      runtime = status.isOk() ? RiverDaemonRuntimeCodec.parseRuntime(read.bytes) : null;
      if (status.isOk() && (runtime == null || runtimeIdentity == null
          || !runtime.matches(metadata.datadir, metadata.incarnation, metadata.owner)
          || !metadata.readyFile.equals(runtime.readyFile))) {
        status = StatusCode.CORRUPTION;
      }
      if (!status.isOk()) return status;
    } else {
      status = StatusCode.OK;
    }
    RiverDaemonRuntimeModel.RuntimeRecord expected = runtime == null
        ? RiverDaemonRuntimeStorage.expectedRuntime(metadata) : runtime;
    RiverDaemonRuntimeModel.ReadyTarget ready = null;
    if (!"none".equals(metadata.readyFile)) {
      ready = RiverDaemonRuntimeReadyAccess.open(filesystem, expected, metadata.datadir,
          metadata.runtimeRoot);
      if (!ready.status.isOk()) return ready.status;
    }
    return removeValidated(runtimeRoot, RiverDaemonRuntimeStorage.runtimeName(metadata.datadir),
        runtimeIdentity, ready);
  }
}
