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

final class RiverDaemonRuntimeReadyAccess {
  private RiverDaemonRuntimeReadyAccess() {
  }

  static RiverDaemonRuntimeModel.ReadyTarget open(
      RiverDaemonFileSystem filesystem, RiverDaemonRuntimeModel.RuntimeRecord runtime,
      String datadir, Path runtimeRoot) {
    Path path = Path.of(runtime.readyFile);
    Path parentPath = path.getParent();
    if (parentPath == null) {
      return RiverDaemonRuntimeModel.ReadyTarget.failure(StatusCode.INVALID_EXTERNAL_INPUT);
    }
    RiverDirectoryResult parentResult = new RiverDirectoryResult();
    StatusCode status = filesystem.openAncestor(parentPath, parentResult);
    if (!status.isOk()) return RiverDaemonRuntimeModel.ReadyTarget.failure(status);
    RiverDirectory parent = parentResult.directory();
    String name = path.getFileName().toString();
    RiverFileResult fileResult = new RiverFileResult();
    status = parent.openFile(name, RiverOpenMode.EXISTING, fileResult);
    if (status == StatusCode.CONFLICT) {
      return new RiverDaemonRuntimeModel.ReadyTarget(parent, null, null, false, StatusCode.OK);
    }
    if (!status.isOk()) return closeParent(parent, status);
    RiverFile file = fileResult.file();
    FileIdentity objectIdentity = file.identity();
    RiverDaemonRuntimeModel.ReadResult readResult = RiverDaemonRuntimeStorage.read(file);
    StatusCode closeStatus = file.close();
    if (!readResult.status.isOk()) return closeParent(parent, readResult.status);
    if (!closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
      return closeParent(parent, closeStatus);
    }
    RiverDaemonRuntimeModel.ReadyRecord ready = RiverDaemonRuntimeCodec.parseReady(readResult.bytes);
    if (ready == null || objectIdentity == null
        || !ready.matches(runtime, datadir,
            RiverDaemonRuntimeStorage.runtimePath(runtimeRoot, datadir).toString())) {
      return closeParent(parent, StatusCode.CORRUPTION);
    }
    return new RiverDaemonRuntimeModel.ReadyTarget(parent, name, objectIdentity, true, StatusCode.OK);
  }

  private static RiverDaemonRuntimeModel.ReadyTarget closeParent(
      RiverDirectory parent, StatusCode primary) {
    StatusCode closeStatus = parent.close();
    if (primary.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
      return RiverDaemonRuntimeModel.ReadyTarget.failure(closeStatus);
    }
    return RiverDaemonRuntimeModel.ReadyTarget.failure(primary);
  }
}
