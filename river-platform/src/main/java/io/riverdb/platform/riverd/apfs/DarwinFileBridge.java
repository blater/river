package io.riverdb.platform.riverd.apfs;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/** Small macOS libc bridge. All returned descriptors are owned by the caller. */
/* FFM use is confined here so every native pointer has one bounded call-scoped arena owner. */
@SuppressWarnings("restricted")
final class DarwinFileBridge {
  static final int AT_FDCWD = -2;
  static final int AT_SYMLINK_NOFOLLOW = 0x20;
  static final int AT_REMOVEDIR = 0x80;
  static final int O_RDONLY = 0;
  static final int O_RDWR = 2;
  static final int O_CREAT = 0x200;
  static final int O_EXCL = 0x800;
  static final int O_NOFOLLOW = 0x100;
  static final int O_DIRECTORY = 0x100000;
  static final int O_CLOEXEC = 0x1000000;
  static final int O_NOFOLLOW_ANY = 0x20000000;
  static final int RENAME_EXCL = 0x4;
  static final int F_FULLFSYNC = 51;
  static final int ACL_TYPE_EXTENDED = 0x100;
  static final int ENOENT = 2;
  static final int EEXIST = 17;
  static final int EACCES = 13;
  static final int EINVAL = 22;
  static final int ENOTDIR = 20;
  static final int ENOTEMPTY = 66;
  static final int ENOSPC = 28;
  static final int ELOOP = 62;
  static final int EWOULDBLOCK = 35;
  static final int S_IFMT = 0170000;
  static final int S_IFREG = 0100000;
  static final int S_IFDIR = 0040000;

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LIBC =
      SymbolLookup.libraryLookup("libSystem.B.dylib", Arena.global());
  private static final long ERRNO_STATE_OFFSET = Linker.Option.captureStateLayout()
      .byteOffset(MemoryLayout.PathElement.groupElement("errno"));
  /* Launcher I/O is synchronous; one confined capture state per calling thread avoids a second
     __error downcall after the native call has returned. */
  private static final ThreadLocal<CallState> CALL_STATE =
      ThreadLocal.withInitial(CallState::new);
  private static final MethodHandle OPENAT = function(
      "openat", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), 3);
  private static final MethodHandle MKDIRAT = function(
      "mkdirat", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
  private static final MethodHandle CLOSE = function(
      "close", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
  private static final MethodHandle DUP = function(
      "dup", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
  private static final MethodHandle FDOPENDIR = function(
      "fdopendir", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
  private static final MethodHandle READDIR_R = function(
      "readdir_r", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
          ValueLayout.ADDRESS, ValueLayout.ADDRESS));
  private static final MethodHandle CLOSEDIR = function(
      "closedir", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
  private static final MethodHandle FSTAT = function(
      "fstat", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS));
  private static final MethodHandle FSTATAT = function(
      "fstatat", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
  private static final MethodHandle GETEUID = functionNoCapture(
      "geteuid", FunctionDescriptor.of(ValueLayout.JAVA_INT));
  private static final MethodHandle READ = function(
      "pread", FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
  private static final MethodHandle WRITE = function(
      "pwrite", FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
  private static final MethodHandle TRUNCATE = function(
      "ftruncate", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
          ValueLayout.JAVA_LONG));
  private static final MethodHandle FSYNC = function(
      "fsync", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
  private static final MethodHandle FULLFSYNC = function(
      "fcntl", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
          ValueLayout.JAVA_INT), 2);
  private static final MethodHandle FLOCK = function(
      "flock", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
          ValueLayout.JAVA_INT));
  private static final MethodHandle RENAME = function(
      "renameatx_np", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
  private static final MethodHandle ACL_GET_ENTRY = function(
      "acl_get_entry", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
          ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
  private static final MethodHandle ACL_GET_TAG = function(
      "acl_get_tag_type", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
          ValueLayout.ADDRESS));
  private static final MethodHandle UNLINK = function(
      "unlinkat", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
  private static final MethodHandle ACL_GET = function(
      "acl_get_fd_np", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
          ValueLayout.JAVA_INT));
  private static final MethodHandle ACL_FREE = function(
      "acl_free", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
  private DarwinFileBridge() {
  }

  static int open(Path path, int flags, int mode) {
    try (Arena arena = Arena.ofConfined()) {
      CallState state = callState();
      int status = (int) OPENAT.invokeExact(
          state.segment, AT_FDCWD, arena.allocateFrom(path.toString()), flags, mode);
      if (status < 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("openat", failure);
    }
  }

  static int openAt(int parent, String name, int flags, int mode) {
    try (Arena arena = Arena.ofConfined()) {
      CallState state = callState();
      int status = (int) OPENAT.invokeExact(state.segment, parent, arena.allocateFrom(name), flags, mode);
      if (status < 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("openat", failure);
    }
  }

  static int mkdirAt(int parent, String name, int mode) {
    try (Arena arena = Arena.ofConfined()) {
      CallState state = callState();
      int status = (int) MKDIRAT.invokeExact(state.segment, parent, arena.allocateFrom(name), mode);
      if (status != 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("mkdirat", failure);
    }
  }

  static int close(int fd) {
    try {
      CallState state = callState();
      int status = (int) CLOSE.invokeExact(state.segment, fd);
      if (status != 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("close", failure);
    }
  }

  static int duplicate(int fd) {
    try {
      CallState state = callState();
      int status = (int) DUP.invokeExact(state.segment, fd);
      if (status < 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("dup", failure);
    }
  }

  static MemorySegment openDirectoryStream(int fd) {
    try {
      CallState state = callState();
      MemorySegment directory = (MemorySegment) FDOPENDIR.invokeExact(state.segment, fd);
      if (directory.equals(MemorySegment.NULL)) state.captureErrno();
      return directory;
    } catch (Throwable failure) {
      throw bridgeFailure("fdopendir", failure);
    }
  }

  static int readDirectory(
      MemorySegment directory, MemorySegment entryBuffer, MemorySegment resultPointer) {
    try {
      CallState state = callState();
      int status = (int) READDIR_R.invokeExact(
          state.segment, directory, entryBuffer, resultPointer);
      if (status != 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("readdir_r", failure);
    }
  }

  static int closeDirectoryStream(MemorySegment directory) {
    try {
      CallState state = callState();
      int status = (int) CLOSEDIR.invokeExact(state.segment, directory);
      if (status != 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("closedir", failure);
    }
  }

  static NativeStat stat(int fd) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment bytes = arena.allocate(144, 8);
      CallState state = callState();
      int status = (int) FSTAT.invokeExact(state.segment, fd, bytes);
      if (status != 0) state.captureErrno();
      return status == 0 ? readStat(bytes) : null;
    } catch (Throwable failure) {
      throw bridgeFailure("fstat", failure);
    }
  }

  static NativeStat statAt(int parent, String name) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment bytes = arena.allocate(144, 8);
      CallState state = callState();
      int status = (int) FSTATAT.invokeExact(state.segment, parent, arena.allocateFrom(name), bytes,
          AT_SYMLINK_NOFOLLOW);
      if (status != 0) state.captureErrno();
      return status == 0 ? readStat(bytes) : null;
    } catch (Throwable failure) {
      throw bridgeFailure("fstatat", failure);
    }
  }

  static int effectiveUid() {
    try {
      return (int) GETEUID.invokeExact();
    } catch (Throwable failure) {
      throw bridgeFailure("geteuid", failure);
    }
  }

  static int read(int fd, ByteBuffer target, long position) {
    int bytes = target.remaining();
    if (bytes == 0) return 0;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment nativeBuffer = arena.allocate(bytes, 1);
      CallState state = callState();
      long count = (long) READ.invokeExact(
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
      CallState state = callState();
      long count = (long) WRITE.invokeExact(
          state.segment, fd, nativeBuffer, (long) bytes, position);
      if (count < 0) state.captureErrno();
      return (int) count;
    } catch (Throwable failure) {
      throw bridgeFailure("pwrite", failure);
    }
  }

  static int truncate(int fd, long size) {
    try {
      CallState state = callState();
      int status = (int) TRUNCATE.invokeExact(state.segment, fd, size);
      if (status != 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("ftruncate", failure);
    }
  }

  static int forceDirectory(int fd) {
    try {
      CallState state = callState();
      int status = (int) FSYNC.invokeExact(state.segment, fd);
      if (status != 0) state.captureErrno();
      if (status != 0) return status;
      // fsync establishes namespace ordering; F_FULLFSYNC asks the device to flush it.
      status = (int) FULLFSYNC.invokeExact(state.segment, fd, F_FULLFSYNC);
      if (status != 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("fsync", failure);
    }
  }

  static int forceFile(int fd) {
    try {
      CallState state = callState();
      int status = (int) FULLFSYNC.invokeExact(state.segment, fd, F_FULLFSYNC);
      if (status != 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("fcntl(F_FULLFSYNC)", failure);
    }
  }

  static int lock(int fd) {
    try {
      CallState state = callState();
      int status = (int) FLOCK.invokeExact(state.segment, fd, 2 | 4);
      if (status != 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("flock", failure);
    }
  }

  static int unlock(int fd) {
    try {
      CallState state = callState();
      int status = (int) FLOCK.invokeExact(state.segment, fd, 8);
      if (status != 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("flock", failure);
    }
  }

  static int renameExclusive(int parent, String stage, String target) {
    return renameExclusive(parent, stage, parent, target);
  }

  static int renameExclusive(int sourceParent, String stage, int targetParent, String target) {
    try (Arena arena = Arena.ofConfined()) {
      CallState state = callState();
      int status = (int) RENAME.invokeExact(state.segment, sourceParent,
          arena.allocateFrom(stage), targetParent, arena.allocateFrom(target), RENAME_EXCL);
      if (status != 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("renameatx_np", failure);
    }
  }

  /** Returns 0 for no ACL or deny-only ACL, 1 when an allow entry is present, -1 if unreadable. */
  static int aclAllows(int fd) {
    try (Arena arena = Arena.ofConfined()) {
      CallState state = callState();
      MemorySegment acl = (MemorySegment) ACL_GET.invokeExact(
          state.segment, fd, ACL_TYPE_EXTENDED);
      if (acl.equals(MemorySegment.NULL)) {
        state.captureErrno();
        return state.errno == ENOENT ? 0 : -1;
      }
      MemorySegment entryPointer = arena.allocate(ValueLayout.ADDRESS);
      MemorySegment tagPointer = arena.allocate(ValueLayout.JAVA_INT);
      int entryId = 0;
      while (true) {
        int result = (int) ACL_GET_ENTRY.invokeExact(state.segment, acl, entryId, entryPointer);
        if (result != 0) {
          state.captureErrno();
          int error = state.errno;
          freeAcl(acl);
          return error == EINVAL ? 0 : -1;
        }
        MemorySegment entry = entryPointer.get(ValueLayout.ADDRESS, 0);
        if (entry.equals(MemorySegment.NULL)) {
          freeAcl(acl);
          return -1;
        }
        int tagStatus = (int) ACL_GET_TAG.invokeExact(state.segment, entry, tagPointer);
        if (tagStatus != 0) {
          state.captureErrno();
          freeAcl(acl);
          return -1;
        }
        if (tagPointer.get(ValueLayout.JAVA_INT, 0) == 1) {
          freeAcl(acl);
          return 1;
        }
        entryId = -1;
      }
    } catch (Throwable failure) {
      throw bridgeFailure("acl_get_fd_np/acl_get_entry", failure);
    }
  }

  static int renameReplace(int parent, String stage, String target) {
    try (Arena arena = Arena.ofConfined()) {
      CallState state = callState();
      int status = (int) RENAME.invokeExact(state.segment, parent, arena.allocateFrom(stage),
          parent, arena.allocateFrom(target), 0);
      if (status != 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("renameatx_np", failure);
    }
  }

  static int remove(int parent, String name) {
    try (Arena arena = Arena.ofConfined()) {
      CallState state = callState();
      int status = (int) UNLINK.invokeExact(state.segment, parent, arena.allocateFrom(name), 0);
      if (status != 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("unlinkat", failure);
    }
  }

  static int removeDirectory(int parent, String name) {
    try (Arena arena = Arena.ofConfined()) {
      CallState state = callState();
      int status = (int) UNLINK.invokeExact(
          state.segment, parent, arena.allocateFrom(name), AT_REMOVEDIR);
      if (status != 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("unlinkat", failure);
    }
  }

  static int errno() {
    return callState().errno;
  }

  private static NativeStat readStat(MemorySegment bytes) {
    long device = Integer.toUnsignedLong(bytes.get(ValueLayout.JAVA_INT, 0));
    int mode = Short.toUnsignedInt(bytes.get(ValueLayout.JAVA_SHORT, 4));
    int links = Short.toUnsignedInt(bytes.get(ValueLayout.JAVA_SHORT, 6));
    long inode = bytes.get(ValueLayout.JAVA_LONG, 8);
    int uid = bytes.get(ValueLayout.JAVA_INT, 16);
    long size = bytes.get(ValueLayout.JAVA_LONG, 96);
    return new NativeStat(
        new io.riverdb.platform.riverd.FileIdentity(device, 0, inode), mode, links, uid, size);
  }

  private static MethodHandle function(String name, FunctionDescriptor descriptor) {
    return function(name, descriptor, -1);
  }

  private static MethodHandle function(
      String name, FunctionDescriptor descriptor, int firstVariadicArgument) {
    // Intel macOS exposes the 64-bit inode ABI under suffixed symbols; arm64 uses unsuffixed
    // symbols. The selected four calls share the 144-byte stat and 64-bit dirent layouts.
    String resolvedName = intelMac() && switch (name) {
      case "fstat", "fstatat", "fdopendir", "readdir", "readdir_r" -> true;
      default -> false;
    } ? name + "$INODE64" : name;
    Linker.Option capture = Linker.Option.captureCallState("errno");
    return firstVariadicArgument < 0
        ? LINKER.downcallHandle(LIBC.findOrThrow(resolvedName), descriptor, capture)
        : LINKER.downcallHandle(
            LIBC.findOrThrow(resolvedName), descriptor,
            Linker.Option.firstVariadicArg(firstVariadicArgument), capture);
  }

  private static MethodHandle functionNoCapture(String name, FunctionDescriptor descriptor) {
    return functionNoCapture(name, descriptor, -1);
  }

  private static MethodHandle functionNoCapture(
      String name, FunctionDescriptor descriptor, int firstVariadicArgument) {
    String resolvedName = intelMac() && switch (name) {
      case "fstat", "fstatat", "fdopendir", "readdir", "readdir_r" -> true;
      default -> false;
    } ? name + "$INODE64" : name;
    return firstVariadicArgument < 0
        ? LINKER.downcallHandle(LIBC.findOrThrow(resolvedName), descriptor)
        : LINKER.downcallHandle(
            LIBC.findOrThrow(resolvedName), descriptor,
            Linker.Option.firstVariadicArg(firstVariadicArgument));
  }

  private static boolean intelMac() {
    String architecture = System.getProperty("os.arch");
    return "x86_64".equals(architecture) || "amd64".equals(architecture);
  }

  private static CallState callState() {
    return CALL_STATE.get();
  }

  private static final class CallState {
    final Arena arena = Arena.ofAuto();
    final MemorySegment segment = arena.allocate(Linker.Option.captureStateLayout());
    int errno;

    void captureErrno() {
      errno = segment.get(ValueLayout.JAVA_INT, ERRNO_STATE_OFFSET);
    }
  }

  private static void freeAcl(MemorySegment acl) throws Throwable {
    int ignored = (int) ACL_FREE.invokeExact(callState().segment, acl);
  }

  static final class NativeStat {
    final io.riverdb.platform.riverd.FileIdentity identity;
    final int mode;
    final int links;
    final int uid;
    final long size;

    NativeStat(
        io.riverdb.platform.riverd.FileIdentity identity,
        int mode,
        int links,
        int uid,
        long size) {
      this.identity = identity;
      this.mode = mode;
      this.links = links;
      this.uid = uid;
      this.size = size;
    }

    boolean regularFile() {
      return (mode & S_IFMT) == S_IFREG;
    }

    boolean directory() {
      return (mode & S_IFMT) == S_IFDIR;
    }
  }

  private static AssertionError bridgeFailure(String operation, Throwable failure) {
    return new AssertionError("Darwin FFM ABI invocation failed: " + operation, failure);
  }
}
