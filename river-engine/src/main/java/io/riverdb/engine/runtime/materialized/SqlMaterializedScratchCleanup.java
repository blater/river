package io.riverdb.engine.runtime.materialized;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;

/** Best-effort, no-follow deletion and first-failure aggregation for retained scratch paths. */
final class SqlMaterializedScratchCleanup {
  private SqlMaterializedScratchCleanup() {}

  static void deleteTree(Path path, State state) {
    if (path == null) return;
    try {
      Files.walkFileTree(
          path,
          EnumSet.noneOf(FileVisitOption.class),
          Integer.MAX_VALUE,
          new DeletingVisitor(state));
    } catch (NoSuchFileException missing) {
      return;
    } catch (IOException | SecurityException failure) {
      state.record(StatusCode.IO_FAILURE, path);
      delete(path, state);
    }
  }

  static void deleteUnreported(Path path) {
    deleteTree(path, new State().begin(null));
  }

  static final class State {
    private StatusDetail detail;
    private StatusCode first = StatusCode.OK;
    private long failures;

    State begin(StatusDetail target) {
      detail = target;
      first = StatusCode.OK;
      failures = 0;
      if (detail != null) detail.reset();
      return this;
    }

    void record(StatusCode status, Path path) {
      if (status == null || status.isOk()) return;
      if (failures != Long.MAX_VALUE) failures++;
      if (first.isOk()) {
        first = status;
        if (detail != null) {
          detail.set(status).append("scratch cleanup failed: ").append(pathText(path));
        }
      } else if (detail != null) {
        detail.append("; also: ").append(pathText(path));
      }
    }

    StatusCode status() { return first; }
    long checkpoint() { return failures; }
    boolean unchanged(long checkpoint) {
      return failures != Long.MAX_VALUE && failures == checkpoint;
    }

    private CharSequence pathText(Path path) {
      return path == null ? "<runtime>" : path.toString();
    }
  }

  private static final class DeletingVisitor extends SimpleFileVisitor<Path> {
    private final State state;

    DeletingVisitor(State cleanupState) {
      state = cleanupState;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
      delete(file, state);
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException failure) {
      if (!(failure instanceof NoSuchFileException)) {
        state.record(StatusCode.IO_FAILURE, file);
      }
      delete(file, state);
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path directory, IOException failure) {
      if (failure != null) state.record(StatusCode.IO_FAILURE, directory);
      delete(directory, state);
      return FileVisitResult.CONTINUE;
    }
  }

  private static void delete(Path path, State state) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException | SecurityException failure) {
      state.record(StatusCode.IO_FAILURE, path);
    }
  }
}
