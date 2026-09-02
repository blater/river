package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.TransactionProgramResult;
import java.nio.ByteBuffer;

/** Common envelope admission and diagnostic-header emission for program results. */
final class ProtocolProgramResultHeader {
  private ProtocolProgramResultHeader() { }

  static boolean validTarget(
      ByteBuffer target, ProtocolMessageType type, long requestId,
      StatusCode status, TransactionProgramResult result) {
    return target != null && type != null && requestId > 0 && status != null
        && (result != null || !status.isOk());
  }

  static int steps(TransactionProgramResult result) { return result == null ? 0 : result.stepCount(); }
  static int rows(TransactionProgramResult result) { return result == null ? 0 : result.rowCount(); }
  static int cells(TransactionProgramResult result) {
    return result == null ? 0 : ProtocolProgramResultShape.cellCount(result);
  }

  static void write(
      ByteBuffer target, int offset, StatusCode outer, TransactionProgramResult result,
      int steps, int rows, int cells) {
    target.putInt(offset, ProtocolProgramResultEncoder.FORMAT);
    target.putInt(offset + 4, outer.stableCode());
    target.putInt(offset + 8, result != null && result.sessionFenced()
        ? ProtocolProgramResultEncoder.FLAG_FENCED : 0);
    target.putLong(offset + 12, result == null ? 0 : result.commitSequence());
    target.putInt(offset + 20, result == null ? -1 : result.failingStep());
    target.putInt(offset + 24, result == null ? outer.stableCode()
        : result.primaryStatus().stableCode());
    target.putInt(offset + 28, result == null ? StatusCode.OK.stableCode()
        : result.rollbackStatus().stableCode());
    target.putInt(offset + 32, steps);
    target.putInt(offset + 36, rows);
    target.putInt(offset + 40, cells);
    target.putInt(offset + 44, 0);
    for (int index = offset + 48; index < offset + ProtocolProgramResultEncoder.HEADER_BYTES;
        index++) target.put(index, (byte) 0);
  }
}
