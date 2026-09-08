package io.riverdb.platform.riverd.ntfs;

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
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Windows NT handle bridge. All pathname operations stay below a verified RootDirectory handle. */
@SuppressWarnings("restricted")
final class WindowsFileBridge {
  static final int FILE_READ_DATA = 0x0001;
  static final int FILE_WRITE_DATA = 0x0002;
  static final int FILE_READ_ATTRIBUTES = 0x0080;
  static final int READ_CONTROL = 0x00020000;
  static final int FILE_LIST_DIRECTORY = 0x0001;
  static final int FILE_ADD_FILE = 0x0002;
  static final int FILE_ADD_SUBDIRECTORY = 0x0004;
  static final int FILE_DELETE_CHILD = 0x0040;
  static final int FILE_APPEND_DATA = 0x0004;
  static final int FILE_WRITE_EA = 0x0010;
  static final int FILE_WRITE_ATTRIBUTES = 0x0100;
  static final int WRITE_DAC = 0x00040000;
  static final int WRITE_OWNER = 0x00080000;
  static final int FILE_TRAVERSE = 0x0020;
  static final int DELETE = 0x00010000;
  static final int SYNCHRONIZE = 0x00100000;
  static final int FILE_SHARE_READ = 1;
  static final int FILE_SHARE_WRITE = 2;
  static final int FILE_SHARE_DELETE = 4;
  static final int FILE_ATTRIBUTE_NORMAL = 0x80;
  static final int FILE_ATTRIBUTE_DIRECTORY = 0x10;
  static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x400;
  static final int FILE_DIRECTORY_FILE = 0x00000001;
  static final int FILE_NON_DIRECTORY_FILE = 0x00000040;
  static final int FILE_OPEN_REPARSE_POINT = 0x00200000;
  static final int FILE_SYNCHRONOUS_IO_NONALERT = 0x00000020;
  static final int OBJ_DONT_REPARSE = 0x00001000;
  static final int OBJ_CASE_INSENSITIVE = 0x00000040;
  static final int FILE_OPEN = 1;
  static final int FILE_CREATE = 2;
  static final int FILE_RENAME_INFORMATION = 10;
  static final int FILE_STANDARD_INFORMATION = 5;
  static final int FILE_ID_INFO = 18;
  static final int FILE_DIRECTORY_INFORMATION = 1;
  static final int FILE_DISPOSITION_INFORMATION = 13;
  static final int FILE_RENAME_REPLACE_IF_EXISTS = 1;
  static final int DUPLICATE_SAME_ACCESS = 2;
  static final int ERROR_INSUFFICIENT_BUFFER = 122;
  static final int ERROR_ACCESS_DENIED = 5;
  static final int ERROR_LOCK_VIOLATION = 33;
  static final int LOCKFILE_FAIL_IMMEDIATELY = 1;
  static final int LOCKFILE_EXCLUSIVE_LOCK = 2;
  static final int ERROR_NONE_MAPPED = 1332;
  static final int SECURITY_INFORMATION_OWNER = 1;
  static final int SECURITY_INFORMATION_DACL = 4;
  static final int SE_FILE_OBJECT = 1;
  static final int TOKEN_QUERY = 8;
  static final int TOKEN_USER = 1;
  static final int ACL_SIZE_INFORMATION = 2;
  static final int ACCESS_ALLOWED_ACE_TYPE = 0;
  static final int ACCESS_DENIED_ACE_TYPE = 1;
  static final int STATUS_SUCCESS = 0;
  static final int STATUS_OBJECT_NAME_NOT_FOUND = 0xC0000034;
  static final int STATUS_OBJECT_NAME_COLLISION = 0xC0000035;
  static final int STATUS_ACCESS_DENIED = 0xC0000022;
  static final int STATUS_REPARSE_POINT_NOT_RESOLVED = 0xC0000280;
  static final int STATUS_NOT_A_DIRECTORY = 0xC0000103;
  static final int STATUS_FILE_IS_A_DIRECTORY = 0xC00000BA;
  static final int STATUS_SHARING_VIOLATION = 0xC0000043;
  static final int STATUS_DELETE_PENDING = 0xC0000056;
  static final int STATUS_END_OF_FILE = 0xC0000011;
  static final int STATUS_NO_MORE_FILES = 0x80000006;
  static final int STATUS_BUFFER_OVERFLOW = 0x80000005;

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup NTDLL = SymbolLookup.libraryLookup("ntdll.dll", Arena.global());
  private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
  private static final SymbolLookup ADVAPI32 = SymbolLookup.libraryLookup("advapi32.dll", Arena.global());
  private static final ThreadLocal<Integer> STATUS = ThreadLocal.withInitial(() -> STATUS_SUCCESS);
  private static final long LAST_ERROR_OFFSET = Linker.Option.captureStateLayout()
      .byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"));
  private static final ThreadLocal<CallState> CALL_STATE = ThreadLocal.withInitial(CallState::new);

