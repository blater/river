package io.riverdb.engine.control;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.control.ControlFile;
import io.riverdb.format.control.ControlFileCodec;
import io.riverdb.format.control.ControlFileDecodeResult;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;

/** Creates and opens the durable control record for one database directory. */
public final class DatabaseControlStore {
  public static final String CONTROL_FILE_NAME = "river.control";
  public static final String TEMPORARY_FILE_NAME = "river.control.new";

  private DatabaseControlStore() {
  }

  public static StatusCode create(
      DurableDirectory directory,
      ControlFile controlFile,
      DatabaseControlResult result) {
    if (directory == null || controlFile == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();

    ByteBuffer bytes = ByteBuffer.allocate(ControlFileCodec.RECORD_BYTES);
    StatusCode status = ControlFileCodec.encode(controlFile, bytes);
    if (!status.isOk()) {
      return status;
    }
    bytes.flip();

    DirectoryOperationResult operation = new DirectoryOperationResult();
    status = directory.createTemporary(TEMPORARY_FILE_NAME, operation);
    if (!status.isOk()) {
      return status;
    }
    DurableFile temporary = operation.file();
    IoResult io = new IoResult();
    status = temporary.write(0, bytes, io);
    if (status.isOk() && io.bytesTransferred() != ControlFileCodec.RECORD_BYTES) {
      status = StatusCode.IO_FAILURE;
    }
    if (status.isOk()) {
      status = temporary.force(ForceMode.CONTENT_AND_METADATA);
    }
    StatusCode closeStatus = temporary.close();
    if (status.isOk() && !closeStatus.isOk()) {
      status = closeStatus;
    }
    if (!status.isOk()) {
      return status;
    }

    status = directory.rename(TEMPORARY_FILE_NAME, CONTROL_FILE_NAME, operation);
    if (!status.isOk()) {
      return status;
    }
    status = directory.force(operation);
    if (!status.isOk()) {
      return status;
    }
    result.set(controlFile);
    return StatusCode.OK;
  }

  public static StatusCode open(DurableDirectory directory, DatabaseControlResult result) {
    if (directory == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();

    DirectoryOperationResult operation = new DirectoryOperationResult();
    StatusCode status = directory.reopen(CONTROL_FILE_NAME, operation);
    if (!status.isOk()) {
      return status;
    }
    DurableFile file = operation.file();
    FileSizeResult size = new FileSizeResult();
    status = file.size(size);
    if (status.isOk() && size.sizeBytes() != ControlFileCodec.RECORD_BYTES) {
      status = StatusCode.CORRUPTION;
    }

    ByteBuffer bytes = ByteBuffer.allocate(ControlFileCodec.RECORD_BYTES);
    IoResult io = new IoResult();
    if (status.isOk()) {
      status = file.read(0, bytes, io);
      if (status.isOk() && io.bytesTransferred() != ControlFileCodec.RECORD_BYTES) {
        status = StatusCode.CORRUPTION;
      }
    }
    StatusCode closeStatus = file.close();
    if (status.isOk() && !closeStatus.isOk()) {
      status = closeStatus;
    }
    if (!status.isOk()) {
      return status;
    }

    bytes.flip();
    ControlFileDecodeResult decoded = new ControlFileDecodeResult();
    status = ControlFileCodec.decode(bytes, decoded);
    if (status.isOk()) {
      result.set(decoded.controlFile());
    }
    return status;
  }
}
