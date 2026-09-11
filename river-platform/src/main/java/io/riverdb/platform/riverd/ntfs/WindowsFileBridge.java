package io.riverdb.platform.riverd.ntfs;

import io.riverdb.platform.riverd.FileIdentity;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
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
      MemorySegment securityDescriptor = create ? WindowsSecurityPolicy.privateSecurityDescriptor(arena) : MemorySegment.NULL;
      if (create && securityDescriptor.equals(MemorySegment.NULL)) return MemorySegment.NULL;
      attributes.set(ValueLayout.ADDRESS, 32, securityDescriptor);
      int access = (directory ? FILE_LIST_DIRECTORY | FILE_ADD_FILE | FILE_ADD_SUBDIRECTORY
          | FILE_DELETE_CHILD | FILE_TRAVERSE : FILE_READ_DATA | FILE_WRITE_DATA)
          | FILE_READ_ATTRIBUTES | READ_CONTROL
          | DELETE | SYNCHRONIZE;
      int options = (directory ? FILE_DIRECTORY_FILE : FILE_NON_DIRECTORY_FILE)
          | FILE_SYNCHRONOUS_IO_NONALERT | FILE_OPEN_REPARSE_POINT;
      int status = (int) WindowsNativeBindings.NT_CREATE.invokeExact(
          handleOut, access, attributes, statusBlock, MemorySegment.NULL, FILE_ATTRIBUTE_NORMAL,
          FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, create ? FILE_CREATE : FILE_OPEN,
          options, MemorySegment.NULL, 0);
      WindowsNativeBindings.setStatus(status);
      if (!securityDescriptor.equals(MemorySegment.NULL)) {
        MemorySegment ignored = (MemorySegment) WindowsNativeBindings.LOCAL_FREE.invokeExact(
            WindowsNativeBindings.callState().segment, securityDescriptor);
      }
      if (status != STATUS_SUCCESS) return MemorySegment.NULL;
      return handleOut.get(ValueLayout.ADDRESS, 0);
    } catch (Throwable failure) {
      throw abi("NtCreateFile", failure);
    }
  }

  static int close(MemorySegment handle) {
    try {
      int status = (int) WindowsNativeBindings.NT_CLOSE.invokeExact(handle);
      WindowsNativeBindings.setStatus(status);
      return status == STATUS_SUCCESS ? 0 : -1;
    } catch (Throwable failure) {
      throw abi("NtClose", failure);
    }
  }

  static MemorySegment duplicate(MemorySegment handle) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
      MemorySegment process = (MemorySegment) WindowsNativeBindings.CURRENT_PROCESS.invokeExact();
      int ok = (int) WindowsNativeBindings.DUPLICATE.invokeExact(WindowsNativeBindings.callState().segment, process, handle, process, out,
          0, 0, DUPLICATE_SAME_ACCESS);
      if (ok == 0) {
        WindowsNativeBindings.setStatus(WindowsNativeBindings.lastError());
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
      int ok = (int) WindowsNativeBindings.LOCK_FILE.invokeExact(WindowsNativeBindings.callState().segment, handle,
          LOCKFILE_EXCLUSIVE_LOCK | LOCKFILE_FAIL_IMMEDIATELY, 0, 1, 0, overlapped);
      if (ok == 0) {
        WindowsNativeBindings.setStatus(WindowsNativeBindings.lastError());
        return MemorySegment.NULL;
      }
      return overlapped;
    } catch (Throwable failure) {
      throw abi("LockFileEx", failure);
    }
  }

  static int unlock(MemorySegment handle, MemorySegment overlapped) {
    try {
      int ok = (int) WindowsNativeBindings.UNLOCK_FILE.invokeExact(WindowsNativeBindings.callState().segment, handle, 0, 1, 0, overlapped);
      if (ok == 0) WindowsNativeBindings.setStatus(WindowsNativeBindings.lastError());
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
      int status = (int) WindowsNativeBindings.NT_READ.invokeExact(handle, MemorySegment.NULL, MemorySegment.NULL,
          MemorySegment.NULL, iosb, bytes, target.remaining(), offset, MemorySegment.NULL);
      WindowsNativeBindings.setStatus(status);
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
      int status = (int) WindowsNativeBindings.NT_WRITE.invokeExact(handle, MemorySegment.NULL, MemorySegment.NULL,
          MemorySegment.NULL, iosb, bytes, source.remaining(), offset, MemorySegment.NULL);
      WindowsNativeBindings.setStatus(status);
      if (status != STATUS_SUCCESS) return -1;
      return (int) iosb.get(ValueLayout.JAVA_LONG, 8);
    } catch (Throwable failure) {
      throw abi("NtWriteFile", failure);
    }
  }

  static int force(MemorySegment handle) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment iosb = arena.allocate(16, 8);
      int status = (int) WindowsNativeBindings.NT_FLUSH.invokeExact(handle, iosb);
      WindowsNativeBindings.setStatus(status);
      return status == STATUS_SUCCESS ? 0 : -1;
    } catch (Throwable failure) {
      throw abi("NtFlushBuffersFile", failure);
    }
  }

  static long size(MemorySegment handle) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment iosb = arena.allocate(16, 8);
      MemorySegment info = arena.allocate(24, 8);
      int status = (int) WindowsNativeBindings.NT_QUERY_INFO.invokeExact(handle, iosb, info, 24, FILE_STANDARD_INFORMATION);
      WindowsNativeBindings.setStatus(status);
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
      int status = (int) WindowsNativeBindings.NT_SET_INFO.invokeExact(handle, iosb, eof, 8, 20);
      WindowsNativeBindings.setStatus(status);
      return status == STATUS_SUCCESS ? 0 : -1;
    } catch (Throwable failure) {
      throw abi("NtSetInformationFile(EOF)", failure);
    }
  }

  static FileIdentity identity(MemorySegment handle) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment info = arena.allocate(24, 8);
      int ok = (int) WindowsNativeBindings.GET_FILE_INFO.invokeExact(WindowsNativeBindings.callState().segment, handle, FILE_ID_INFO, info, 24);
      if (ok == 0) {
        WindowsNativeBindings.setStatus(WindowsNativeBindings.lastError());
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
      int ok = (int) WindowsNativeBindings.GET_FILE_INFO.invokeExact(WindowsNativeBindings.callState().segment, handle, 0, info, 52);
      if (ok == 0) {
        WindowsNativeBindings.setStatus(WindowsNativeBindings.lastError());
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
      int status = (int) WindowsNativeBindings.NT_QUERY_INFO.invokeExact(handle, iosb, info, 24, FILE_STANDARD_INFORMATION);
      WindowsNativeBindings.setStatus(status);
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
      int status = (int) WindowsNativeBindings.NT_SET_INFO.invokeExact(stage, iosb, info, 20 + utf16.length,
          FILE_RENAME_INFORMATION);
      WindowsNativeBindings.setStatus(status);
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
      int status = (int) WindowsNativeBindings.NT_SET_INFO.invokeExact(handle, iosb, disposition, 1,
          FILE_DISPOSITION_INFORMATION);
      WindowsNativeBindings.setStatus(status);
      return status == STATUS_SUCCESS ? 0 : -1;
    } catch (Throwable failure) {
      throw abi("NtSetInformationFile(disposition)", failure);
    }
  }

  static int queryDirectory(MemorySegment handle, MemorySegment buffer, boolean restart) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment iosb = arena.allocate(16, 8);
      int status = (int) WindowsNativeBindings.NT_QUERY_DIR.invokeExact(handle, MemorySegment.NULL, MemorySegment.NULL,
          MemorySegment.NULL, iosb, buffer, (int) buffer.byteSize(), FILE_DIRECTORY_INFORMATION,
          (byte) 0, MemorySegment.NULL, (byte) (restart ? 1 : 0));
      WindowsNativeBindings.setStatus(status);
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

  private static AssertionError abi(String operation, Throwable failure) {
    return new AssertionError("Windows FFM ABI invocation failed: " + operation, failure);
  }


}
