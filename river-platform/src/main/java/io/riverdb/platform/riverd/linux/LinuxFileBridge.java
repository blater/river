package io.riverdb.platform.riverd.linux;

import io.riverdb.platform.riverd.FileIdentity;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Linux syscall/libc bridge. Linux execution is confined to this adapter. */
@SuppressWarnings("restricted")
final class LinuxFileBridge {
  private static final int ARCH = architecture();
  static final int AT_FDCWD = -100;
  static final int AT_SYMLINK_NOFOLLOW = 0x100;
  static final int AT_EMPTY_PATH = 0x1000;
  static final int AT_REMOVEDIR = 0x200;
  static final int O_RDONLY = 0;
  static final int O_RDWR = 2;
  static final int O_CREAT = 0x40;
  static final int O_EXCL = 0x80;
  // Linux keeps these flags stable per ABI, but their values differ by arch.
  static final int O_NOFOLLOW = ARCH == 1 ? 0x20000 : 0x8000;
  static final int O_DIRECTORY = ARCH == 1 ? 0x10000 : 0x4000;
  static final int O_CLOEXEC = 0x80000;
  static final int RENAME_NOREPLACE = 1;
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

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LIBC =
      SymbolLookup.libraryLookup("libc.so.6", Arena.global());
  private static final long ERRNO_OFFSET = Linker.Option.captureStateLayout()
      .byteOffset(MemoryLayout.PathElement.groupElement("errno"));
  private static final ThreadLocal<CallState> CALL_STATE = ThreadLocal.withInitial(CallState::new);
  private static final long SYS_OPENAT2 = 437;
  private static final long SYS_RENAMEAT2 = ARCH == 1 ? 316 : 276;
  private static final long SYS_STATX = ARCH == 1 ? 332 : 291;
  private static final long SYS_GETDENTS64 = ARCH == 1 ? 217 : 61;

