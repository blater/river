package io.riverdb.protocol;

import io.riverdb.engine.api.TransactionProgramResult;
import java.nio.ByteBuffer;

/** Writes result steps, rows, and cells after shape admission. */
final class ProtocolProgramResultWriter {
  private ProtocolProgramResultWriter() { }

  static int write(
      ByteBuffer target, int offset, TransactionProgramResult result, int steps) {
    for (int step = 0; step < steps; step++) {
      target.putInt(offset, result.programStep(step));
      target.putInt(offset + 4, result.action(step));
      target.putInt(offset + 8, result.affectedRows(step));
      target.putInt(offset + 12, result.rowCount(step));
      offset += ProtocolProgramResultEncoder.STEP_BYTES;
      int firstRow = result.firstRow(step);
      for (int row = firstRow; row < firstRow + result.rowCount(step); row++) {
        int columns = result.columnCount(row);
        target.putInt(offset, columns);
        target.putInt(offset + 4, 0);
        offset += ProtocolProgramResultEncoder.ROW_BYTES;
        for (int column = 0; column < columns; column++) {
          offset = ProtocolProgramResultValueCodec.write(target, offset, result, row, column);
        }
      }
    }
    return offset;
  }
}
