package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.RowResult;
import java.nio.ByteBuffer;

/** Byte bounds and continuation marker for query response batches. */
public final class ProtocolRowBatch {
  private ProtocolRowBatch() { }

  /** Exact wire size of one staged FETCH row, including continuation framing. */
  public static int rowWireBytes(RowResult row) {
    if (row == null || !row.isAvailable()) return -1;
    int columns = row.columnCount();
    int values = ProtocolResponseValueEncoder.bytes(null, row, columns);
    if (values < 0) return -1;
    int payload = ProtocolResponseFrameWriter.FIXED_BYTES
        + ProtocolResponseValueEncoder.nullBitmapBytes(columns)
        + columns * Integer.BYTES + values;
    if (payload <= ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES) {
      return ProtocolFrameCodec.HEADER_BYTES + payload;
    }
    return ProtocolContinuationLimits.wireBytes(
        payload, ProtocolFrameCodec.MAXIMUM_LOGICAL_RESPONSE_PAYLOAD_BYTES);
  }

  /** The caller owns the bounded response buffer and has reserved the next row. */
  public static void markMore(ByteBuffer response, int frameOffset) {
    int offset = frameOffset + ProtocolFrameCodec.HEADER_BYTES + Integer.BYTES;
    response.putInt(offset, response.getInt(offset) | ProtocolFrameCodec.FLAG_BATCH_MORE);
  }

  static boolean validFlags(ProtocolMessageType type, StatusCode status, int flags) {
    if ((flags & ProtocolFrameCodec.FLAG_BATCH_MORE) == 0) return true;
    if (!status.isOk()) return false;
    if ((flags & ProtocolFrameCodec.FLAG_ROW_AVAILABLE) == 0) return false;
    if ((flags & ProtocolFrameCodec.FLAG_QUERY_ACTIVE) == 0) return false;
    if ((flags & ProtocolFrameCodec.FLAG_END_OF_STREAM) != 0) return false;
    return type == ProtocolMessageType.BEGIN_QUERY
        || type == ProtocolMessageType.BEGIN_PREPARED_QUERY
        || type == ProtocolMessageType.FETCH;
  }
}
