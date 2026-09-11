package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;

/** Resolves and validates launch paths before capability-based inspection or mutation. */
final class RiverDaemonPathSelection {
  private RiverDaemonPathSelection() { }

  static StatusCode resolve(Path requestedData, Path requestedReady, Path home, Result result) {
    result.datadir = null;
    result.runtimeRoot = null;
    result.ready = null;
    if (home == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    try {
      Path canonicalHome = home.toRealPath();
      Path datadir = canonical(requestedData == null
          ? canonicalHome.resolve(".river/default") : requestedData);
      Path runtimeRoot = canonical(canonicalHome.resolve(".river/run"));
      Path ready = requestedReady == null ? null : canonical(requestedReady);
      if (!valid(datadir, runtimeRoot, ready)) return StatusCode.INVALID_EXTERNAL_INPUT;
      result.datadir = datadir;
      result.runtimeRoot = runtimeRoot;
      result.ready = ready;
      return StatusCode.OK;
    } catch (IOException failure) {
      return StatusCode.IO_FAILURE;
    } catch (IllegalArgumentException | SecurityException failure) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
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

  static boolean valid(Path datadir, Path runtimeRoot, Path ready) {
    return datadir != null && runtimeRoot != null
        && recordPath(datadir) && recordPath(runtimeRoot)
        && (ready == null || recordPath(ready))
        && !overlaps(datadir, runtimeRoot)
        && (ready == null || !overlaps(ready, datadir) && !overlaps(ready, runtimeRoot));
  }

  static final class Result {
    Path datadir;
    Path runtimeRoot;
    Path ready;
  }
}
