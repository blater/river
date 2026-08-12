package io.riverdb.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.CommandResult;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class ProtocolFrameCodecAllocationTest {
  private static volatile long allocationGuard;

  @Test
  void warmedFrameAndResponsePathReusesCallerOwnedStorage() {
    java.lang.management.ThreadMXBean standard = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standard instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standard;
    Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    bean.setThreadAllocatedMemoryEnabled(true);

    ProtocolFrameCodec codec = new ProtocolFrameCodec();
    ProtocolFrame frame = new ProtocolFrame();
    ProtocolResponse response = new ProtocolResponse();
    CommandResult command = new CommandResult();
    char[] source = "catalog_table_identifier_longer_than_seven".toCharArray();
    char[] decoded = new char[CommandResult.MAXIMUM_TEXT_CHARACTERS];
    assertEquals(
        StatusCode.OK,
        command.complete(
            1,
            7,
            false,
            true,
            3,
            new long[] {0, 10},
            0,
            new int[] {SqlTypeDescriptor.varchar(7), SqlTypeDescriptor.BIGINT},
            2));
    assertEquals(StatusCode.OK, command.setTextAt(0, source, 0, source.length));
    ByteBuffer bytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
    for (int index = 0; index < 10_000; index++) {
      exercise(codec, frame, response, command, bytes, decoded);
    }

    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 0; index < 100; index++) {
      exercise(codec, frame, response, command, bytes, decoded);
    }
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;
    assertTrue(allocated <= 512, "warmed protocol allocated bytes: " + allocated);
  }

  private static void exercise(
      ProtocolFrameCodec codec,
      ProtocolFrame frame,
      ProtocolResponse response,
      CommandResult command,
      ByteBuffer bytes,
      char[] decoded) {
    allocationGuard += codec.encodeCommandResponse(
        bytes,
        ProtocolMessageType.EXECUTE,
        11,
        StatusCode.OK,
        command,
        false).ordinal();
    allocationGuard += codec.decodeResponse(bytes, frame, response).ordinal();
    allocationGuard += response.valueAt(1);
    allocationGuard += response.copyTextAt(0, decoded, 0);
  }
}
