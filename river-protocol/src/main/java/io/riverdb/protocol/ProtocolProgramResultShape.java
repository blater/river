package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.TransactionProgramAction;
import io.riverdb.engine.api.TransactionProgramResult;

/** Validates result shape before encoding and counts its cells once. */
final class ProtocolProgramResultShape {
  private ProtocolProgramResultShape() { }

  static boolean valid(
      TransactionProgramResult result, StatusCode outer, int steps, int rows, int cells) {
    if (result == null) return !outer.isOk();
    if (result.commitSequence() < 0 || result.failingStep() < -1
        || result.primaryStatus() == null
        || result.rollbackStatus() == null) return false;
    int countedRows = 0;
    int countedCells = 0;
    for (int step = 0; step < steps; step++) {
      if (result.programStep(step) < 0
          || !TransactionProgramAction.isValid(result.action(step))
          || result.affectedRows(step) < 0 || result.rowCount(step) < 0) return false;
      countedRows += result.rowCount(step);
    }
    for (int row = 0; row < rows; row++) {
      int columns = result.columnCount(row);
      if (columns < 0) return false;
      countedCells += columns;
      for (int column = 0; column < columns; column++) {
        if (ProtocolProgramResultValueCodec.bytes(result, row, column) < 0) return false;
      }
    }
    return countedRows == rows && countedCells == cells;
  }

  static int cellCount(TransactionProgramResult result) {
    int cells = 0;
    for (int row = 0; row < result.rowCount(); row++) cells += result.columnCount(row);
    return cells;
  }

  static long payloadBytes(TransactionProgramResult result, int steps, int rows) {
    long bytes = ProtocolProgramResultEncoder.HEADER_BYTES
        + (long) steps * ProtocolProgramResultEncoder.STEP_BYTES
        + (long) rows * ProtocolProgramResultEncoder.ROW_BYTES;
    for (int row = 0; row < rows; row++) {
      int columns = result.columnCount(row);
      for (int column = 0; column < columns; column++) {
        bytes += ProtocolProgramResultEncoder.VALUE_HEADER_BYTES
            + ProtocolProgramResultValueCodec.bytes(result, row, column);
      }
    }
    return bytes;
  }
}
