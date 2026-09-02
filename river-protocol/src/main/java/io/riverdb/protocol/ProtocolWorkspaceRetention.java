package io.riverdb.protocol;

import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.TransactionProgramResult;

/** Shared two-tier policy: retain one physical frame of warm workspace, shed larger bursts. */
public final class ProtocolWorkspaceRetention {
  private ProtocolWorkspaceRetention() { }

  public static long warmCeilingBytes() {
    return ProtocolFrameCodec.MAXIMUM_FRAME_BYTES;
  }

  public static boolean shouldShed(long retainedBytes) {
    return retainedBytes > warmCeilingBytes();
  }

  public static long maximumProgramRequestRetainedBytes() {
    int bytes = ProtocolFrameCodec.MAXIMUM_LOGICAL_REQUEST_PAYLOAD_BYTES;
    int graphBytes = bytes - ProtocolProgramGraphCodec.HEADER_BYTES;
    long graph = TransactionProgram.maximumRetainedBytes(
        graphBytes / ProtocolProgramGraphCodec.STEP_BYTES,
        graphBytes / ProtocolProgramGraphCodec.EXPRESSION_BYTES,
        graphBytes / Integer.BYTES,
        graphBytes / ProtocolProgramGraphCodec.EXPRESSION_BYTES,
        graphBytes / ProtocolProgramGraphCodec.NODE_BYTES);
    int argumentBytes = bytes - Long.BYTES - Integer.BYTES * 2;
    int slots = argumentBytes / ProtocolParameterDecoder.HEADER_BYTES;
    long arguments = TransactionProgramArguments.maximumRetainedBytes(slots, argumentBytes);
    long text = ProtocolProgramTextDecoder.maximumRetainedBytes(
        Math.min(argumentBytes, 0xffff));
    return Math.max(graph, add(arguments, text));
  }

  public static long maximumProgramResultRetainedBytes() {
    int bytes = ProtocolFrameCodec.MAXIMUM_LOGICAL_RESPONSE_PAYLOAD_BYTES
        - ProtocolProgramResultEncoder.HEADER_BYTES;
    return TransactionProgramResult.maximumRetainedBytes(
        bytes / ProtocolProgramResultEncoder.STEP_BYTES,
        bytes / ProtocolProgramResultEncoder.ROW_BYTES,
        bytes / ProtocolProgramResultEncoder.VALUE_HEADER_BYTES,
        bytes);
  }

  private static long add(long left, long right) {
    return left < 0 || right < 0 || left > Long.MAX_VALUE - right ? -1 : left + right;
  }
}
