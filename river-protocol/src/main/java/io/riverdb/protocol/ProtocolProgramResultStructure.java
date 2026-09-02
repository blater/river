package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.TransactionProgramAction;
import io.riverdb.engine.api.TransactionProgramResult;
import java.nio.ByteBuffer;

/** Validates and publishes the step prefix of a program result payload. */
final class ProtocolProgramResultStructure {
  private ProtocolProgramResultStructure() { }

  static int validateSteps(
      ByteBuffer source, int input, int end, int steps, int rows, int cells) {
    int countedRows = 0;
    int countedCells = 0;
    for (int step = 0; step < steps; step++) {
      if (input > end - ProtocolProgramResultEncoder.STEP_BYTES) return -1;
      int programStep = source.getInt(input);
      int action = source.getInt(input + 4);
      int affected = source.getInt(input + 8);
      int rowCount = source.getInt(input + 12);
      if (programStep < 0 || !TransactionProgramAction.isValid(action)
          || affected < 0 || rowCount < 0) return -1;
      countedRows += rowCount;
      input += ProtocolProgramResultEncoder.STEP_BYTES;
      for (int row = 0; row < rowCount; row++) {
        if (input > end - ProtocolProgramResultEncoder.ROW_BYTES) return -1;
        int columns = source.getInt(input);
        if (columns < 0) return -1;
        if (columns > (end - input - ProtocolProgramResultEncoder.ROW_BYTES)
            / ProtocolProgramResultEncoder.VALUE_HEADER_BYTES) return -1;
        int next = ProtocolProgramResultRows.validate(source, input, end, 1, columns);
        if (next < 0) return -1;
        input = next;
        countedCells += columns;
      }
    }
    return countedRows == rows && countedCells == cells ? input : -1;
  }
}
