package io.riverdb.platform.riverd.linux;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/** Linux file descriptor and data operations. */
@SuppressWarnings("restricted")
final class LinuxFileBridge {
  static final int AT_FDCWD = -100;
  static final int O_RDONLY = 0;
  static final int O_RDWR = 2;
  static final int O_CREAT = 0x40;
  static final int O_EXCL = 0x80;
  // Linux keeps these flags stable per ABI, but their values differ by arch.
  static final int O_NOFOLLOW = LinuxNativeBindings.ARCH == 1 ? 0x20000 : 0x8000;
  static final int O_DIRECTORY = LinuxNativeBindings.ARCH == 1 ? 0x10000 : 0x4000;
  static final int O_CLOEXEC = 0x80000;
  static final int LOCK_EX = 2;
  static final int LOCK_NB = 4;
  static final int LOCK_UN = 8;
  static final int ENOENT = 2;
  static final int EEXIST = 17;
  static final int EACCES = 13;
  static final int ENOTDIR = 20;
  static final int ENOTEMPTY = 39;
  static final int ENOSPC = 28;
  static final int ELOOP = 40;
  static final int EWOULDBLOCK = 11;
  static final int EINVAL = 22;
  static final int ENOSYS = 38;

  private LinuxFileBridge() {
  }

  static int open(Path path, int flags, int mode) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment how = openHow(arena, flags, mode, 4);
      LinuxNativeBindings.CallState state = LinuxNativeBindings.state();
      long result = syscall(state, LinuxNativeBindings.SYS_OPENAT2, AT_FDCWD, arena.allocateFrom(path.toString()), how, 24);
      return nativeResult(result, state);
    } catch (Throwable failure) {
      throw abi("openat2", failure);
    }
  }

  static int openAt(int parent, String name, int flags, int mode) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment how = openHow(arena, flags, mode, 4);
      LinuxNativeBindings.CallState state = LinuxNativeBindings.state();
      long result = syscall(state, LinuxNativeBindings.SYS_OPENAT2, parent, arena.allocateFrom(name), how, 24);
      return nativeResult(result, state);
    } catch (Throwable failure) {
      throw abi("openat2", failure);
    }
  }

  static int close(int fd) {
    try {
      LinuxNativeBindings.CallState state = LinuxNativeBindings.state();
      int result = (int) LinuxNativeBindings.CLOSE.invokeExact(state.segment, fd);
      if (result != 0) state.capture();
      return result;
    } catch (Throwable failure) {
      throw abi("close", failure);
    }
  }

  static int duplicate(int fd) {
    try {
      LinuxNativeBindings.CallState state = LinuxNativeBindings.state();
      int result = (int) LinuxNativeBindings.DUP.invokeExact(state.segment, fd);
      if (result < 0) state.capture();
      return result;
    } catch (Throwable failure) {
      throw abi("dup", failure);
    }
  }

  static int read(int fd, ByteBuffer target, long position) {
    int bytes = target.remaining();
    if (bytes == 0) return 0;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment nativeBuffer = arena.allocate(bytes, 1);
      LinuxNativeBindings.CallState state = LinuxNativeBindings.state();
      long count = (long) LinuxNativeBindings.READ.invokeExact(state.segment, fd, nativeBuffer, (long) bytes, position);
      if (count < 0) state.capture();
      if (count > 0) {
        ByteBuffer copy = nativeBuffer.asByteBuffer();
        copy.limit((int) count);
        target.put(copy);
      }
      return (int) count;
    } catch (Throwable failure) {
      throw abi("pread64", failure);
    }
  }

  static int write(int fd, ByteBuffer source, long position) {
    int bytes = source.remaining();
    if (bytes == 0) return 0;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment nativeBuffer = arena.allocate(bytes, 1);
      nativeBuffer.asByteBuffer().put(source.duplicate());
      LinuxNativeBindings.CallState state = LinuxNativeBindings.state();
      long count = (long) LinuxNativeBindings.WRITE.invokeExact(state.segment, fd, nativeBuffer, (long) bytes, position);
      if (count < 0) state.capture();
      return (int) count;
    } catch (Throwable failure) {
      throw abi("pwrite64", failure);
    }
  }

  static int truncate(int fd, long size) {
    try {
      LinuxNativeBindings.CallState state = LinuxNativeBindings.state();
      int result = (int) LinuxNativeBindings.TRUNCATE.invokeExact(state.segment, fd, size);
      if (result != 0) state.capture();
      return result;
    } catch (Throwable failure) {
      throw abi("ftruncate", failure);
    }
  }

  static int force(int fd) {
    try {
      LinuxNativeBindings.CallState state = LinuxNativeBindings.state();
      int result = (int) LinuxNativeBindings.FSYNC.invokeExact(state.segment, fd);
      if (result != 0) state.capture();
      return result;
    } catch (Throwable failure) {
      throw abi("fsync", failure);
    }
  }

  static int lock(int fd) {
    try {
      LinuxNativeBindings.CallState state = LinuxNativeBindings.state();
      int result = (int) LinuxNativeBindings.FLOCK.invokeExact(state.segment, fd, LOCK_EX | LOCK_NB);
      if (result != 0) state.capture();
      return result;
    } catch (Throwable failure) {
      throw abi("flock", failure);
    }
  }

  static int unlock(int fd) {
    try {
      LinuxNativeBindings.CallState state = LinuxNativeBindings.state();
      int result = (int) LinuxNativeBindings.FLOCK.invokeExact(state.segment, fd, LOCK_UN);
      if (result != 0) state.capture();
      return result;
    } catch (Throwable failure) {
      throw abi("flock", failure);
    }
  }

  private static MemorySegment openHow(Arena arena, int flags, int mode, long resolve) {
    MemorySegment how = arena.allocate(24, 8);
    how.set(ValueLayout.JAVA_LONG, 0, Integer.toUnsignedLong(flags));
    how.set(ValueLayout.JAVA_LONG, 8, Integer.toUnsignedLong(mode));
    how.set(ValueLayout.JAVA_LONG, 16, resolve);
    return how;
  }

  private static long syscall(LinuxNativeBindings.CallState state, long number, long dirfd, MemorySegment path,
      MemorySegment how, long size) throws Throwable {
    return (long) LinuxNativeBindings.SYSCALL5.invokeExact(state.segment, number, dirfd, path, how, size);
  }

  private static int nativeResult(long result, LinuxNativeBindings.CallState state) {
    if (result < 0) state.capture();
    return result < Integer.MIN_VALUE ? -1 : (int) result;
  }

  private static AssertionError abi(String operation, Throwable failure) {
    return new AssertionError("Linux FFM ABI invocation failed: " + operation, failure);
  }

}
