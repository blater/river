package io.riverdb.platform.riverd.apfs;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/** Initializes the macOS libc symbols and one errno capture state per calling thread. */
@SuppressWarnings("restricted")
final class DarwinNativeBindings {
  static final int F_FULLFSYNC = 51;
  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LIBC =
      SymbolLookup.libraryLookup("libSystem.B.dylib", Arena.global());
  private static final long ERRNO_STATE_OFFSET = Linker.Option.captureStateLayout()
      .byteOffset(MemoryLayout.PathElement.groupElement("errno"));
  private static final ThreadLocal<CallState> CALL_STATE =
      ThreadLocal.withInitial(CallState::new);

  static final MethodHandle OPENAT = function(
      "openat", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), 3);
  static final MethodHandle MKDIRAT = function(
      "mkdirat", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
  static final MethodHandle CLOSE = function(
      "close", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
  static final MethodHandle DUP = function(
      "dup", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
  static final MethodHandle FDOPENDIR = function(
      "fdopendir", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
  static final MethodHandle READDIR_R = function(
      "readdir_r", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
          ValueLayout.ADDRESS, ValueLayout.ADDRESS));
  static final MethodHandle CLOSEDIR = function(
      "closedir", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
  static final MethodHandle FSTAT = function(
      "fstat", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS));
  static final MethodHandle FSTATAT = function(
      "fstatat", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
  static final MethodHandle GETEUID = functionNoCapture(
      "geteuid", FunctionDescriptor.of(ValueLayout.JAVA_INT));
  static final MethodHandle READ = function(
      "pread", FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
  static final MethodHandle WRITE = function(
      "pwrite", FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
  static final MethodHandle TRUNCATE = function(
      "ftruncate", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
          ValueLayout.JAVA_LONG));
  static final MethodHandle FSYNC = function(
      "fsync", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
  static final MethodHandle FULLFSYNC = function(
      "fcntl", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
          ValueLayout.JAVA_INT), 2);
  static final MethodHandle FLOCK = function(
      "flock", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
          ValueLayout.JAVA_INT));
  static final MethodHandle RENAME = function(
      "renameatx_np", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
  static final MethodHandle ACL_GET_ENTRY = function(
      "acl_get_entry", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
          ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
  static final MethodHandle ACL_GET_TAG = function(
      "acl_get_tag_type", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
          ValueLayout.ADDRESS));
  static final MethodHandle UNLINK = function(
      "unlinkat", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
  static final MethodHandle ACL_GET = function(
      "acl_get_fd_np", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
          ValueLayout.JAVA_INT));
  static final MethodHandle ACL_FREE = function(
      "acl_free", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

  private DarwinNativeBindings() { }

  static CallState callState() {
    return CALL_STATE.get();
  }

  static int errno() {
    return callState().errno;
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

  static final class CallState {
    final Arena arena = Arena.ofAuto();
    final MemorySegment segment = arena.allocate(Linker.Option.captureStateLayout());
    int errno;

    void captureErrno() {
      errno = segment.get(ValueLayout.JAVA_INT, ERRNO_STATE_OFFSET);
    }
  }
}
