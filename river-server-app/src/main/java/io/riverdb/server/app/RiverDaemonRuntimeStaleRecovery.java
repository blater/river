package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.riverd.FileIdentity;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import java.nio.file.Path;

final class RiverDaemonRuntimeStaleRecovery {
  private RiverDaemonRuntimeStaleRecovery() {
  }

  static StatusCode recover(Path datadir, RiverDaemonFileSystem filesystem,
      RiverDaemonIdentity.IdentityResult identity, Path runtimeRoot) {
    if (filesystem == null || identity == null || identity.directory() == null
        || identity.lock() == null || identity.priorOwner() == null || datadir == null
        || runtimeRoot == null || !RiverDaemonRuntimeStorage.validDirectoryPath(datadir.toString())
        || !RiverDaemonRuntimeStorage.validDirectoryPath(runtimeRoot.toString())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    String canonicalDatadir = datadir.toString();
    if (!canonicalDatadir.equals(identity.priorOwner().datadir)) return StatusCode.CORRUPTION;
    RiverDirectoryResult rootResult = new RiverDirectoryResult();
    StatusCode status = filesystem.openDirectory(runtimeRoot, rootResult);
    if (!status.isOk()) return status;
    RiverDirectory runtimeDirectory = rootResult.directory();
    status = recoverIn(runtimeDirectory, runtimeRoot, filesystem, canonicalDatadir, identity);
    StatusCode closeStatus = runtimeDirectory.close();
    if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
      return closeStatus;
    }
    return status;
  }

  private static StatusCode recoverIn(RiverDirectory runtimeRoot, Path runtimeRootPath,
      RiverDaemonFileSystem filesystem, String datadir,
      RiverDaemonIdentity.IdentityResult identity) {
    String runtimeName = RiverDaemonRuntimeStorage.runtimeName(datadir);
    RiverFileResult result = new RiverFileResult();
    StatusCode status = runtimeRoot.openFile(runtimeName, RiverOpenMode.EXISTING, result);
    if (status == StatusCode.CONFLICT) return StatusCode.OK;
    if (!status.isOk()) return status;
    RiverFile file = result.file();
    FileIdentity fileIdentity = file.identity();
    RiverDaemonRuntimeModel.ReadResult read = RiverDaemonRuntimeStorage.read(file);
    StatusCode closeStatus = file.close();
    if (!read.status.isOk()) status = read.status;
    if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
      status = closeStatus;
    }
    RiverDaemonRuntimeModel.RuntimeRecord runtime = status.isOk()
        ? RiverDaemonRuntimeCodec.parseRuntime(read.bytes) : null;
    if (status.isOk() && (runtime == null || fileIdentity == null
        || !runtime.matches(datadir, identity.incarnation(), identity.priorOwner()))) {
      status = StatusCode.CORRUPTION;
    }
    if (!status.isOk()) return status;
    RiverDaemonRuntimeModel.ReadyTarget ready = null;
    if (!"none".equals(runtime.readyFile)) {
      ready = RiverDaemonRuntimeReadyAccess.open(filesystem, runtime, datadir, runtimeRootPath);
      if (!ready.status.isOk()) return ready.status;
    }
    return RiverDaemonRuntimeCleanup.removeValidated(runtimeRoot, runtimeName, fileIdentity, ready);
  }
}
