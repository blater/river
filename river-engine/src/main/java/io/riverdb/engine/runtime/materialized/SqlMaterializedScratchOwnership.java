package io.riverdb.engine.runtime.materialized;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Exclusive database-open ownership of one deterministic scratch namespace. */
final class SqlMaterializedScratchOwnership {
  private static final String LOCK_NAME = ".owner.lock";

  private final Path path;
  private FileChannel channel;
  private FileLock lock;
  private boolean closed;

  private SqlMaterializedScratchOwnership(Path lockPath, FileChannel lockChannel, FileLock held) {
    path = lockPath;
    channel = lockChannel;
    lock = held;
  }

  static StatusCode acquire(Path namespace, Result target, StatusDetail detail) {
    target.reset();
    Path path = namespace.resolve(LOCK_NAME);
    FileChannel channel = null;
    FileLock lock = null;
    try {
      channel = FileChannel.open(
          path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
      lock = channel.tryLock();
      if (lock == null) {
        closeUnreported(channel);
        return fail(detail, StatusCode.CONFLICT, "materialized scratch namespace is already owned");
      }
      target.set(new SqlMaterializedScratchOwnership(path, channel, lock));
      return StatusCode.OK;
    } catch (OverlappingFileLockException conflict) {
      closeUnreported(channel);
      return fail(detail, StatusCode.CONFLICT, "materialized scratch namespace is already owned");
    } catch (IOException | SecurityException failure) {
      releaseUnreported(lock, channel);
      return fail(detail, StatusCode.IO_FAILURE, "cannot acquire materialized scratch ownership");
    } catch (OutOfMemoryError failure) {
      releaseUnreported(lock, channel);
      return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "cannot retain materialized scratch ownership");
    }
  }

  void close(SqlMaterializedScratchCleanup.State state) {
    if (closed) return;
    try {
      channel.close();
    } catch (IOException | SecurityException failure) {
      if (channel.isOpen()) {
        state.record(StatusCode.IO_FAILURE, path);
        return;
      }
    }
    lock = null;
    channel = null;
    closed = true;
  }

  private static void releaseUnreported(FileLock lock, FileChannel channel) {
    if (lock != null) {
      try {
        lock.release();
      } catch (IOException | SecurityException ignored) {
        // The channel close below is the final ownership release attempt.
      }
    }
    closeUnreported(channel);
  }

  private static void closeUnreported(FileChannel channel) {
    if (channel == null) return;
    try {
      channel.close();
    } catch (IOException | SecurityException ignored) {
      // Creation already has a primary status and no runtime can retain this channel.
    }
  }

  private static StatusCode fail(StatusDetail detail, StatusCode code, String message) {
    if (detail != null) detail.set(code).append(message);
    return code;
  }

  static final class Result {
    private SqlMaterializedScratchOwnership ownership;

    void reset() { ownership = null; }
    void set(SqlMaterializedScratchOwnership value) { ownership = value; }
    SqlMaterializedScratchOwnership ownership() { return ownership; }
  }
}
