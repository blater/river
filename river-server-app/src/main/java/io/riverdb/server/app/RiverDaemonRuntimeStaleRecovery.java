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
    RuntimeState state = readRuntime(runtimeRoot, runtimeName, datadir, identity);
    if (!state.status.isOk()) return state.status;
    if (state.runtime == null) return StatusCode.OK;
    RiverDaemonRuntimeModel.ReadyTarget ready = null;
    if (!"none".equals(state.runtime.readyFile)) {
      ready = RiverDaemonRuntimeReadyAccess.open(
          filesystem, state.runtime, datadir, runtimeRootPath);
      if (!ready.status.isOk()) return ready.status;
    }
    return RiverDaemonRuntimeCleanup.removeValidated(runtimeRoot, runtimeName,
        state.identity, ready);
  }

  private static RuntimeState readRuntime(RiverDirectory runtimeRoot, String runtimeName,
      String datadir, RiverDaemonIdentity.IdentityResult identity) {
    RiverFileResult result = new RiverFileResult();
    StatusCode status = runtimeRoot.openFile(runtimeName, RiverOpenMode.EXISTING, result);
    if (status == StatusCode.CONFLICT) return new RuntimeState(StatusCode.OK, null, null);
    if (!status.isOk()) return new RuntimeState(status, null, null);
    RiverFile file = result.file();
    FileIdentity fileIdentity = file.identity();
    RiverDaemonRuntimeModel.ReadResult read = RiverDaemonRuntimeStorage.read(file);
    StatusCode closeStatus = file.close();
    status = read.status;
    if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
      status = closeStatus;
    }
    RiverDaemonRuntimeModel.RuntimeRecord runtime = status.isOk()
        ? RiverDaemonRuntimeCodec.parseRuntime(read.bytes) : null;
    if (status.isOk() && (runtime == null || fileIdentity == null
        || !runtime.matches(datadir, identity.incarnation(), identity.priorOwner()))) {
      status = StatusCode.CORRUPTION;
    }
    return new RuntimeState(status, runtime, fileIdentity);
  }

  private record RuntimeState(StatusCode status,
      RiverDaemonRuntimeModel.RuntimeRecord runtime, FileIdentity identity) { }
}
