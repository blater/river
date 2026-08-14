package io.riverdb.inspect;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import java.nio.ByteBuffer;

/** Reusable exact-read state for one currently inspected file. */
final class OfflineInspectionFile {
  private final DirectoryOperationResult operation =
      new DirectoryOperationResult();
  private final FileSizeResult size = new FileSizeResult();
  private final IoResult io = new IoResult();
  private DurableFile file;

  StatusCode open(NioDurableDirectory directory, String name) {
    file = null;
    operation.reset();
    StatusCode status = directory.reopen(name, operation);
    if (status == StatusCode.CONFLICT) {
      return StatusCode.CORRUPTION;
    }
    if (status.isOk()) {
      file = operation.file();
    }
    return status;
  }

  StatusCode readSize() {
    return file.size(size);
  }

  long sizeBytes() {
    return size.sizeBytes();
  }

  StatusCode requireSize(long expected) {
    StatusCode status = readSize();
    return status.isOk() && sizeBytes() != expected
        ? StatusCode.CORRUPTION : status;
  }

  StatusCode read(long offset, ByteBuffer target) {
    int expected = target.remaining();
    StatusCode status = file.read(offset, target, io);
    return status.isOk() && io.bytesTransferred() != expected
        ? StatusCode.CORRUPTION : status;
  }

  StatusCode close(StatusCode body) {
    if (file == null) {
      return body;
    }
    StatusCode close = file.close();
    file = null;
    return body.isOk() ? close : body;
  }
}