  private static final MethodHandle NT_CREATE = nt("NtCreateFile", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
  private static final MethodHandle NT_CLOSE = nt("NtClose", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
  private static final MethodHandle NT_READ = nt("NtReadFile", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS));
  private static final MethodHandle NT_WRITE = nt("NtWriteFile", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS));
  private static final MethodHandle NT_FLUSH = nt("NtFlushBuffersFile", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
  private static final MethodHandle NT_SET_INFO = nt("NtSetInformationFile", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
  private static final MethodHandle NT_QUERY_INFO = nt("NtQueryInformationFile", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
  private static final MethodHandle NT_QUERY_DIR = nt("NtQueryDirectoryFile", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE));
  private static final MethodHandle DUPLICATE = win("DuplicateHandle", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
      ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
  private static final MethodHandle LOCK_FILE = win("LockFileEx", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
  private static final MethodHandle UNLOCK_FILE = win("UnlockFileEx", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
  private static final MethodHandle CURRENT_PROCESS = winNoCapture("GetCurrentProcess", FunctionDescriptor.of(
      ValueLayout.ADDRESS));
  private static final MethodHandle GET_FILE_INFO = win("GetFileInformationByHandleEx", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
      ValueLayout.JAVA_INT));
  private static final MethodHandle OPEN_TOKEN = advapi("OpenProcessToken", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
  private static final MethodHandle TOKEN_INFO = advapi("GetTokenInformation", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
  private static final MethodHandle SECURITY_INFO = advapi("GetSecurityInfo", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
      ValueLayout.ADDRESS));
  private static final MethodHandle SD_OWNER = advapi("GetSecurityDescriptorOwner", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
  private static final MethodHandle SD_DACL = advapi("GetSecurityDescriptorDacl", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
      ValueLayout.ADDRESS));
  private static final MethodHandle ACL_INFO = advapi("GetAclInformation", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
      ValueLayout.JAVA_INT));
  private static final MethodHandle GET_ACE = advapi("GetAce", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
  private static final MethodHandle EQUAL_SID = advapiNoCapture("EqualSid", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
  private static final MethodHandle WELL_KNOWN_SID = advapiNoCapture("IsWellKnownSid", FunctionDescriptor.of(
      ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
  private static final MethodHandle LOCAL_FREE = win("LocalFree", FunctionDescriptor.of(
      ValueLayout.ADDRESS, ValueLayout.ADDRESS));
  private static final MethodHandle CONVERT_SDDL = advapi("ConvertStringSecurityDescriptorToSecurityDescriptorW",
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS, ValueLayout.ADDRESS));

  private WindowsFileBridge() { }

  static MemorySegment open(Path path, boolean directory, boolean create) {
    String absolute = path.toAbsolutePath().normalize().toString();
    String nativePath = absolute.startsWith("\\\\")
        ? "\\??\\UNC\\" + absolute.substring(2) : "\\??\\" + absolute;
    return open(null, nativePath, directory, create);
  }

  static MemorySegment openAt(MemorySegment parent, String name, boolean directory, boolean create) {
    return open(parent, name, directory, create);
  }

  private static MemorySegment open(MemorySegment parent, String name, boolean directory, boolean create) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment handleOut = arena.allocate(ValueLayout.ADDRESS);
      MemorySegment statusBlock = arena.allocate(16, 8);
      MemorySegment objectName = unicodeString(arena, name);
      MemorySegment attributes = arena.allocate(48, 8);
      attributes.set(ValueLayout.JAVA_INT, 0, 48);
      attributes.set(ValueLayout.ADDRESS, 8, parent == null ? MemorySegment.NULL : parent);
      attributes.set(ValueLayout.ADDRESS, 16, objectName);
      attributes.set(ValueLayout.JAVA_INT, 24, OBJ_DONT_REPARSE | OBJ_CASE_INSENSITIVE);
      MemorySegment securityDescriptor = create ? privateSecurityDescriptor(arena) : MemorySegment.NULL;
      if (create && securityDescriptor.equals(MemorySegment.NULL)) return MemorySegment.NULL;
      attributes.set(ValueLayout.ADDRESS, 32, securityDescriptor);
      int access = (directory ? FILE_LIST_DIRECTORY | FILE_ADD_FILE | FILE_ADD_SUBDIRECTORY
          | FILE_DELETE_CHILD | FILE_TRAVERSE : FILE_READ_DATA | FILE_WRITE_DATA)
          | FILE_READ_ATTRIBUTES | READ_CONTROL
          | DELETE | SYNCHRONIZE;
      int options = (directory ? FILE_DIRECTORY_FILE : FILE_NON_DIRECTORY_FILE)
          | FILE_SYNCHRONOUS_IO_NONALERT | FILE_OPEN_REPARSE_POINT;
      int status = (int) NT_CREATE.invokeExact(
          handleOut, access, attributes, statusBlock, MemorySegment.NULL, FILE_ATTRIBUTE_NORMAL,
          FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, create ? FILE_CREATE : FILE_OPEN,
          options, MemorySegment.NULL, 0);
      STATUS.set(status);
      if (!securityDescriptor.equals(MemorySegment.NULL)) {
        MemorySegment ignored = (MemorySegment) LOCAL_FREE.invokeExact(
            callState().segment, securityDescriptor);
      }
      if (status != STATUS_SUCCESS) return MemorySegment.NULL;
      return handleOut.get(ValueLayout.ADDRESS, 0);
    } catch (Throwable failure) {
      throw abi("NtCreateFile", failure);
    }
  }

  static int close(MemorySegment handle) {
    try {
      int status = (int) NT_CLOSE.invokeExact(handle);
      STATUS.set(status);
      return status == STATUS_SUCCESS ? 0 : -1;
    } catch (Throwable failure) {
      throw abi("NtClose", failure);
    }
  }

  static MemorySegment duplicate(MemorySegment handle) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
      MemorySegment process = (MemorySegment) CURRENT_PROCESS.invokeExact();
      int ok = (int) DUPLICATE.invokeExact(callState().segment, process, handle, process, out,
          0, 0, DUPLICATE_SAME_ACCESS);
      if (ok == 0) {
        STATUS.set(lastError());
        return MemorySegment.NULL;
      }
      return out.get(ValueLayout.ADDRESS, 0);
    } catch (Throwable failure) {
      throw abi("DuplicateHandle", failure);
    }
  }

  static MemorySegment lock(MemorySegment handle) {
    try {
      Arena arena = Arena.ofAuto();
      MemorySegment overlapped = arena.allocate(32, 8);
      // All River owners lock the same byte beyond instance metadata. Windows byte-range
      // locks also deny reads, so locking record bytes would prevent owner discovery.
      overlapped.set(ValueLayout.JAVA_LONG, 16, Long.MAX_VALUE - 1);
      int ok = (int) LOCK_FILE.invokeExact(callState().segment, handle,
          LOCKFILE_EXCLUSIVE_LOCK | LOCKFILE_FAIL_IMMEDIATELY, 0, 1, 0, overlapped);
      if (ok == 0) {
        STATUS.set(lastError());
        return MemorySegment.NULL;
      }
      return overlapped;
    } catch (Throwable failure) {
      throw abi("LockFileEx", failure);
    }
  }

  static int unlock(MemorySegment handle, MemorySegment overlapped) {
    try {
      int ok = (int) UNLOCK_FILE.invokeExact(callState().segment, handle, 0, 1, 0, overlapped);
      if (ok == 0) STATUS.set(lastError());
      return ok == 0 ? -1 : 0;
    } catch (Throwable failure) {
      throw abi("UnlockFileEx", failure);
    }
  }

  static int read(MemorySegment handle, ByteBuffer target, long position) {
    if (!target.hasRemaining()) return 0;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment iosb = arena.allocate(16, 8);
      MemorySegment offset = arena.allocate(ValueLayout.JAVA_LONG);
      offset.set(ValueLayout.JAVA_LONG, 0, position);
      MemorySegment bytes = arena.allocate(target.remaining(), 1);
      int status = (int) NT_READ.invokeExact(handle, MemorySegment.NULL, MemorySegment.NULL,
          MemorySegment.NULL, iosb, bytes, target.remaining(), offset, MemorySegment.NULL);
      STATUS.set(status);
      if (status == STATUS_END_OF_FILE) return 0;
      if (status != STATUS_SUCCESS && status != STATUS_BUFFER_OVERFLOW) return -1;
      long count = iosb.get(ValueLayout.JAVA_LONG, 8);
      ByteBuffer copy = bytes.asByteBuffer();
      copy.limit((int) count);
      target.put(copy);
      return (int) count;
    } catch (Throwable failure) {
      throw abi("NtReadFile", failure);
    }
  }

  static int write(MemorySegment handle, ByteBuffer source, long position) {
    if (!source.hasRemaining()) return 0;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment iosb = arena.allocate(16, 8);
      MemorySegment offset = arena.allocate(ValueLayout.JAVA_LONG);
      offset.set(ValueLayout.JAVA_LONG, 0, position);
      MemorySegment bytes = arena.allocate(source.remaining(), 1);
      bytes.asByteBuffer().put(source.duplicate());
      int status = (int) NT_WRITE.invokeExact(handle, MemorySegment.NULL, MemorySegment.NULL,
          MemorySegment.NULL, iosb, bytes, source.remaining(), offset, MemorySegment.NULL);
      STATUS.set(status);
      if (status != STATUS_SUCCESS) return -1;
      return (int) iosb.get(ValueLayout.JAVA_LONG, 8);
    } catch (Throwable failure) {
      throw abi("NtWriteFile", failure);
    }
  }

  static int force(MemorySegment handle) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment iosb = arena.allocate(16, 8);
      int status = (int) NT_FLUSH.invokeExact(handle, iosb);
      STATUS.set(status);
      return status == STATUS_SUCCESS ? 0 : -1;
    } catch (Throwable failure) {
      throw abi("NtFlushBuffersFile", failure);
    }
  }

  static long size(MemorySegment handle) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment iosb = arena.allocate(16, 8);
      MemorySegment info = arena.allocate(24, 8);
      int status = (int) NT_QUERY_INFO.invokeExact(handle, iosb, info, 24, FILE_STANDARD_INFORMATION);
      STATUS.set(status);
      return status == STATUS_SUCCESS ? info.get(ValueLayout.JAVA_LONG, 8) : -1;
    } catch (Throwable failure) {
      throw abi("NtQueryInformationFile", failure);
    }
  }

  static int truncate(MemorySegment handle, long size) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment iosb = arena.allocate(16, 8);
      MemorySegment eof = arena.allocate(ValueLayout.JAVA_LONG);
      eof.set(ValueLayout.JAVA_LONG, 0, size);
      int status = (int) NT_SET_INFO.invokeExact(handle, iosb, eof, 8, 20);
      STATUS.set(status);
      return status == STATUS_SUCCESS ? 0 : -1;
    } catch (Throwable failure) {
      throw abi("NtSetInformationFile(EOF)", failure);
    }
  }

  static FileIdentity identity(MemorySegment handle) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment info = arena.allocate(24, 8);
      int ok = (int) GET_FILE_INFO.invokeExact(callState().segment, handle, FILE_ID_INFO, info, 24);
      if (ok == 0) {
        STATUS.set(lastError());
        return null;
      }
      long volume = info.get(ValueLayout.JAVA_LONG, 0);
      long high = info.get(ValueLayout.JAVA_LONG, 8);
      long low = info.get(ValueLayout.JAVA_LONG, 16);
      return new FileIdentity(volume, high, low);
    } catch (Throwable failure) {
      throw abi("GetFileInformationByHandleEx", failure);
    }
  }

  static int attributes(MemorySegment handle) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment info = arena.allocate(52, 8);
      int ok = (int) GET_FILE_INFO.invokeExact(callState().segment, handle, 0, info, 52);
      if (ok == 0) {
        STATUS.set(lastError());
        return -1;
      }
      return info.get(ValueLayout.JAVA_INT, 32);
    } catch (Throwable failure) {
      throw abi("GetFileInformationByHandleEx", failure);
    }
  }

  static int links(MemorySegment handle) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment iosb = arena.allocate(16, 8);
      MemorySegment info = arena.allocate(24, 8);
      int status = (int) NT_QUERY_INFO.invokeExact(handle, iosb, info, 24, FILE_STANDARD_INFORMATION);
      STATUS.set(status);
      return status == STATUS_SUCCESS ? info.get(ValueLayout.JAVA_INT, 16) : -1;
    } catch (Throwable failure) {
      throw abi("NtQueryInformationFile", failure);
    }
  }

  static int rename(MemorySegment stage, MemorySegment targetParent, String target, boolean replace) {
    try (Arena arena = Arena.ofConfined()) {
      byte[] utf16 = target.getBytes(StandardCharsets.UTF_16LE);
      MemorySegment iosb = arena.allocate(16, 8);
      MemorySegment info = arena.allocate(20L + utf16.length, 8);
      info.set(ValueLayout.JAVA_INT, 0, replace ? FILE_RENAME_REPLACE_IF_EXISTS : 0);
      info.set(ValueLayout.ADDRESS, 8, targetParent);
      info.set(ValueLayout.JAVA_INT, 16, utf16.length);
      info.asSlice(20, utf16.length).copyFrom(MemorySegment.ofArray(utf16));
      int status = (int) NT_SET_INFO.invokeExact(stage, iosb, info, 20 + utf16.length,
          FILE_RENAME_INFORMATION);
      STATUS.set(status);
      return status == STATUS_SUCCESS ? 0 : -1;
    } catch (Throwable failure) {
      throw abi("NtSetInformationFile(rename)", failure);
    }
  }

  static int remove(MemorySegment handle) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment iosb = arena.allocate(16, 8);
      MemorySegment disposition = arena.allocate(ValueLayout.JAVA_BYTE);
      disposition.set(ValueLayout.JAVA_BYTE, 0, (byte) 1);
      int status = (int) NT_SET_INFO.invokeExact(handle, iosb, disposition, 1,
          FILE_DISPOSITION_INFORMATION);
      STATUS.set(status);
      return status == STATUS_SUCCESS ? 0 : -1;
    } catch (Throwable failure) {
      throw abi("NtSetInformationFile(disposition)", failure);
    }
  }

  static int queryDirectory(MemorySegment handle, MemorySegment buffer, boolean restart) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment iosb = arena.allocate(16, 8);
      int status = (int) NT_QUERY_DIR.invokeExact(handle, MemorySegment.NULL, MemorySegment.NULL,
          MemorySegment.NULL, iosb, buffer, (int) buffer.byteSize(), FILE_DIRECTORY_INFORMATION,
          (byte) 0, MemorySegment.NULL, (byte) (restart ? 1 : 0));
      STATUS.set(status);
      if (status == STATUS_SUCCESS || status == STATUS_BUFFER_OVERFLOW) {
        return (int) iosb.get(ValueLayout.JAVA_LONG, 8);
      }
      return status == STATUS_NO_MORE_FILES ? 0 : -1;
    } catch (Throwable failure) {
      throw abi("NtQueryDirectoryFile", failure);
    }
  }

  static String directoryName(MemorySegment buffer, int offset) {
    int length = buffer.get(ValueLayout.JAVA_INT, offset + 60);
    if (length < 2 || (length & 1) != 0 || offset + 64L + length > buffer.byteSize()) return null;
    byte[] bytes = buffer.asSlice(offset + 64L, length).toArray(ValueLayout.JAVA_BYTE);
    return new String(bytes, StandardCharsets.UTF_16LE);
  }

  static int nextOffset(MemorySegment buffer, int offset) {
    return buffer.get(ValueLayout.JAVA_INT, offset);
  }

  static int entryAttributes(MemorySegment buffer, int offset) {
    return buffer.get(ValueLayout.JAVA_INT, offset + 56);
  }

  static int status() { return STATUS.get(); }

  static int verifyOwnerAndDacl(MemorySegment handle, boolean privateOnly) {
    // Query the verified handle. SYSTEM and built-in administrators are the privileged
    // authorities excluded by the portable threat boundary; other users remain restricted.
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment ownerOut = arena.allocate(ValueLayout.ADDRESS);
      MemorySegment daclOut = arena.allocate(ValueLayout.ADDRESS);
      MemorySegment descriptorOut = arena.allocate(ValueLayout.ADDRESS);
      int error = (int) SECURITY_INFO.invokeExact(callState().segment, handle, SE_FILE_OBJECT,
          SECURITY_INFORMATION_OWNER | SECURITY_INFORMATION_DACL, ownerOut, MemorySegment.NULL,
          daclOut, MemorySegment.NULL, descriptorOut);
      if (error != 0) return error;
      MemorySegment descriptor = descriptorOut.get(ValueLayout.ADDRESS, 0);
      try {
        MemorySegment tokenOut = arena.allocate(ValueLayout.ADDRESS);
        MemorySegment process = (MemorySegment) CURRENT_PROCESS.invokeExact();
        if ((int) OPEN_TOKEN.invokeExact(callState().segment, process, TOKEN_QUERY, tokenOut) == 0) {
          return lastError();
        }
        MemorySegment token = tokenOut.get(ValueLayout.ADDRESS, 0);
        try {
          MemorySegment sizeOut = arena.allocate(ValueLayout.JAVA_INT);
          int ignored = (int) TOKEN_INFO.invokeExact(callState().segment, token, TOKEN_USER,
              MemorySegment.NULL, 0, sizeOut);
          int size = sizeOut.get(ValueLayout.JAVA_INT, 0);
          if (size <= 0) return lastError();
          MemorySegment tokenBytes = arena.allocate(size, 8);
          if ((int) TOKEN_INFO.invokeExact(callState().segment, token, TOKEN_USER, tokenBytes,
              size, sizeOut) == 0) {
            return lastError();
          }
          MemorySegment tokenSid = tokenBytes.get(ValueLayout.ADDRESS, 0);
          MemorySegment owner = ownerOut.get(ValueLayout.ADDRESS, 0);
          if ((int) EQUAL_SID.invokeExact(owner, tokenSid) == 0) return STATUS_ACCESS_DENIED;
          MemorySegment dacl = daclOut.get(ValueLayout.ADDRESS, 0);
          if (dacl.equals(MemorySegment.NULL)) return STATUS_ACCESS_DENIED;
          MemorySegment aclInfo = arena.allocate(12, 4);
          if ((int) ACL_INFO.invokeExact(callState().segment, dacl, aclInfo, 12,
              ACL_SIZE_INFORMATION) == 0) {
            return lastError();
          }
          int count = aclInfo.get(ValueLayout.JAVA_INT, 0);
          MemorySegment aceOut = arena.allocate(ValueLayout.ADDRESS);
          for (int index = 0; index < count; index++) {
            if ((int) GET_ACE.invokeExact(callState().segment, dacl, index, aceOut) == 0) {
              return lastError();
            }
            MemorySegment acePointer = aceOut.get(ValueLayout.ADDRESS, 0);
            MemorySegment aceHeader = acePointer.reinterpret(4);
            int aceSize = Short.toUnsignedInt(aceHeader.get(ValueLayout.JAVA_SHORT, 2));
            if (aceSize < 8) return STATUS_ACCESS_DENIED;
            MemorySegment ace = acePointer.reinterpret(aceSize);
            int type = Byte.toUnsignedInt(ace.get(ValueLayout.JAVA_BYTE, 0));
            if ((ace.get(ValueLayout.JAVA_BYTE, 1) & 8) != 0) continue; // INHERIT_ONLY_ACE
            if (type == ACCESS_ALLOWED_ACE_TYPE) {
              MemorySegment sid = ace.asSlice(8);
              int mask = ace.get(ValueLayout.JAVA_INT, 4);
              boolean other = (int) EQUAL_SID.invokeExact(owner, sid) == 0
                  && (int) WELL_KNOWN_SID.invokeExact(sid, 71) == 0 // owner rights
                  && (int) WELL_KNOWN_SID.invokeExact(sid, 22) == 0 // local system
                  && (int) WELL_KNOWN_SID.invokeExact(sid, 26) == 0; // built-in administrators
              if (other && (privateOnly || (mask & (FILE_ADD_FILE | FILE_ADD_SUBDIRECTORY
                  | FILE_DELETE_CHILD | FILE_APPEND_DATA | FILE_WRITE_EA
                  | FILE_WRITE_ATTRIBUTES | DELETE | WRITE_DAC | WRITE_OWNER
                  | 0x40000000 | 0x10000000)) != 0)) { // GENERIC_WRITE / GENERIC_ALL
                return STATUS_ACCESS_DENIED;
              }
            } else if (type != ACCESS_DENIED_ACE_TYPE) {
              return STATUS_ACCESS_DENIED;
            }
          }
          return STATUS_SUCCESS;
        } finally {
          close(token);
        }
      } finally {
        MemorySegment ignored = (MemorySegment) LOCAL_FREE.invokeExact(
            callState().segment, descriptor);
      }
    } catch (Throwable failure) {
      throw abi("Windows security descriptor", failure);
    }
  }

  static int lastError() {
    return CALL_STATE.get().segment.get(ValueLayout.JAVA_INT, LAST_ERROR_OFFSET);
  }

  private static MemorySegment unicodeString(Arena arena, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_16LE);
    MemorySegment chars = arena.allocate(bytes.length + 2L, 2);
    chars.asSlice(0, bytes.length).copyFrom(MemorySegment.ofArray(bytes));
    MemorySegment string = arena.allocate(16, 8);
    string.set(ValueLayout.JAVA_SHORT, 0, (short) bytes.length);
    string.set(ValueLayout.JAVA_SHORT, 2, (short) (bytes.length + 2));
    string.set(ValueLayout.ADDRESS, 8, chars);
    return string;
  }

  private static MemorySegment privateSecurityDescriptor(Arena arena) {
    try {
      MemorySegment text = wideString(arena, "D:P(A;;FA;;;OW)");
      MemorySegment descriptorOut = arena.allocate(ValueLayout.ADDRESS);
      MemorySegment sizeOut = arena.allocate(ValueLayout.JAVA_INT);
      int ok = (int) CONVERT_SDDL.invokeExact(
          callState().segment, text, 1, descriptorOut, sizeOut);
      if (ok == 0) {
        STATUS.set(lastError());
        return MemorySegment.NULL;
      }
      return descriptorOut.get(ValueLayout.ADDRESS, 0);
    } catch (Throwable failure) {
      throw abi("ConvertStringSecurityDescriptorToSecurityDescriptorW", failure);
    }
  }

  private static MemorySegment wideString(Arena arena, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_16LE);
    MemorySegment result = arena.allocate(bytes.length + 2L, 2);
    result.asSlice(0, bytes.length).copyFrom(MemorySegment.ofArray(bytes));
    return result;
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

  private static AssertionError abi(String operation, Throwable failure) {
    return new AssertionError("Windows FFM ABI invocation failed: " + operation, failure);
  }

  private static CallState callState() {
    return CALL_STATE.get();
  }

  private static final class CallState {
    final Arena arena = Arena.ofAuto();
    final MemorySegment segment = arena.allocate(Linker.Option.captureStateLayout());
  }
}
