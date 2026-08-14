package io.riverdb.backup;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;

/** Exact-length durable-file operations shared by backup owners. */
final class OfflineBackupIo {
  private OfflineBackupIo() { }

  static StatusCode readExact(
      DurableFile file, ByteBuffer target, long position, IoResult result) {
    int expected = target.remaining();
    StatusCode status = file.read(position, target, result);
    return status.isOk() && result.bytesTransferred() != expected
        ? StatusCode.CORRUPTION : status;
  }

  static StatusCode writeExact(
      DurableFile file, ByteBuffer source, long position, IoResult result) {
    int expected = source.remaining();
    StatusCode status = file.write(position, source, result);
    return status.isOk() && result.bytesTransferred() != expected
        ? StatusCode.IO_FAILURE : status;
  }
}
