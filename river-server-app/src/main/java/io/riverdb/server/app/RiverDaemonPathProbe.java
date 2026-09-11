package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.FileIdentity;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Probes path targets through descriptor-relative capabilities and preserves probe status order. */
final class RiverDaemonPathProbe {
  private RiverDaemonPathProbe() { }

  static Target inspectDirectory(RiverDaemonFileSystem filesystem, Path path) {
    RiverDirectoryResult result = new RiverDirectoryResult();
    StatusCode status = filesystem.openDirectory(path, result);
    if (status == StatusCode.CONFLICT && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return inspectMissingDirectory(filesystem, path);
    }
    if (!status.isOk()) return Target.failure(status);
    RiverDirectory directory = result.directory();
    FileIdentity identity = directory.identity();
    StatusCode close = directory.close();
    if (!close.isOk() && close != StatusCode.CLOSED) return Target.failure(close);
    return identity == null ? Target.failure(StatusCode.CORRUPTION) : Target.present(identity);
  }

  private static Target inspectMissingDirectory(RiverDaemonFileSystem filesystem, Path path) {
    return inspectMissingTarget(filesystem, path);
  }

  private static Target inspectMissingTarget(RiverDaemonFileSystem filesystem, Path path) {
    Path ancestor = path;
    while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
      ancestor = ancestor.getParent();
    }
    if (ancestor == null) return Target.missing();
    RiverDirectoryResult parentResult = new RiverDirectoryResult();
    StatusCode status = filesystem.openAncestor(ancestor, parentResult);
    if (!status.isOk()) return Target.failure(status);
    RiverDirectory parent = parentResult.directory();
    FileIdentity identity = parent.identity();
    StatusCode close = parent.close();
    if (!close.isOk() && close != StatusCode.CLOSED) return Target.failure(close);
    return Target.missing(identity, path.toString());
  }

  static Target inspectFile(RiverDaemonFileSystem filesystem, Path path) {
    Path parentPath = path.getParent();
    Path namePath = path.getFileName();
    if (parentPath == null || namePath == null) return Target.failure(StatusCode.INVALID_EXTERNAL_INPUT);
    RiverDirectoryResult parentResult = new RiverDirectoryResult();
    StatusCode status = filesystem.openAncestor(parentPath, parentResult);
    if (status == StatusCode.CONFLICT && !Files.exists(parentPath, LinkOption.NOFOLLOW_LINKS)) {
      return inspectMissingTarget(filesystem, path);
    }
    if (!status.isOk()) return Target.failure(status);
    RiverDirectory parent = parentResult.directory();
    RiverDirectoryResult directoryProbe = new RiverDirectoryResult();
    StatusCode directoryStatus = parent.openDirectory(namePath.toString(), directoryProbe);
    if (directoryStatus.isOk()) {
      StatusCode closed = directoryProbe.directory().close();
      StatusCode parentClosed = parent.close();
      if (!closed.isOk() && closed != StatusCode.CLOSED) return Target.failure(closed);
      if (!parentClosed.isOk() && parentClosed != StatusCode.CLOSED) {
        return Target.failure(parentClosed);
      }
      return Target.failure(StatusCode.INVALID_EXTERNAL_INPUT);
    }
    RiverFileResult fileResult = new RiverFileResult();
    status = parent.openFile(namePath.toString(), RiverOpenMode.EXISTING, fileResult);
    if (status == StatusCode.CONFLICT && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      FileIdentity parentIdentity = parent.identity();
      StatusCode close = parent.close();
      return close.isOk() || close == StatusCode.CLOSED
          ? Target.missing(parentIdentity, namePath.toString()) : Target.failure(close);
    }
    if (!status.isOk()) {
      StatusCode close = parent.close();
      return Target.failure(status.isOk() ? close : status);
    }
    RiverFile file = fileResult.file();
    FileIdentity identity = file.identity();
    StatusCode fileClose = file.close();
    StatusCode parentClose = parent.close();
    if (!fileClose.isOk() && fileClose != StatusCode.CLOSED) return Target.failure(fileClose);
    if (!parentClose.isOk() && parentClose != StatusCode.CLOSED) return Target.failure(parentClose);
    return identity == null ? Target.failure(StatusCode.CORRUPTION) : Target.present(identity);
  }

  static final class Target {
    final StatusCode status;
    final FileIdentity identity;
    final FileIdentity parentIdentity;
    final String name;

    private Target(StatusCode status, FileIdentity identity,
        FileIdentity parentIdentity, String name) {
      this.status = status;
      this.identity = identity;
      this.parentIdentity = parentIdentity;
      this.name = name;
    }

    static Target present(FileIdentity identity) {
      return new Target(StatusCode.OK, identity, null, null);
    }

    static Target missing() {
      return new Target(StatusCode.OK, null, null, null);
    }

    static Target missing(FileIdentity parentIdentity, String name) {
      return new Target(StatusCode.OK, null, parentIdentity, name);
    }

    static Target failure(StatusCode status) {
      return new Target(status, null, null, null);
    }
  }

}
