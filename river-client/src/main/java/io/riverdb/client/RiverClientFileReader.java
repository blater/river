package io.riverdb.client;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;

/** Reads one validated client artifact with bounded allocation and close precedence. */
final class RiverClientFileReader {
  private RiverClientFileReader() { }

  static boolean validAbsolutePath(Path path) {
    return path != null && path.isAbsolute() && path.equals(path.normalize())
        && validValue(path.toString());
  }

  private static boolean validChild(String value) {
    return !value.isEmpty() && !value.equals(".") && !value.equals("..")
        && value.indexOf('/') < 0 && value.indexOf('\\') < 0 && validValue(value);
  }

  private static boolean validValue(String value) {
    if (value.isEmpty() || !value.equals(value.strip())) return false;
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == '=' || character == '\r' || character == '\n' || character == 0
          || Character.getType(character) == Character.CONTROL) return false;
    }
    return true;
  }

  static StatusCode readBounded(
      RiverDaemonFileSystem fileSystem,
      Path path,
      int maximum,
      RiverClientConfiguration.BytesResult result) {
    if (fileSystem == null || result == null || !validAbsolutePath(path)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.clear();
    Path parentPath = path.getParent();
    Path fileName = path.getFileName();
    if (parentPath == null || fileName == null
        || !validChild(fileName.toString())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    RiverDirectoryResult directoryResult = new RiverDirectoryResult();
    StatusCode status = fileSystem.openDirectory(parentPath, directoryResult);
    if (!status.isOk()) return status;
    RiverDirectory directory = directoryResult.directory();
    RiverFileResult fileResult = new RiverFileResult();
    RiverFile file = null;
    byte[] bytes = null;
    boolean transferred = false;
    try {
      status = directory.openFile(fileName.toString(), RiverOpenMode.EXISTING, fileResult);
      if (status.isOk()) {
        file = fileResult.file();
        FileSizeResult size = new FileSizeResult();
        status = file.size(size);
        long length = size.sizeBytes();
        if (status.isOk() && (length < 0 || length > maximum)) status = StatusCode.CORRUPTION;
        if (status.isOk()) {
          bytes = new byte[(int) length];
          ByteBuffer target = ByteBuffer.wrap(bytes);
          IoResult io = new IoResult();
          long position = 0;
          while (status.isOk() && target.hasRemaining()) {
            status = file.read(position, target, io);
            int transferredBytes = io.bytesTransferred();
            if (status.isOk() && transferredBytes <= 0) {
              status = StatusCode.IO_FAILURE;
            } else if (status.isOk()) {
              position += transferredBytes;
            }
          }
        }
      }
      if (status.isOk()) {
        result.value = bytes;
        transferred = true;
      }
    } finally {
      StatusCode closeFile = file == null ? StatusCode.OK : file.close();
      StatusCode closeDirectory = directory.close();
      if (status.isOk() && !closeFile.isOk()) status = closeFile;
      if (status.isOk() && !closeDirectory.isOk()) status = closeDirectory;
      if (!transferred && bytes != null) Arrays.fill(bytes, (byte) 0);
    }
    return status;
  }
}
