package io.riverdb.platform.riverd.apfs;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/** Small macOS libc bridge. All returned descriptors are owned by the caller. */
/* File operations use direct typed FFM calls with bounded call-scoped pointer lifetimes. */
@SuppressWarnings("restricted")
final class DarwinFileBridge {
  static final int AT_FDCWD = -2;
  static final int O_RDONLY = 0;
  static final int O_RDWR = 2;
  static final int O_CREAT = 0x200;
  static final int O_EXCL = 0x800;
  static final int O_NOFOLLOW = 0x100;
  static final int O_DIRECTORY = 0x100000;
  static final int O_CLOEXEC = 0x1000000;
  static final int O_NOFOLLOW_ANY = 0x20000000;
  static final int EWOULDBLOCK = 35;

  private DarwinFileBridge() {
  }

  static int open(Path path, int flags, int mode) {
    try (Arena arena = Arena.ofConfined()) {
      DarwinNativeBindings.CallState state = DarwinNativeBindings.callState();
      int status = (int) DarwinNativeBindings.OPENAT.invokeExact(
          state.segment, AT_FDCWD, arena.allocateFrom(path.toString()), flags, mode);
      if (status < 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("openat", failure);
    }
  }

  static int openAt(int parent, String name, int flags, int mode) {
    try (Arena arena = Arena.ofConfined()) {
      DarwinNativeBindings.CallState state = DarwinNativeBindings.callState();
      int status = (int) DarwinNativeBindings.OPENAT.invokeExact(state.segment, parent, arena.allocateFrom(name), flags, mode);
      if (status < 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("openat", failure);
    }
  }

  static int close(int fd) {
    try {
      DarwinNativeBindings.CallState state = DarwinNativeBindings.callState();
      int status = (int) DarwinNativeBindings.CLOSE.invokeExact(state.segment, fd);
      if (status != 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("close", failure);
    }
  }

  static int duplicate(int fd) {
    try {
      DarwinNativeBindings.CallState state = DarwinNativeBindings.callState();
      int status = (int) DarwinNativeBindings.DUP.invokeExact(state.segment, fd);
      if (status < 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("dup", failure);
    }
  }

  static int read(int fd, ByteBuffer target, long position) {
    int bytes = target.remaining();
    if (bytes == 0) return 0;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment nativeBuffer = arena.allocate(bytes, 1);
      DarwinNativeBindings.CallState state = DarwinNativeBindings.callState();
      long count = (long) DarwinNativeBindings.READ.invokeExact(
          state.segment, fd, nativeBuffer, (long) bytes, position);
      if (count < 0) state.captureErrno();
      if (count > 0) {
        ByteBuffer copy = nativeBuffer.asByteBuffer();
        copy.limit((int) count);
        target.put(copy);
      }
      return (int) count;
    } catch (Throwable failure) {
      throw bridgeFailure("pread", failure);
    }
  }

  static int write(int fd, ByteBuffer source, long position) {
    int bytes = source.remaining();
    if (bytes == 0) return 0;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment nativeBuffer = arena.allocate(bytes, 1);
      ByteBuffer copy = source.duplicate();
      nativeBuffer.asByteBuffer().put(copy);
      DarwinNativeBindings.CallState state = DarwinNativeBindings.callState();
      long count = (long) DarwinNativeBindings.WRITE.invokeExact(
          state.segment, fd, nativeBuffer, (long) bytes, position);
      if (count < 0) state.captureErrno();
      return (int) count;
    } catch (Throwable failure) {
      throw bridgeFailure("pwrite", failure);
    }
  }

  static int truncate(int fd, long size) {
    try {
      DarwinNativeBindings.CallState state = DarwinNativeBindings.callState();
      int status = (int) DarwinNativeBindings.TRUNCATE.invokeExact(state.segment, fd, size);
      if (status != 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("ftruncate", failure);
    }
  }

  static int forceFile(int fd) {
    try {
      DarwinNativeBindings.CallState state = DarwinNativeBindings.callState();
      int status = (int) DarwinNativeBindings.FULLFSYNC.invokeExact(
          state.segment, fd, DarwinNativeBindings.F_FULLFSYNC);
      if (status != 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("fcntl(F_FULLFSYNC)", failure);
    }
  }

  static int lock(int fd) {
    try {
      DarwinNativeBindings.CallState state = DarwinNativeBindings.callState();
      int status = (int) DarwinNativeBindings.FLOCK.invokeExact(state.segment, fd, 2 | 4);
      if (status != 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("flock", failure);
    }
  }

  static int unlock(int fd) {
    try {
      DarwinNativeBindings.CallState state = DarwinNativeBindings.callState();
      int status = (int) DarwinNativeBindings.FLOCK.invokeExact(state.segment, fd, 8);
      if (status != 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("flock", failure);
    }
  }

  private static AssertionError bridgeFailure(String operation, Throwable failure) {
    return new AssertionError("Darwin FFM ABI invocation failed: " + operation, failure);
  }
}
