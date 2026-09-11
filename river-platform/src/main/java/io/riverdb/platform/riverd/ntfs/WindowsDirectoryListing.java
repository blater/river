package io.riverdb.platform.riverd.ntfs;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryEntryType;
import io.riverdb.platform.file.DirectoryListResult;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/** Lists one Windows directory with a fresh descriptor-relative enumeration cursor. */
final class WindowsDirectoryListing {
  private WindowsDirectoryListing() {
  }

  static StatusCode list(MemorySegment parentHandle, DirectoryListResult result) {
    MemorySegment stream = WindowsFileBridge.openAt(parentHandle, ".", true, false);
    if (stream.equals(MemorySegment.NULL)) {
      return WindowsRiverDaemonFileSystem.status(WindowsFileBridge.status());
    }
    StatusCode status = StatusCode.OK;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment buffer = arena.allocate(64 * 1024L, 8);
      boolean restart = true;
      while (true) {
        int count = WindowsFileBridge.queryDirectory(stream, buffer, restart);
        restart = false;
        if (count == 0) {
          result.finish(1);
          break;
        }
        if (count < 0) {
          status = WindowsRiverDaemonFileSystem.status(WindowsFileBridge.status());
          break;
        }
        int offset = 0;
        while (offset < count) {
          String name = WindowsFileBridge.directoryName(buffer, offset);
          int next = WindowsFileBridge.nextOffset(buffer, offset);
          if (name == null || next < 0 || (next != 0 && next <= 0)
              || offset + (next == 0 ? count - offset : next) > count) {
            status = StatusCode.CORRUPTION;
            break;
          }
          if (!name.equals(".") && !name.equals("..")) {
            int attributes = WindowsFileBridge.entryAttributes(buffer, offset);
            if ((attributes & WindowsFileBridge.FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
              status = StatusCode.ACCESS_DENIED;
              break;
            }
            DirectoryEntryType type = (attributes & WindowsFileBridge.FILE_ATTRIBUTE_DIRECTORY) != 0
                ? DirectoryEntryType.DIRECTORY : DirectoryEntryType.FILE;
            StatusCode added = result.add(name, type);
            if (!added.isOk()) {
              status = added;
              break;
            }
          }
          if (next == 0) break;
          offset += next;
        }
        if (!status.isOk()) break;
      }
    } finally {
      WindowsFileBridge.close(stream);
    }
    return status;
  }
}
