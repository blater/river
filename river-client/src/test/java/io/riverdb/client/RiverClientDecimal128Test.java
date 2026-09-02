package io.riverdb.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.protocol.ProtocolFrame;
import io.riverdb.protocol.ProtocolFrameCodec;
import io.riverdb.protocol.ProtocolMessageType;
import io.riverdb.protocol.ProtocolResponse;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class RiverClientDecimal128Test {
  @Test
  void copiesBothDecimalWordsFromDecodedResponseIntoPublicResult() {
    long high = 669_260_594_276_348_691L;
    long low = -4_302_749_291_975_740_594L;
    int descriptor = SqlTypeDescriptor.decimal(38, 6);
    CommandResult source = new CommandResult();
    assertEquals(StatusCode.OK, source.complete(
        1, 1, false, true, 0,
        new long[] {high}, new long[] {low}, new long[1], 1,
        new int[] {descriptor}, 1));
    ProtocolFrameCodec codec = new ProtocolFrameCodec();
    ByteBuffer bytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
    assertEquals(StatusCode.OK, codec.encodeCommandResponse(
        bytes, ProtocolMessageType.EXECUTE, 1, StatusCode.OK, source, false));
    ProtocolResponse response = new ProtocolResponse();
    assertEquals(StatusCode.OK,
        codec.decodeResponse(bytes, new ProtocolFrame(), response));

    CommandResult target = new CommandResult();
    assertEquals(StatusCode.OK,
        new RiverClientResultWorkspace().copyCommand(response, target));
    assertEquals(high, target.decimalUnscaledHighAt(0));
    assertEquals(low, target.decimalUnscaledLowAt(0));
  }

  @Test
  void failedWorkspaceGrowthPublishesNoPartialArray() {
    RiverClientResultAllocator failSecondLong = new RiverClientResultAllocator() {
      private int longAllocations;
      private boolean failed;

      @Override public long[] copy(long[] source, int capacity) {
        if (!failed && ++longAllocations == 2) {
          failed = true;
          throw new OutOfMemoryError("injected");
        }
        return java.util.Arrays.copyOf(source, capacity);
      }

      @Override public int[] copy(int[] source, int capacity) {
        return java.util.Arrays.copyOf(source, capacity);
      }
    };
    RiverClientResultWorkspace workspace =
        new RiverClientResultWorkspace(failSecondLong);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, workspace.reserve(9));
    assertEquals(8, workspace.retainedColumns());
    assertEquals(StatusCode.OK, workspace.reserve(9));
    assertEquals(16, workspace.retainedColumns());
  }
}
