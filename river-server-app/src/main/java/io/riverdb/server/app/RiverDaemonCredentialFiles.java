package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Reads bounded credential files and always closes the acquired file handle. */
final class RiverDaemonCredentialFiles {
  private RiverDaemonCredentialFiles() {
  }

  static StatusCode read(
      RiverDirectory directory,
      String name,
      int maximumBytes,
      RiverDaemonCredentials.BytesResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.clear();
    RiverFileResult fileResult = new RiverFileResult();
    StatusCode status = directory.openFile(name, RiverOpenMode.EXISTING, fileResult);
    if (!status.isOk()) return status;
    RiverFile file = fileResult.file();
    byte[] bytes = null;
    try {
      FileSizeResult size = new FileSizeResult();
      status = file.size(size);
      long length = size.sizeBytes();
      if (status.isOk() && (length < 0 || length > maximumBytes)) status = StatusCode.CORRUPTION;
      if (status.isOk()) {
        bytes = new byte[(int) length];
        ByteBuffer target = ByteBuffer.wrap(bytes);
        IoResult io = new IoResult();
        long position = 0;
        while (status.isOk() && target.hasRemaining()) {
          status = file.read(position, target, io);
          if (status.isOk() && io.bytesTransferred() <= 0) status = StatusCode.IO_FAILURE;
          if (status.isOk()) position += io.bytesTransferred();
        }
      }
      if (status.isOk()) result.set(bytes);
    } finally {
      StatusCode close = file.close();
      if (status.isOk() && !close.isOk() && close != StatusCode.CLOSED) status = close;
      if (!status.isOk() && bytes != null) Arrays.fill(bytes, (byte) 0);
    }
    return status;
  }

  static StatusCode writeChild(RiverDirectory directory, String name, byte[] bytes) {
    RiverFileResult fileResult = new RiverFileResult();
    StatusCode status = directory.openFile(name, RiverOpenMode.CREATE_NEW, fileResult);
    if (!status.isOk()) return status;
    RiverFile file = fileResult.file();
    status = write(file, bytes);
    StatusCode close = file.close();
    return status.isOk() && !close.isOk() && close != StatusCode.CLOSED ? close : status;
  }

  static StatusCode write(RiverFile file, byte[] bytes) {
    ByteBuffer source = ByteBuffer.wrap(bytes);
    IoResult io = new IoResult();
    long position = 0;
    while (source.hasRemaining()) {
      StatusCode status = file.write(position, source, io);
      if (!status.isOk()) return status;
      if (io.bytesTransferred() <= 0) return StatusCode.IO_FAILURE;
      position += io.bytesTransferred();
    }
    return file.force(ForceMode.CONTENT_AND_METADATA);
  }
}
