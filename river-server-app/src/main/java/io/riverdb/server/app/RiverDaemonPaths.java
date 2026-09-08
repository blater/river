package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.FileIdentity;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;

/** Resolves launch paths before mutation and creates missing parents through directory capabilities. */
final class RiverDaemonPaths {
  private RiverDaemonPaths() { }

  static StatusCode resolve(Path requestedData, Path requestedReady, Path home, Result result) {
    result.datadir = null;
    result.registry = null;
    result.ready = null;
    if (home == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    try {
      Path canonicalHome = home.toRealPath();
      Path datadir = canonical(requestedData == null
          ? canonicalHome.resolve(".river/default") : requestedData);
      Path registry = canonical(canonicalHome.resolve(".river/run/instances"));
      Path ready = requestedReady == null ? null : canonical(requestedReady);
      if (!recordPath(datadir) || !recordPath(registry)
          || ready != null && !recordPath(ready)
          || overlaps(datadir, registry)
          || ready != null && (overlaps(ready, datadir) || overlaps(ready, registry))) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      result.datadir = datadir;
      result.registry = registry;
      result.ready = ready;
      return StatusCode.OK;
    } catch (IOException failure) {
      return StatusCode.IO_FAILURE;
    } catch (IllegalArgumentException | SecurityException failure) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
  }

  /**
   * Revalidates existing prospective objects through the platform descriptor boundary. Call this
   * immediately before creating missing parents and again before publishing runtime records.
   */
  static StatusCode verify(RiverDaemonFileSystem filesystem, Result paths) {
    if (filesystem == null || paths == null || paths.datadir == null || paths.registry == null
        || !recordPath(paths.datadir) || !recordPath(paths.registry)
        || paths.ready != null && !recordPath(paths.ready)
        || overlaps(paths.datadir, paths.registry)
        || paths.ready != null
        && (overlaps(paths.ready, paths.datadir) || overlaps(paths.ready, paths.registry))) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    try {
      Target data = inspectDirectory(filesystem, paths.datadir);
      if (!data.status.isOk()) return data.status;
      Target[] fixedChildren = new Target[2];
      String[] fixedNames = {
          RiverDaemonIdentity.DATABASE_NAME,
          RiverDaemonIdentity.SECURITY_NAME};
      for (int index = 0; index < fixedNames.length; index++) {
        fixedChildren[index] = inspectDirectory(
            filesystem, paths.datadir.resolve(fixedNames[index]));
        if (!fixedChildren[index].status.isOk()) return fixedChildren[index].status;
      }
      Target registry = inspectDirectory(filesystem, paths.registry);
      if (!registry.status.isOk()) return registry.status;
      Target ready = paths.ready == null ? Target.missing() : inspectFile(filesystem, paths.ready);
      if (!ready.status.isOk()) return ready.status;
      if (collides(data, registry) || collides(data, ready) || collides(registry, ready)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      for (int index = 0; index < fixedChildren.length; index++) {
        FileIdentity identity = fixedChildren[index].identity;
        if (same(identity, data.identity) || same(identity, registry.identity)
            || same(identity, ready.identity)
            || same(identity, data.parentIdentity)
            || same(identity, registry.parentIdentity)
            || same(identity, ready.parentIdentity)) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        for (int other = index + 1; other < fixedChildren.length; other++) {
          if (same(identity, fixedChildren[other].identity)) {
            return StatusCode.INVALID_EXTERNAL_INPUT;
          }
        }
      }
      return StatusCode.OK;
    } catch (SecurityException | IllegalArgumentException failure) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
  }

  private static Target inspectDirectory(RiverDaemonFileSystem filesystem, Path path) {
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

  private static Target inspectFile(RiverDaemonFileSystem filesystem, Path path) {
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

  private static boolean collides(Target first, Target second) {
    return same(first.identity, second.identity)
        || same(first.parentIdentity, second.identity)
        || same(second.parentIdentity, first.identity)
        || (same(first.parentIdentity, second.parentIdentity)
            && first.name != null && first.name.equals(second.name));
  }

  private static boolean same(FileIdentity first, FileIdentity second) {
    return first != null && second != null && first.equals(second);
  }

  private static final class Target {
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

  private static Path canonical(Path path) throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    ArrayDeque<String> missing = new ArrayDeque<>();
    Path ancestor = absolute;
    while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
      if (ancestor.getFileName() == null) throw new IOException("missing root");
      missing.addFirst(ancestor.getFileName().toString());
      ancestor = ancestor.getParent();
    }
    // The adapter checks every existing component again before obtaining a capability.
    // Resolving the real ancestor makes containment checks include filesystem aliases.
    Path resolved = ancestor.toRealPath();
    for (String child : missing) resolved = resolved.resolve(child);
    return resolved;
  }

  private static boolean recordPath(Path path) {
    if (!path.isAbsolute() || path.getParent() == null) return false;
    String value = path.toString();
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (Character.isISOControl(character) || character == '*' || character == '?') return false;
    }
    return true;
  }

  private static boolean overlaps(Path first, Path second) {
    return first.startsWith(second) || second.startsWith(first);
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

  static final class Result {
    Path datadir;
    Path registry;
    Path ready;
  }
}
