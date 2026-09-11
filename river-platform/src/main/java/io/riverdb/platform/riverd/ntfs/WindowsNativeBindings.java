package io.riverdb.platform.riverd.ntfs;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/** Initializes Windows native handles and owns the shared status/call state. */
@SuppressWarnings("restricted")
final class WindowsNativeBindings {
  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup NTDLL = SymbolLookup.libraryLookup("ntdll.dll", Arena.global());
  private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
  private static final SymbolLookup ADVAPI32 = SymbolLookup.libraryLookup("advapi32.dll", Arena.global());
  private static final ThreadLocal<Integer> STATUS = ThreadLocal.withInitial(() -> 0);
  private static final long LAST_ERROR_OFFSET = Linker.Option.captureStateLayout()
      .byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"));
  private static final ThreadLocal<CallState> CALL_STATE = ThreadLocal.withInitial(CallState::new);

  static final MethodHandle NT_CREATE = nt("NtCreateFile", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
  static final MethodHandle NT_CLOSE = nt("NtClose", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
  static final MethodHandle NT_READ = nt("NtReadFile", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS));
  static final MethodHandle NT_WRITE = nt("NtWriteFile", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS));
  static final MethodHandle NT_FLUSH = nt("NtFlushBuffersFile", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
  static final MethodHandle NT_SET_INFO = nt("NtSetInformationFile", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
  static final MethodHandle NT_QUERY_INFO = nt("NtQueryInformationFile", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
  static final MethodHandle NT_QUERY_DIR = nt("NtQueryDirectoryFile", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE));
  static final MethodHandle DUPLICATE = win("DuplicateHandle", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
      ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
  static final MethodHandle LOCK_FILE = win("LockFileEx", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
  static final MethodHandle UNLOCK_FILE = win("UnlockFileEx", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
  static final MethodHandle CURRENT_PROCESS = winNoCapture("GetCurrentProcess", FunctionDescriptor.of(
      ValueLayout.ADDRESS));
  static final MethodHandle GET_FILE_INFO = win("GetFileInformationByHandleEx", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
      ValueLayout.JAVA_INT));
  static final MethodHandle OPEN_TOKEN = advapi("OpenProcessToken", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
  static final MethodHandle TOKEN_INFO = advapi("GetTokenInformation", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
  static final MethodHandle SECURITY_INFO = advapi("GetSecurityInfo", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
      ValueLayout.ADDRESS));
  static final MethodHandle SD_OWNER = advapi("GetSecurityDescriptorOwner", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
  static final MethodHandle SD_DACL = advapi("GetSecurityDescriptorDacl", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
      ValueLayout.ADDRESS));
  static final MethodHandle ACL_INFO = advapi("GetAclInformation", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT));
  static final MethodHandle GET_ACE = advapi("GetAce", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
  static final MethodHandle EQUAL_SID = advapiNoCapture("EqualSid", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
  static final MethodHandle WELL_KNOWN_SID = advapiNoCapture("IsWellKnownSid", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
  static final MethodHandle LOCAL_FREE = win("LocalFree", FunctionDescriptor.of(
      ValueLayout.ADDRESS, ValueLayout.ADDRESS));
  static final MethodHandle CONVERT_SDDL = advapi("ConvertStringSecurityDescriptorToSecurityDescriptorW",
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS, ValueLayout.ADDRESS));

  private WindowsNativeBindings() { }

  static CallState callState() {
    return CALL_STATE.get();
  }

  static int status() {
    return STATUS.get();
  }

  static int lastError() {
    return CALL_STATE.get().segment.get(ValueLayout.JAVA_INT, LAST_ERROR_OFFSET);
  }

  static void setStatus(int status) {
    STATUS.set(status);
  }

  private static MethodHandle nt(String name, FunctionDescriptor descriptor) {
    return LINKER.downcallHandle(NTDLL.findOrThrow(name), descriptor);
  }

  private static MethodHandle win(String name, FunctionDescriptor descriptor) {
    return LINKER.downcallHandle(KERNEL32.findOrThrow(name), descriptor,
        Linker.Option.captureCallState("GetLastError"));
  }

  private static MethodHandle winNoCapture(String name, FunctionDescriptor descriptor) {
    return LINKER.downcallHandle(KERNEL32.findOrThrow(name), descriptor);
  }

  private static MethodHandle advapi(String name, FunctionDescriptor descriptor) {
    return LINKER.downcallHandle(ADVAPI32.findOrThrow(name), descriptor,
        Linker.Option.captureCallState("GetLastError"));
  }

  private static MethodHandle advapiNoCapture(String name, FunctionDescriptor descriptor) {
    return LINKER.downcallHandle(ADVAPI32.findOrThrow(name), descriptor);
  }

  static final class CallState {
    final Arena arena = Arena.ofAuto();
    final MemorySegment segment = arena.allocate(Linker.Option.captureStateLayout());
  }
}
