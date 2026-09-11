package io.riverdb.platform.riverd.linux;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryEntryType;
import io.riverdb.platform.file.DirectoryListResult;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** Lists one Linux directory with scope-local stream and decoding storage. */
final class LinuxDirectoryListing {
  private LinuxDirectoryListing() {
  }

  static StatusCode list(int parentFd, int directoryFlags, DirectoryListResult result) {
    int streamFd = LinuxFileBridge.openAt(parentFd, ".", directoryFlags, 0);
    if (streamFd < 0) {
      int error = LinuxNativeBindings.errno();
      return LinuxRiverDaemonFileSystem.status(error);
    }
    StatusCode scanStatus = StatusCode.IO_FAILURE;
    try (Arena arena = Arena.ofConfined()) {
      scanStatus = scan(parentFd, streamFd, result, arena.allocate(8192, 8));
    } finally {
      int closeStatus = LinuxFileBridge.close(streamFd);
      if (scanStatus.isOk() && closeStatus != 0) {
        int error = LinuxNativeBindings.errno();
        scanStatus = LinuxRiverDaemonFileSystem.status(error);
      }
    }
    return scanStatus;
  }

  private static StatusCode scan(
      int parentFd, int streamFd, DirectoryListResult result, MemorySegment buffer) {
    while (true) {
      int count = LinuxNamespaceBridge.readDirectory(streamFd, buffer, 8192);
      if (count == 0) {
        result.finish(1);
        return StatusCode.OK;
      }
      if (count < 0) {
        int error = LinuxNativeBindings.errno();
        return LinuxRiverDaemonFileSystem.status(error);
      }
      int offset = 0;
      while (offset < count) {
        if (count - offset < 19) return StatusCode.CORRUPTION;
        int recordLength = Short.toUnsignedInt(buffer.get(ValueLayout.JAVA_SHORT, offset + 16));
        if (recordLength < 19 || recordLength > count - offset) return StatusCode.CORRUPTION;
        int nameLength = 0;
        while (nameLength < recordLength - 19
            && buffer.get(ValueLayout.JAVA_BYTE, offset + 19 + nameLength) != 0) nameLength++;
        if (nameLength == 0 || nameLength == recordLength - 19) return StatusCode.CORRUPTION;
        String name = LinuxNamespaceBridge.directoryEntryName(buffer, offset + 19, nameLength);
        offset += recordLength;
        if (name.equals(".") || name.equals("..")) continue;
        LinuxNamespaceBridge.Stat stat = LinuxNamespaceBridge.statAt(parentFd, name);
        if (stat == null) {
          int error = LinuxNativeBindings.errno();
          return LinuxRiverDaemonFileSystem.status(error);
        }
        DirectoryEntryType type = stat.regularFile() ? DirectoryEntryType.FILE
            : stat.directory() ? DirectoryEntryType.DIRECTORY : null;
        if (type == null) return StatusCode.CORRUPTION;
        StatusCode added = result.add(name, type);
        if (!added.isOk()) return added;
      }
    }
  }
}
