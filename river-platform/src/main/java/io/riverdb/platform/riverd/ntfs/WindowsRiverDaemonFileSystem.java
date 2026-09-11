package io.riverdb.platform.riverd.ntfs;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverLockResult;
import java.nio.file.Path;

/** Windows NTFS handle-relative riverd filesystem adapter. */
public final class WindowsRiverDaemonFileSystem implements RiverDaemonFileSystem {
  @Override
  public StatusCode openAncestor(Path path, RiverDirectoryResult result) {
    return openPath(path, result, false);
  }

  @Override
  public StatusCode openDirectory(Path path, RiverDirectoryResult result) {
    return openPath(path, result, true);
  }

  private StatusCode openPath(Path path, RiverDirectoryResult result, boolean privateRequired) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!windows() || path == null || invalidPath(path)) return StatusCode.INVALID_EXTERNAL_INPUT;
    var handle = WindowsFileBridge.open(path, true, false);
    if (handle.equals(java.lang.foreign.MemorySegment.NULL)) return status(WindowsFileBridge.status());
    WindowsDirectoryInspection.Stat stat = WindowsDirectoryInspection.inspect(handle);
    StatusCode check = stat == null ? status(WindowsFileBridge.status())
        : !stat.directory ? StatusCode.CONFLICT
        : (stat.reparse ? StatusCode.ACCESS_DENIED
        : privateRequired ? WindowsDirectoryInspection.verifyPrivate(handle, stat)
        : WindowsDirectoryInspection.verifyAncestor(handle, stat));
    if (!check.isOk()) {
      WindowsFileBridge.close(handle);
      return check;
    }
    result.set(new WindowsRiverDirectory(handle, stat.identity));
    return StatusCode.OK;
  }

  @Override
  public StatusCode acquireExclusive(RiverFile file, RiverLockResult result) {
    if (file == null || result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!(file instanceof WindowsRiverFile windowsFile)) return StatusCode.INVALID_EXTERNAL_INPUT;
    synchronized (windowsFile) {
      StatusCode reserve = windowsFile.reserveLock();
      if (!reserve.isOk()) return reserve;
      var duplicate = WindowsFileBridge.duplicate(windowsFile.handle());
      if (duplicate.equals(java.lang.foreign.MemorySegment.NULL)) {
        windowsFile.cancelLock();
        return status(WindowsFileBridge.status());
      }
      var overlapped = WindowsFileBridge.lock(duplicate);
      if (overlapped.equals(java.lang.foreign.MemorySegment.NULL)) {
        int error = WindowsFileBridge.status();
        WindowsFileBridge.close(duplicate);
        windowsFile.cancelLock();
        return error == WindowsFileBridge.ERROR_LOCK_VIOLATION ? StatusCode.CONFLICT : status(error);
      }
      result.set(new WindowsRiverLock(windowsFile, duplicate, overlapped));
      return StatusCode.OK;
    }
  }

  static StatusCode status(int status) {
    return switch (status) {
      case WindowsFileBridge.STATUS_OBJECT_NAME_COLLISION -> StatusCode.CONFLICT;
      case WindowsFileBridge.STATUS_OBJECT_NAME_NOT_FOUND, WindowsFileBridge.STATUS_NOT_A_DIRECTORY,
          WindowsFileBridge.STATUS_FILE_IS_A_DIRECTORY -> StatusCode.CONFLICT;
      case WindowsFileBridge.STATUS_ACCESS_DENIED, WindowsFileBridge.STATUS_REPARSE_POINT_NOT_RESOLVED,
          WindowsFileBridge.STATUS_SHARING_VIOLATION, WindowsFileBridge.ERROR_ACCESS_DENIED ->
          StatusCode.ACCESS_DENIED;
      case WindowsFileBridge.STATUS_DELETE_PENDING -> StatusCode.CLOSED;
      default -> StatusCode.IO_FAILURE;
    };
  }

  private static boolean windows() {
    return System.getProperty("os.name", "").startsWith("Windows");
  }

  private static boolean invalidPath(Path path) {
    String value = path.toString();
    String absolute = path.toAbsolutePath().normalize().toString();
    int prefixLength = absolute.startsWith("\\\\") ? 6 : 4;
    // UNICODE_STRING uses unsigned 16-bit byte lengths, including a UTF-16 terminator.
    if (absolute.length() + prefixLength > 32766) return true;
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == 0 || character == '\r' || character == '\n'
          || Character.getType(character) == Character.CONTROL) return true;
    }
    return false;
  }
}
