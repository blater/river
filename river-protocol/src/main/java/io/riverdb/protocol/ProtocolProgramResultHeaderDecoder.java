package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Reusable parser for the fixed program-result diagnostic envelope. */
final class ProtocolProgramResultHeaderDecoder {
  private StatusCode outer;
  private StatusCode primary;
  private StatusCode rollback;
  private int flags;
  private long commit;
  private int failing;
  private int steps;
  private int rows;
  private int cells;

  StatusCode decode(ByteBuffer source, int offset, int end) {
    reset();
    if (source == null || offset < 0 || end < offset
        || end > source.limit() || end - offset < ProtocolProgramResultEncoder.HEADER_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (source.getInt(offset) != ProtocolProgramResultEncoder.FORMAT
        || source.getInt(offset + 44) != 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    for (int index = offset + 48;
        index < offset + ProtocolProgramResultEncoder.HEADER_BYTES; index++) {
      if (source.get(index) != 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    outer = ProtocolResponsePayloadDecoder.statusFromStableCode(source.getInt(offset + 4));
    flags = source.getInt(offset + 8);
    commit = source.getLong(offset + 12);
    failing = source.getInt(offset + 20);
    primary = ProtocolResponsePayloadDecoder.statusFromStableCode(source.getInt(offset + 24));
    rollback = ProtocolResponsePayloadDecoder.statusFromStableCode(source.getInt(offset + 28));
    steps = source.getInt(offset + 32);
    rows = source.getInt(offset + 36);
    cells = source.getInt(offset + 40);
    return valid() ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  StatusCode outer() { return outer; }
  StatusCode primary() { return primary; }
  StatusCode rollback() { return rollback; }
  int flags() { return flags; }
  long commit() { return commit; }
  int failing() { return failing; }
  int steps() { return steps; }
  int rows() { return rows; }
  int cells() { return cells; }

  void reset() {
    outer = null;
    primary = null;
    rollback = null;
    flags = 0;
    commit = 0;
    failing = -1;
    steps = 0;
    rows = 0;
    cells = 0;
  }

  private boolean valid() {
    return outer != null && primary != null && rollback != null
        && (flags & ~ProtocolProgramResultEncoder.FLAG_FENCED) == 0
        && commit >= 0 && steps >= 0 && rows >= 0 && cells >= 0
        && failing >= -1;
  }
}
