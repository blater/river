package io.riverdb.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RowResult;
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
    ProtocolFrameHeader header = new ProtocolFrameHeader();
    ProtocolResponse response = new ProtocolResponse();
    CommandResult command = new CommandResult();
    char[] source = "catalog_table_identifier_longer_than_seven".toCharArray();
    char[] decoded = new char[CommandResult.MAXIMUM_TEXT_CHARACTERS];
    assertEquals(StatusCode.OK, command.reserve(2, source.length));
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
            new int[] {SqlTypeDescriptor.varchar(64), SqlTypeDescriptor.BIGINT},
            2));
    assertEquals(StatusCode.OK, command.setTextAt(0, source, 0, source.length));
    ByteBuffer bytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
    for (int index = 0; index < 10_000; index++) {
      exercise(codec, frame, header, response, command, bytes, decoded);
    }

    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 0; index < 100; index++) {
      exercise(codec, frame, header, response, command, bytes, decoded);
    }
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;
    assertTrue(allocated <= 512, "warmed protocol allocated bytes: " + allocated);
  }

  @Test
  void warmedContinuedMaximumRowPathReusesAssemblyStorage() {
    java.lang.management.ThreadMXBean standard = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standard instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standard;
    Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    bean.setThreadAllocatedMemoryEnabled(true);
    int columns = 1_664;
    long[] values = new long[columns];
    int[] descriptors = new int[columns];
    java.util.Arrays.fill(descriptors, SqlTypeDescriptor.BIGINT);
    CommandResult command = new CommandResult();
    assertEquals(StatusCode.OK, command.reserve(columns, 0));
    assertEquals(StatusCode.OK, command.complete(
        1, 0, false, true, 0, values, new long[26], 26, descriptors, columns));
    ProtocolFrameCodec codec = new ProtocolFrameCodec();
    ProtocolFrame frame = new ProtocolFrame();
    ProtocolResponse response = new ProtocolResponse();
    ByteBuffer bytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
    for (int index = 0; index < 1_000; index++) {
      exerciseWide(codec, frame, response, command, bytes);
    }
    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 0; index < 100; index++) {
      exerciseWide(codec, frame, response, command, bytes);
    }
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;
    assertTrue(allocated <= 512, "warmed continuation allocated bytes: " + allocated);
  }

  @Test
  void warmedQueryOpenPrefetchPathReusesMetadataAndRowStorage() {
    java.lang.management.ThreadMXBean standard = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standard instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standard;
    Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    bean.setThreadAllocatedMemoryEnabled(true);
    ProtocolFrameCodec codec = new ProtocolFrameCodec();
    ProtocolFrame frame = new ProtocolFrame();
    ProtocolResponse response = new ProtocolResponse();
    ProtocolQueryMetadata metadata = new ProtocolQueryMetadata();
    RiverQuery query = new AllocationQuery();
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, row.complete(
        3, new long[] {7, 9}, 0,
        new int[] {SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.BIGINT}, 2));
    ByteBuffer bytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
    for (int index = 0; index < 10_000; index++) {
      exerciseQueryOpen(codec, frame, response, metadata, query, row, bytes);
    }
    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 0; index < 100; index++) {
      exerciseQueryOpen(codec, frame, response, metadata, query, row, bytes);
    }
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;
    assertTrue(allocated <= 512, "warmed query open allocated bytes: " + allocated);
  }

  private static void exercise(
      ProtocolFrameCodec codec,
      ProtocolFrame frame,
      ProtocolFrameHeader header,
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
    allocationGuard += codec.inspectResponseHeader(bytes, header).ordinal();
    allocationGuard += header.payloadBytes();
    allocationGuard += codec.decodeResponse(bytes, frame, response).ordinal();
    allocationGuard += response.valueAt(1);
    allocationGuard += response.copyTextAt(0, decoded, 0);
  }

  private static void exerciseWide(ProtocolFrameCodec codec, ProtocolFrame frame,
      ProtocolResponse response, CommandResult command, ByteBuffer bytes) {
    allocationGuard += codec.encodeCommandResponse(bytes, ProtocolMessageType.EXECUTE,
        12, StatusCode.OK, command, false).ordinal();
    allocationGuard += codec.decodeResponse(bytes, frame, response).ordinal();
    allocationGuard += response.columnCount();
  }

  private static void exerciseQueryOpen(
      ProtocolFrameCodec codec,
      ProtocolFrame frame,
      ProtocolResponse response,
      ProtocolQueryMetadata metadata,
      RiverQuery query,
      RowResult row,
      ByteBuffer bytes) {
    allocationGuard += metadata.capture(query).ordinal();
    allocationGuard += codec.encodeQueryOpenResponse(
        bytes, ProtocolMessageType.BEGIN_QUERY, 13, StatusCode.OK,
        metadata, row, 1, null, true).ordinal();
    allocationGuard += codec.decodeResponse(bytes, frame, response).ordinal();
    allocationGuard += response.valueAt(1);
  }

  private static final class AllocationQuery implements RiverQuery {
    @Override public StatusCode next(RowResult result) { return StatusCode.CONFLICT; }
    @Override public StatusCode close(CommandResult result) { return StatusCode.CONFLICT; }
    @Override public boolean isActive() { return true; }
    @Override public int columnCount() { return 2; }
    @Override public CharSequence columnName(int index) { return index == 0 ? "id" : "value"; }
    @Override public int columnTypeDescriptor(int index) { return SqlTypeDescriptor.BIGINT; }
    @Override public boolean columnIsNullable(int index) { return index == 1; }
    @Override public long rowsReturned() { return 0; }
  }
}
