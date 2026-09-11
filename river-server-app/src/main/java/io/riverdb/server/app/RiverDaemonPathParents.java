package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;

/** Creates missing path components through descriptor-relative capabilities. */
final class RiverDaemonPathParents {
  private RiverDaemonPathParents() { }

  /** Validates and prepares the launch namespace before identity or database mutation. */
  static StatusCode prepare(
      RiverDaemonFileSystem filesystem, RiverDaemonPathSelection.Result paths) {
    StatusCode status = RiverDaemonPathInspection.verify(filesystem, paths);
    if (!status.isOk()) return status;
    status = ensureParents(filesystem, paths.datadir);
    if (!status.isOk()) return status;
    if (paths.ready != null && paths.ready.getParent() != null) {
      status = ensureParents(filesystem, paths.ready.getParent());
      if (!status.isOk()) return status;
    }
    RiverDirectoryResult runtimeRootResult = new RiverDirectoryResult();
    status = ensureDirectory(filesystem, paths.runtimeRoot, runtimeRootResult);
    if (!status.isOk()) return status;
    status = runtimeRootResult.directory().close();
    if (!status.isOk() && status != StatusCode.CLOSED) return status;
    // Parent creation changed the namespace; revalidate every prospective object before
    // identity/database mutation begins.
    return RiverDaemonPathInspection.verify(filesystem, paths);
  }

  /** Makes only missing parent components; an existing caller-owned ancestor need not be private. */
  static StatusCode ensureParents(RiverDaemonFileSystem filesystem, Path target) {
    Path parent = target.getParent();
    if (parent == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    RiverDirectoryResult result = new RiverDirectoryResult();
    StatusCode status = Files.exists(parent, LinkOption.NOFOLLOW_LINKS)
        ? filesystem.openAncestor(parent, result) : ensureDirectory(filesystem, parent, result);
    if (!status.isOk()) return status;
    status = result.directory().close();
    return status == StatusCode.CLOSED ? StatusCode.OK : status;
  }

  /** Existing ancestors remain unchanged; each newly created directory is private and forced. */
  static StatusCode ensureDirectory(
      RiverDaemonFileSystem filesystem, Path path, RiverDirectoryResult result) {
    ArrayDeque<String> missing = new ArrayDeque<>();
    Path ancestor = path;
    while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
      if (ancestor.getFileName() == null) return StatusCode.IO_FAILURE;
      missing.addFirst(ancestor.getFileName().toString());
      ancestor = ancestor.getParent();
    }
    if (missing.isEmpty()) return filesystem.openDirectory(path, result);
    RiverDirectoryResult opened = new RiverDirectoryResult();
    StatusCode status = filesystem.openAncestor(ancestor, opened);
    if (!status.isOk()) return status;
    RiverDirectory directory = opened.directory();
    for (String child : missing) {
      RiverDirectoryResult created = new RiverDirectoryResult();
      status = directory.createDirectory(child, created);
      if (status == StatusCode.CONFLICT) status = directory.openDirectory(child, created);
      if (status.isOk()) status = directory.force(new DirectoryOperationResult());
      StatusCode closed = directory.close();
      directory = created.directory();
      if (status.isOk() && closed != StatusCode.CLOSED) status = closed;
      if (!status.isOk()) {
        if (directory != null) directory.close();
        return status;
      }
    }
    result.set(directory);
    return StatusCode.OK;
  }

}
