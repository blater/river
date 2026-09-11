package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

final class RiverDaemonRuntimePublication {
  private RiverDaemonRuntimePublication() {
  }

  static StatusCode publishRuntime(RiverDirectory runtimeRoot,
      RiverDaemonRuntimeRecords.Metadata metadata) {
    if (runtimeRoot == null || metadata == null || !metadata.valid()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    String stageName = ".runtime-" + metadata.owner.nonce + ".stage";
    return publish(runtimeRoot, stageName,
        RiverDaemonRuntimeStorage.runtimeName(metadata.datadir),
        RiverDaemonRuntimeCodec.runtimeBody(metadata));
  }

  static StatusCode publishReady(RiverDaemonFileSystem filesystem,
      RiverDaemonRuntimeRecords.Metadata metadata, Path readyPath, String certificate) {
    if (filesystem == null || metadata == null || !metadata.valid() || readyPath == null
        || certificate == null || !certificate.matches("[0-9a-f]{64}")
        || !metadata.readyFile.equals(readyPath.toString()) || "none".equals(metadata.readyFile)
        || !RiverDaemonRuntimeStorage.validDirectoryPath(metadata.runtimeRoot.toString())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    Path parentPath = readyPath.getParent();
    Path targetPath = readyPath.getFileName() == null ? null : readyPath;
    if (parentPath == null || targetPath == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    RiverDirectoryResult parentResult = new RiverDirectoryResult();
    StatusCode status = filesystem.openAncestor(parentPath, parentResult);
    if (!status.isOk()) return status;
    RiverDirectory parent = parentResult.directory();
    String targetName = targetPath.getFileName().toString();
    String stageName = "." + targetName + ".riverd-ready-" + metadata.owner.nonce + ".stage";
    status = publish(parent, stageName, targetName,
        RiverDaemonRuntimeCodec.readyBody(metadata, certificate));
    StatusCode closeStatus = parent.close();
    if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
      status = closeStatus;
    }
    return status;
  }

  private static StatusCode publish(RiverDirectory directory, String stageName,
      String targetName, String body) {
    RiverFileResult result = new RiverFileResult();
    StatusCode status = directory.openFile(stageName, RiverOpenMode.CREATE_NEW, result);
    if (!status.isOk()) return status;
    RiverFile stage = result.file();
    status = RiverDaemonRuntimeStorage.write(stage, body.getBytes(StandardCharsets.UTF_8));
    if (status.isOk()) {
      status = directory.publishExclusive(stage, stageName, targetName,
          new DirectoryOperationResult());
    }
    StatusCode closeStatus = stage.close();
    if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) {
      status = closeStatus;
    }
    if (status.isOk()) status = RiverDaemonRuntimeStorage.force(directory);
    return status;
  }

}
