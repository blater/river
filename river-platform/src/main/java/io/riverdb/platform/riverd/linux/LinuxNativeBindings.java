package io.riverdb.platform.riverd.linux;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/** Initializes Linux libc/syscall handles and owns the per-thread errno capture state. */
@SuppressWarnings("restricted")
final class LinuxNativeBindings {
  static final int ARCH = architecture();
  static final long SYS_OPENAT2 = 437;
  static final long SYS_RENAMEAT2 = ARCH == 1 ? 316 : 276;
  static final long SYS_STATX = ARCH == 1 ? 332 : 291;
  static final long SYS_GETDENTS64 = ARCH == 1 ? 217 : 61;

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LIBC =
      SymbolLookup.libraryLookup("libc.so.6", Arena.global());
  private static final long ERRNO_OFFSET = Linker.Option.captureStateLayout()
      .byteOffset(MemoryLayout.PathElement.groupElement("errno"));
  private static final ThreadLocal<CallState> CALL_STATE = ThreadLocal.withInitial(CallState::new);

  static final MethodHandle SYSCALL4 = function("syscall", FunctionDescriptor.of(
      ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
      ValueLayout.ADDRESS, ValueLayout.JAVA_LONG), 1);
  static final MethodHandle SYSCALL5 = function("syscall", FunctionDescriptor.of(
      ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG), 1);
  static final MethodHandle SYSCALL_STATX = function("syscall", FunctionDescriptor.of(
      ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
      ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS), 1);
  static final MethodHandle SYSCALL_RENAME = function("syscall", FunctionDescriptor.of(
      ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
      ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG), 1);
  static final MethodHandle MKDIRAT = function("mkdirat", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
  static final MethodHandle UNLINKAT = function("unlinkat", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
  static final MethodHandle CLOSE = function("close", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
  static final MethodHandle DUP = function("dup", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
  static final MethodHandle READ = function("pread64", FunctionDescriptor.of(
      ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
      ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
  static final MethodHandle WRITE = function("pwrite64", FunctionDescriptor.of(
      ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
      ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
  static final MethodHandle TRUNCATE = function("ftruncate", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
  static final MethodHandle FSYNC = function("fsync", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
  static final MethodHandle FLOCK = function("flock", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
  static final MethodHandle EUID = functionNoCapture("geteuid", FunctionDescriptor.of(
      ValueLayout.JAVA_INT));

  private LinuxNativeBindings() {
  }

  static boolean supportedArchitecture() {
    return ARCH != 0;
  }

  static int errno() {
    return state().errno;
  }

  static CallState state() {
    return CALL_STATE.get();
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

  static final class CallState {
    final Arena arena = Arena.ofAuto();
    final MemorySegment segment = arena.allocate(Linker.Option.captureStateLayout());
    int errno;

    void capture() {
      errno = segment.get(ValueLayout.JAVA_INT, ERRNO_OFFSET);
    }
  }
}