  private static final MethodHandle SYSCALL4 = function("syscall", FunctionDescriptor.of(
      ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
      ValueLayout.ADDRESS, ValueLayout.JAVA_LONG), 1);
  private static final MethodHandle SYSCALL5 = function("syscall", FunctionDescriptor.of(
      ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG), 1);
  private static final MethodHandle SYSCALL_STATX = function("syscall", FunctionDescriptor.of(
      ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
      ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS), 1);
  private static final MethodHandle SYSCALL_RENAME = function("syscall", FunctionDescriptor.of(
      ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
      ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG), 1);
  private static final MethodHandle MKDIRAT = function("mkdirat", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
  private static final MethodHandle UNLINKAT = function("unlinkat", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
  private static final MethodHandle CLOSE = function("close", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
  private static final MethodHandle DUP = function("dup", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
  private static final MethodHandle READ = function("pread64", FunctionDescriptor.of(
      ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
      ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
  private static final MethodHandle WRITE = function("pwrite64", FunctionDescriptor.of(
      ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
      ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
  private static final MethodHandle TRUNCATE = function("ftruncate", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
  private static final MethodHandle FSYNC = function("fsync", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
  private static final MethodHandle FLOCK = function("flock", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
  private static final MethodHandle EUID = functionNoCapture("geteuid", FunctionDescriptor.of(
      ValueLayout.JAVA_INT));
  private LinuxFileBridge() {
  }

  static boolean supportedArchitecture() {
    return ARCH != 0;
  }

  static int open(Path path, int flags, int mode) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment how = openHow(arena, flags, mode, 4);
      CallState state = state();
      long result = syscall(state, SYS_OPENAT2, AT_FDCWD, arena.allocateFrom(path.toString()), how, 24);
      return nativeResult(result, state);
    } catch (Throwable failure) {
      throw abi("openat2", failure);
    }
  }

  static int openAt(int parent, String name, int flags, int mode) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment how = openHow(arena, flags, mode, 4);
      CallState state = state();
      long result = syscall(state, SYS_OPENAT2, parent, arena.allocateFrom(name), how, 24);
      return nativeResult(result, state);
    } catch (Throwable failure) {
      throw abi("openat2", failure);
    }
  }

  static int mkdirAt(int parent, String name, int mode) {
    try (Arena arena = Arena.ofConfined()) {
      CallState state = state();
      int result = (int) MKDIRAT.invokeExact(state.segment, parent, arena.allocateFrom(name), mode);
      if (result != 0) state.capture();
      return result;
    } catch (Throwable failure) {
      throw abi("mkdirat", failure);
    }
  }

  static int unlinkAt(int parent, String name, boolean directory) {
    try (Arena arena = Arena.ofConfined()) {
      CallState state = state();
      int result = (int) UNLINKAT.invokeExact(
          state.segment, parent, arena.allocateFrom(name), directory ? AT_REMOVEDIR : 0);
      if (result != 0) state.capture();
      return result;
    } catch (Throwable failure) {
      throw abi("unlinkat", failure);
    }
  }

  static int close(int fd) {
    try {
      CallState state = state();
      int result = (int) CLOSE.invokeExact(state.segment, fd);
      if (result != 0) state.capture();
      return result;
    } catch (Throwable failure) {
      throw abi("close", failure);
    }
  }

  static int duplicate(int fd) {
    try {
      CallState state = state();
      int result = (int) DUP.invokeExact(state.segment, fd);
      if (result < 0) state.capture();
      return result;
    } catch (Throwable failure) {
      throw abi("dup", failure);
    }
  }

  static Stat stat(int fd) {
    return statAt(fd, "");
  }

  static Stat statAt(int parent, String name) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment bytes = arena.allocate(256, 8);
      CallState state = state();
      int flags = name.isEmpty() ? AT_EMPTY_PATH : AT_SYMLINK_NOFOLLOW;
      long result = syscall(state, SYS_STATX, parent, arena.allocateFrom(name),
          flags, STATX_MASK, bytes);
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
      return (int) EUID.invokeExact();
    } catch (Throwable failure) {
      throw abi("geteuid", failure);
    }
  }

  static int read(int fd, ByteBuffer target, long position) {
    int bytes = target.remaining();
    if (bytes == 0) return 0;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment nativeBuffer = arena.allocate(bytes, 1);
      CallState state = state();
      long count = (long) READ.invokeExact(state.segment, fd, nativeBuffer, (long) bytes, position);
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
      CallState state = state();
      long count = (long) WRITE.invokeExact(state.segment, fd, nativeBuffer, (long) bytes, position);
      if (count < 0) state.capture();
      return (int) count;
    } catch (Throwable failure) {
      throw abi("pwrite64", failure);
    }
  }

  static int truncate(int fd, long size) {
    try {
      CallState state = state();
      int result = (int) TRUNCATE.invokeExact(state.segment, fd, size);
      if (result != 0) state.capture();
      return result;
    } catch (Throwable failure) {
      throw abi("ftruncate", failure);
    }
  }

  static int force(int fd) {
    try {
      CallState state = state();
      int result = (int) FSYNC.invokeExact(state.segment, fd);
      if (result != 0) state.capture();
      return result;
    } catch (Throwable failure) {
      throw abi("fsync", failure);
    }
  }

  static int lock(int fd) {
    try {
      CallState state = state();
      int result = (int) FLOCK.invokeExact(state.segment, fd, LOCK_EX | LOCK_NB);
      if (result != 0) state.capture();
      return result;
    } catch (Throwable failure) {
      throw abi("flock", failure);
    }
  }

  static int unlock(int fd) {
    try {
      CallState state = state();
      int result = (int) FLOCK.invokeExact(state.segment, fd, LOCK_UN);
      if (result != 0) state.capture();
      return result;
    } catch (Throwable failure) {
      throw abi("flock", failure);
    }
  }

  static int rename(int sourceParent, String stage, int targetParent, String target, boolean exclusive) {
    try (Arena arena = Arena.ofConfined()) {
      CallState state = state();
      long result = syscallRename(state, sourceParent, arena.allocateFrom(stage), targetParent,
          arena.allocateFrom(target), exclusive ? RENAME_NOREPLACE : 0);
      return nativeResult(result, state);
    } catch (Throwable failure) {
      throw abi("renameat2", failure);
    }
  }

  static int readDirectory(int fd, MemorySegment buffer, int capacity) {
    try {
      CallState state = state();
      long result = syscallGetdents(state, fd, buffer, capacity);
      return nativeResult(result, state);
    } catch (Throwable failure) {
      throw abi("getdents64", failure);
    }
  }

  static int errno() {
    return state().errno;
  }

  static String directoryEntryName(MemorySegment bytes, long offset, int length) {
    return new String(bytes.asSlice(offset, length).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
  }

  private static MemorySegment openHow(Arena arena, int flags, int mode, long resolve) {
    MemorySegment how = arena.allocate(24, 8);
    how.set(ValueLayout.JAVA_LONG, 0, Integer.toUnsignedLong(flags));
    how.set(ValueLayout.JAVA_LONG, 8, Integer.toUnsignedLong(mode));
    how.set(ValueLayout.JAVA_LONG, 16, resolve);
    return how;
  }

  private static long syscall(CallState state, long number, long dirfd, MemorySegment path,
      MemorySegment how, long size) throws Throwable {
    return (long) SYSCALL5.invokeExact(state.segment, number, dirfd, path, how, size);
  }

  private static long syscall(CallState state, long number, long dirfd, MemorySegment path,
      long flags, long mask, MemorySegment buffer) throws Throwable {
    return (long) SYSCALL_STATX.invokeExact(state.segment, number, dirfd, path, flags, mask, buffer);
  }

  private static long syscallRename(CallState state, long oldDir, MemorySegment oldPath,
      long newDir, MemorySegment newPath, long flags) throws Throwable {
    return (long) SYSCALL_RENAME.invokeExact(
        state.segment, SYS_RENAMEAT2, oldDir, oldPath, newDir, newPath, flags);
  }

  private static long syscallGetdents(CallState state, int fd, MemorySegment buffer, int capacity)
      throws Throwable {
    return (long) SYSCALL4.invokeExact(
        state.segment, SYS_GETDENTS64, (long) fd, buffer, (long) capacity);
  }

  private static int nativeResult(long result, CallState state) {
    if (result < 0) state.capture();
    return result < Integer.MIN_VALUE ? -1 : (int) result;
  }

  private static MethodHandle function(String name, FunctionDescriptor descriptor, int firstVariadic) {
    return firstVariadic < 0
        ? LINKER.downcallHandle(LIBC.findOrThrow(name), descriptor,
            Linker.Option.captureCallState("errno"))
        : LINKER.downcallHandle(LIBC.findOrThrow(name), descriptor,
            Linker.Option.firstVariadicArg(firstVariadic),
            Linker.Option.captureCallState("errno"));
  }

  private static MethodHandle function(String name, FunctionDescriptor descriptor) {
    return function(name, descriptor, -1);
  }

  private static MethodHandle functionNoCapture(String name, FunctionDescriptor descriptor) {
    return LINKER.downcallHandle(LIBC.findOrThrow(name), descriptor);
  }

  private static int architecture() {
    String arch = System.getProperty("os.arch");
    if ("amd64".equals(arch) || "x86_64".equals(arch)) return 1;
    if ("aarch64".equals(arch) || "arm64".equals(arch)) return 2;
    return 0;
  }

  private static CallState state() {
    return CALL_STATE.get();
  }

  private static AssertionError abi(String operation, Throwable failure) {
    return new AssertionError("Linux FFM ABI invocation failed: " + operation, failure);
  }

  private static final class CallState {
    final Arena arena = Arena.ofAuto();
    final MemorySegment segment = arena.allocate(Linker.Option.captureStateLayout());
    int errno;

    void capture() {
      errno = segment.get(ValueLayout.JAVA_INT, ERRNO_OFFSET);
    }

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
