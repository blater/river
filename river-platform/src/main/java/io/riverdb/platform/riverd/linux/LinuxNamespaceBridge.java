package io.riverdb.platform.riverd.linux;

import io.riverdb.platform.riverd.FileIdentity;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/** Linux namespace operations and statx/getdents64 record decoding. */
@SuppressWarnings("restricted")
final class LinuxNamespaceBridge {
  static final int AT_SYMLINK_NOFOLLOW = 0x100;
  static final int AT_EMPTY_PATH = 0x1000;
  static final int AT_REMOVEDIR = 0x200;
  static final int RENAME_NOREPLACE = 1;
  static final int ENOTSUP = 95;
  static final int S_IFMT = 0170000;
  static final int S_IFREG = 0100000;
  static final int S_IFDIR = 0040000;
  static final int STATX_BASIC_STATS = 0x7ff;
  static final int STATX_INO = 0x100;
  static final int STATX_SIZE = 0x200;
  static final int STATX_TYPE = 1;
  static final int STATX_MODE = 2;
  static final int STATX_NLINK = 4;
  static final int STATX_UID = 8;
  static final int STATX_MASK = STATX_BASIC_STATS;

  private LinuxNamespaceBridge() {
  }

  static int mkdirAt(int parent, String name, int mode) {
    try (Arena arena = Arena.ofConfined()) {
      LinuxNativeBindings.CallState state = LinuxNativeBindings.state();
      int result = (int) LinuxNativeBindings.MKDIRAT.invokeExact(
          state.segment, parent, arena.allocateFrom(name), mode);
      if (result != 0) state.capture();
      return result;
    } catch (Throwable failure) {
      throw abi("mkdirat", failure);
    }
  }

  static int unlinkAt(int parent, String name, boolean directory) {
    try (Arena arena = Arena.ofConfined()) {
      LinuxNativeBindings.CallState state = LinuxNativeBindings.state();
      int result = (int) LinuxNativeBindings.UNLINKAT.invokeExact(
          state.segment, parent, arena.allocateFrom(name), directory ? AT_REMOVEDIR : 0);
      if (result != 0) state.capture();
      return result;
    } catch (Throwable failure) {
      throw abi("unlinkat", failure);
    }
  }

  static Stat stat(int fd) {
    return statAt(fd, "");
  }

  static Stat statAt(int parent, String name) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment bytes = arena.allocate(256, 8);
      LinuxNativeBindings.CallState state = LinuxNativeBindings.state();
      int flags = name.isEmpty() ? AT_EMPTY_PATH : AT_SYMLINK_NOFOLLOW;
      long result = syscall(state, parent, arena.allocateFrom(name), flags, STATX_MASK, bytes);
      if (result < 0) {
        state.capture();
        return null;
      }
      int mask = bytes.get(ValueLayout.JAVA_INT, 0);
      int required = STATX_TYPE | STATX_MODE | STATX_NLINK | STATX_UID | STATX_INO | STATX_SIZE;
      if ((mask & required) != required) {
        state.errno = ENOTSUP;
        return null;
      }
      int mode = Short.toUnsignedInt(bytes.get(ValueLayout.JAVA_SHORT, 28));
      int links = bytes.get(ValueLayout.JAVA_INT, 16);
      int uid = bytes.get(ValueLayout.JAVA_INT, 20);
      long inode = bytes.get(ValueLayout.JAVA_LONG, 32);
      long size = bytes.get(ValueLayout.JAVA_LONG, 40);
      long device = (Integer.toUnsignedLong(bytes.get(ValueLayout.JAVA_INT, 136)) << 32)
          | Integer.toUnsignedLong(bytes.get(ValueLayout.JAVA_INT, 140));
      return new Stat(new FileIdentity(device, 0, inode), mode, links, uid, size);
    } catch (Throwable failure) {
      throw abi("statx", failure);
    }
  }

  static int effectiveUid() {
    try {
      return (int) LinuxNativeBindings.EUID.invokeExact();
    } catch (Throwable failure) {
      throw abi("geteuid", failure);
    }
  }

  static int rename(int sourceParent, String stage, int targetParent, String target, boolean exclusive) {
    try (Arena arena = Arena.ofConfined()) {
      LinuxNativeBindings.CallState state = LinuxNativeBindings.state();
      long result = (long) LinuxNativeBindings.SYSCALL_RENAME.invokeExact(
          state.segment, LinuxNativeBindings.SYS_RENAMEAT2, (long) sourceParent,
          arena.allocateFrom(stage), (long) targetParent, arena.allocateFrom(target),
          exclusive ? RENAME_NOREPLACE : 0L);
      return nativeResult(result, state);
    } catch (Throwable failure) {
      throw abi("renameat2", failure);
    }
  }

  static int readDirectory(int fd, MemorySegment buffer, int capacity) {
    try {
      LinuxNativeBindings.CallState state = LinuxNativeBindings.state();
      long result = (long) LinuxNativeBindings.SYSCALL4.invokeExact(
          state.segment, LinuxNativeBindings.SYS_GETDENTS64, (long) fd, buffer, (long) capacity);
      return nativeResult(result, state);
    } catch (Throwable failure) {
      throw abi("getdents64", failure);
    }
  }

  static String directoryEntryName(MemorySegment bytes, long offset, int length) {
    return new String(bytes.asSlice(offset, length).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
  }

  private static long syscall(LinuxNativeBindings.CallState state, int dirfd, MemorySegment path,
      long flags, long mask, MemorySegment buffer) throws Throwable {
    return (long) LinuxNativeBindings.SYSCALL_STATX.invokeExact(
        state.segment, LinuxNativeBindings.SYS_STATX, (long) dirfd, path, flags, mask, buffer);
  }

  private static int nativeResult(long result, LinuxNativeBindings.CallState state) {
    if (result < 0) state.capture();
    return result < Integer.MIN_VALUE ? -1 : (int) result;
  }

  private static AssertionError abi(String operation, Throwable failure) {
    return new AssertionError("Linux FFM ABI invocation failed: " + operation, failure);
  }

  static final class Stat {
    final FileIdentity identity;
    final int mode;
    final int links;
    final int uid;
    final long size;

    Stat(FileIdentity identity, int mode, int links, int uid, long size) {
      this.identity = identity;
      this.mode = mode;
      this.links = links;
      this.uid = uid;
      this.size = size;
    }

    boolean regularFile() { return (mode & S_IFMT) == S_IFREG; }
    boolean directory() { return (mode & S_IFMT) == S_IFDIR; }
  }
}
