package io.riverdb.inspect;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.control.ControlFile;
import io.riverdb.format.control.ControlFileCodec;
import io.riverdb.format.control.ControlFileDecodeResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import java.nio.ByteBuffer;

/** Validates the database control authority. */
final class OfflineControlInspector {
  static final String FILE_NAME = "river.control";

  private final OfflineInspectionFile file;
  private final ControlFileDecodeResult decoded = new ControlFileDecodeResult();
  private final ByteBuffer bytes =
      ByteBuffer.allocateDirect(ControlFileCodec.RECORD_BYTES);

  OfflineControlInspector(OfflineInspectionFile inspectedFile) {
    file = inspectedFile;
  }

  StatusCode inspect(
      NioDurableDirectory directory, DatabaseInspectionResult result) {
    StatusCode status = file.open(directory, FILE_NAME);
    if (status.isOk()) {
      status = file.requireSize(ControlFileCodec.RECORD_BYTES);
    }
    if (status.isOk()) {
      bytes.clear();
      status = file.read(0, bytes);
    }
    if (status.isOk()) {
      bytes.flip();
      status = ControlFileCodec.decode(bytes, decoded);
    }
    if (status.isOk()) {
      ControlFile control = decoded.controlFile();
      result.setDatabase(control.databaseIncarnation());
      result.addControlBytes(ControlFileCodec.RECORD_BYTES);
    }
    return file.close(status);
  }
}
