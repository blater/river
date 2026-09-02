package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class PendingMutationBufferTest {
  private static volatile long allocationGuard;

  @Test
  void growsPackedRowsAndRetainsOffsetsAcrossCompaction() {
    PendingMutationBuffer mutations = new PendingMutationBuffer(4, Long.BYTES);
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);

    put(row, 11);
    assertEquals(StatusCode.OK, mutations.reserve(1, Long.BYTES));
    mutations.append(IndexedWalCodec.MUTATION_INSERT, 1, 1, 0, row, 0, Long.BYTES);
    assertEquals(StatusCode.OK, mutations.reserve(1, Long.BYTES));
    put(row, 22);
    mutations.append(IndexedWalCodec.MUTATION_INSERT, 1, 2, 0, row, 0, Long.BYTES);
    assertEquals(StatusCode.OK, mutations.reserve(1, Long.BYTES));
    put(row, 33);
    mutations.append(IndexedWalCodec.MUTATION_UPDATE, 1, 1, 1, row, 0, Long.BYTES);
    assertEquals(StatusCode.OK, mutations.reserve(1, Long.BYTES));
    put(row, 44);
    mutations.append(IndexedWalCodec.MUTATION_INSERT, 1, 3, 0, row, 0, Long.BYTES);

    mutations.compact();

    assertEquals(3, mutations.count());
    assertEquals(2, mutations.keyAt(0));
    assertEquals(1, mutations.keyAt(1));
    assertEquals(3, mutations.keyAt(2));
    assertEquals(22, copiedValue(mutations, 0));
    assertEquals(33, copiedValue(mutations, 1));
    assertEquals(44, copiedValue(mutations, 2));
  }

  @Test
  void truncateReusesRetainedArenaAndRejectsExcessReservation() {
    PendingMutationBuffer mutations = new PendingMutationBuffer(2, Long.BYTES);
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    put(row, 51);
    assertEquals(StatusCode.OK, mutations.reserve(1, Long.BYTES));
    mutations.append(IndexedWalCodec.MUTATION_INSERT, 1, 5, 0, row, 0, Long.BYTES);
    assertEquals(StatusCode.OK, mutations.reserve(1, Long.BYTES));
    put(row, 61);
    mutations.append(IndexedWalCodec.MUTATION_INSERT, 1, 6, 0, row, 0, Long.BYTES);

    mutations.truncate(1);
    assertEquals(1, mutations.count());
    assertEquals(51, copiedValue(mutations, 0));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, mutations.reserve(2, Long.BYTES));
    assertEquals(StatusCode.OK, mutations.reserve(1, Long.BYTES));
    put(row, 71);
    mutations.append(IndexedWalCodec.MUTATION_UPDATE, 1, 7, 0, row, 0, Long.BYTES);
    assertEquals(71, copiedValue(mutations, 1));
    assertTrue(mutations.containsNonInsertMutation());
  }

  @Test
  void latestIndexTracksDuplicatesAndRevealsEarlierMutationAfterTruncate() {
    PendingMutationBuffer mutations = new PendingMutationBuffer(12, 1);
    ByteBuffer row = ByteBuffer.allocateDirect(1);

    append(mutations, row, 3, 11, 1);
    append(mutations, row, 4, 12, 2);
    append(mutations, row, 3, 11, 3);
    append(mutations, row, 3, 13, 4);
    for (int index = 4; index < 9; index++) {
      append(mutations, row, 8, 20 + index, index);
    }

    assertEquals(2, mutations.findLatestIndex(3, 11));
    assertEquals(1, mutations.findLatestIndex(4, 12));
    assertEquals(3, mutations.findLatestIndex(3, 13));
    assertEquals(-1, mutations.findLatestIndex(4, 11));

    mutations.truncate(2);

    assertEquals(0, mutations.findLatestIndex(3, 11));
    assertEquals(1, mutations.findLatestIndex(4, 12));
    assertEquals(-1, mutations.findLatestIndex(3, 13));
  }

  @Test
  void pendingScanUsesOrderedLatestResourcesWithinCompositeBounds() {
    PendingMutationBuffer mutations = new PendingMutationBuffer(12, 1);
    ByteBuffer row = ByteBuffer.allocateDirect(1);
    append(mutations, row, 3, 8, 1);
    append(mutations, row, 2, 20, 2);
    append(mutations, row, 3, 10, 3);
    append(mutations, row, 3, 8, 4);
    append(mutations, row, 4, 1, 5);
    IndexedScanCursor cursor = new IndexedScanCursor();
    assertEquals(StatusCode.OK, cursor.claim(null, 0, 2, 21, 4, 1, 1));

    int first = mutations.nextIndex(cursor);
    assertEquals(3, first);
    cursor.returned(mutations.spaceAt(first), mutations.keyAt(first));
    int second = mutations.nextIndex(cursor);
    assertEquals(2, second);
    cursor.returned(mutations.spaceAt(second), mutations.keyAt(second));
    assertEquals(-1, mutations.nextIndex(cursor));
  }

  @Test
  void compactsVariableRowsAcrossDeletionAndDiscardedLatestMutation() {
    PendingMutationBuffer mutations = new PendingMutationBuffer(6, 8);
    ByteBuffer row = ByteBuffer.allocateDirect(8);
    putBytes(row, 1, 2, 3);
    assertEquals(StatusCode.OK, mutations.reserve(1, 3));
    mutations.append(IndexedWalCodec.MUTATION_INSERT, 1, 1, 0, row, 0, 3);
    assertEquals(StatusCode.OK, mutations.reserve(1, 1));
    mutations.appendDeletion(IndexedTransactionSession.MUTATION_NONE, 1, 1, 0);
    assertEquals(StatusCode.OK, mutations.reserve(1, 5));
    putBytes(row, 10, 11, 12, 13, 14);
    mutations.append(IndexedWalCodec.MUTATION_INSERT, 1, 2, 0, row, 0, 5);
    assertEquals(StatusCode.OK, mutations.reserve(1, 8));
    putBytes(row, 20, 21, 22, 23, 24, 25, 26, 27);
    mutations.append(IndexedWalCodec.MUTATION_INSERT, 1, 3, 0, row, 0, 8);
    assertEquals(StatusCode.OK, mutations.reserve(1, 2));
    putBytes(row, 30, 31);
    mutations.append(IndexedWalCodec.MUTATION_UPDATE, 1, 2, 2, row, 0, 2);

    mutations.compact();

    assertEquals(2, mutations.count());
    assertEquals(3, mutations.keyAt(0));
    assertEquals(2, mutations.keyAt(1));
    assertEquals(0, mutations.findLatestIndex(1, 3));
    assertEquals(1, mutations.findLatestIndex(1, 2));
    assertEquals(-1, mutations.findLatestIndex(1, 1));
    assertRow(mutations, 0, 20, 21, 22, 23, 24, 25, 26, 27);
    assertRow(mutations, 1, 30, 31);

    mutations.truncate(1);
    assertEquals(StatusCode.OK, mutations.reserve(1, 4));
    putBytes(row, 40, 41, 42, 43);
    mutations.append(IndexedWalCodec.MUTATION_UPDATE, 1, 4, 3, row, 0, 4);
    assertRow(mutations, 0, 20, 21, 22, 23, 24, 25, 26, 27);
    assertRow(mutations, 1, 40, 41, 42, 43);
  }

  @Test
  void crossesLazyChunkBoundaryWithoutMovingRetainedRows() {
    PendingMutationBuffer mutations = new PendingMutationBuffer(10, 4);
    ByteBuffer row = ByteBuffer.allocateDirect(4);

    for (int key = 1; key <= 10; key++) {
      putBytes(row, key, key + 1, key + 2, key + 3);
      assertEquals(StatusCode.OK, mutations.reserve(1, 4));
      mutations.append(IndexedWalCodec.MUTATION_INSERT, 1, key, 0, row, 0, 4);
    }

    assertEquals(StatusCode.RESOURCE_EXHAUSTED, mutations.reserve(1, 4));
    assertRow(mutations, 0, 1, 2, 3, 4);
    assertRow(mutations, 7, 8, 9, 10, 11);
    assertRow(mutations, 8, 9, 10, 11, 12);
    assertRow(mutations, 9, 10, 11, 12, 13);

    mutations.truncate(8);
    putBytes(row, 40, 41);
    assertEquals(StatusCode.OK, mutations.reserve(1, 2));
    mutations.append(IndexedWalCodec.MUTATION_UPDATE, 1, 40, 8, row, 0, 2);
    assertRow(mutations, 8, 40, 41);
  }

  @Test
  void batchPreflightRetainsAllocatedHighWaterAfterPressure() {
    FailingChunkAllocator allocator = new FailingChunkAllocator(2);
    PendingMutationBuffer mutations = new PendingMutationBuffer(10, 4, allocator);
    int[] rowLengths = {4, 4, 4, 4, 4, 4, 4, 4, 4};

    assertEquals(StatusCode.RESOURCE_EXHAUSTED, mutations.reserve(rowLengths, 0, 9));
    assertEquals(0, mutations.count());
    allocator.allowAllocations();
    assertEquals(StatusCode.OK, mutations.reserve(rowLengths, 0, 9));

    ByteBuffer row = ByteBuffer.allocateDirect(4);
    for (int key = 1; key <= 9; key++) {
      putBytes(row, key, key, key, key);
      assertEquals(StatusCode.OK, mutations.reserve(1, 4));
      mutations.append(IndexedWalCodec.MUTATION_INSERT, 1, key, 0, row, 0, 4);
    }
    assertRow(mutations, 8, 9, 9, 9, 9);
  }

  @Test
  void compactionMovesSecondChunkRowsAndEndsExactlyAtBoundary() {
    PendingMutationBuffer mutations = new PendingMutationBuffer(12, 4);
    ByteBuffer row = ByteBuffer.allocateDirect(4);
    for (int key = 1; key <= 8; key++) {
      putBytes(row, key, key, key, key);
      assertEquals(StatusCode.OK, mutations.reserve(1, 4));
      mutations.append(IndexedWalCodec.MUTATION_INSERT, 1, key, 0, row, 0, 4);
    }
    for (int key = 1; key <= 4; key++) {
      putBytes(row, key + 20, key + 20, key + 20, key + 20);
      assertEquals(StatusCode.OK, mutations.reserve(1, 4));
      mutations.append(IndexedWalCodec.MUTATION_UPDATE, 1, key, key, row, 0, 4);
    }

    mutations.compact();

    assertEquals(8, mutations.count());
    assertRow(mutations, 0, 5, 5, 5, 5);
    assertRow(mutations, 3, 8, 8, 8, 8);
    assertRow(mutations, 4, 21, 21, 21, 21);
    assertRow(mutations, 7, 24, 24, 24, 24);
    mutations.truncate(8);
    putBytes(row, 31, 31, 31, 31);
    assertEquals(StatusCode.OK, mutations.reserve(1, 4));
    mutations.append(IndexedWalCodec.MUTATION_INSERT, 1, 31, 0, row, 0, 4);
    assertRow(mutations, 8, 31, 31, 31, 31);
  }

  @Test
  void warmedSecondChunkAppendAndReuseDoesNotAllocatePerRow() {
    ThreadMXBean bean = allocationBean();
    PendingMutationBuffer mutations = new PendingMutationBuffer(10, 4);
    ByteBuffer row = ByteBuffer.allocateDirect(4);
    int[] lengths = {4, 4, 4, 4, 4, 4, 4, 4, 4};
    assertEquals(StatusCode.OK, mutations.reserve(lengths, 0, lengths.length));

    for (int iteration = 0; iteration < 1_000; iteration++) {
      appendNineRows(mutations, row);
    }
    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    for (int iteration = 0; iteration < 1_000; iteration++) {
      appendNineRows(mutations, row);
    }
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;

    assertTrue(allocated <= 256, "warmed two-chunk row arena allocated bytes: " + allocated);
  }

  @Test
  void indexesLazyMetadataAcrossLegacyCapacityBoundary() {
    int capacity = 513;
    PendingMutationBuffer mutations = new PendingMutationBuffer(capacity, 1);
    int[] lengths = new int[capacity];
    java.util.Arrays.fill(lengths, 1);
    assertEquals(StatusCode.OK, mutations.reserve(lengths, 0, capacity));
    ByteBuffer row = ByteBuffer.allocateDirect(1);
    for (int index = 0; index < capacity; index++) {
      row.put(0, (byte) index);
      mutations.append(
          index == 384 ? IndexedWalCodec.MUTATION_UPDATE : IndexedWalCodec.MUTATION_INSERT,
          7, index + 1L, index, row, 0, 1);
    }

    assertEquals(capacity, mutations.count());
    assertEquals(256, mutations.keyAt(255));
    assertEquals(257, mutations.keyAt(256));
    assertEquals(384, mutations.previousRowIdAt(384));
    assertEquals(385, mutations.keyAt(384));
    assertEquals(IndexedWalCodec.MUTATION_UPDATE, mutations.operationAt(384));
    assertEquals(0, mutations.findLatestIndex(7, 1));
    assertEquals(384, mutations.findLatestIndex(7, 385));
    assertEquals(512, mutations.findLatestIndex(7, 513));
    assertEquals(513, mutations.keyAt(512));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, mutations.reserve(1, 1));
    PendingMutationBuffer enterprise = new PendingMutationBuffer(Integer.MAX_VALUE, 1);
    assertEquals(Integer.MAX_VALUE, enterprise.capacity());
  }

  @Test
  void warmedMultiMetadataChunkReuseDoesNotAllocatePerRow() {
    ThreadMXBean bean = allocationBean();
    PendingMutationBuffer mutations = new PendingMutationBuffer(385, 1);
    int[] lengths = new int[385];
    java.util.Arrays.fill(lengths, 1);
    assertEquals(StatusCode.OK, mutations.reserve(lengths, 0, lengths.length));
    ByteBuffer row = ByteBuffer.allocateDirect(1);
    for (int iteration = 0; iteration < 1_000; iteration++) {
      appendRows(mutations, row, lengths.length);
    }
    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    for (int iteration = 0; iteration < 1_000; iteration++) {
      appendRows(mutations, row, lengths.length);
    }
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;
    assertTrue(allocated <= 256, "warmed metadata chunks allocated bytes: " + allocated);
  }

  private static void put(ByteBuffer row, long value) {
    row.putLong(0, value);
  }

  private static long copiedValue(PendingMutationBuffer mutations, int index) {
    ByteBuffer copied = ByteBuffer.allocateDirect(Long.BYTES);
    mutations.copyRowTo(index, copied, 0);
    return copied.getLong(0);
  }

  private static void putBytes(ByteBuffer row, int... values) {
    for (int index = 0; index < values.length; index++) {
      row.put(index, (byte) values[index]);
    }
  }

  private static void assertRow(
      PendingMutationBuffer mutations,
      int index,
      int... expected) {
    assertEquals(expected.length, mutations.rowLengthAt(index));
    ByteBuffer copied = ByteBuffer.allocateDirect(expected.length);
    mutations.copyRowTo(index, copied, 0);
    for (int offset = 0; offset < expected.length; offset++) {
      assertEquals(expected[offset], Byte.toUnsignedInt(copied.get(offset)));
    }
  }

  private static void appendNineRows(PendingMutationBuffer mutations, ByteBuffer row) {
    mutations.truncate(0);
    for (int key = 1; key <= 9; key++) {
      row.putInt(0, key);
      allocationGuard += mutations.reserve(1, 4).stableCode();
      mutations.append(IndexedWalCodec.MUTATION_INSERT, 1, key, 0, row, 0, 4);
    }
    allocationGuard += mutations.count();
  }

  private static void appendRows(
      PendingMutationBuffer mutations, ByteBuffer row, int count) {
    mutations.truncate(0);
    for (int index = 0; index < count; index++) {
      row.put(0, (byte) index);
      allocationGuard += mutations.reserve(1, 1).stableCode();
      mutations.append(
          IndexedWalCodec.MUTATION_INSERT, 1, index + 1L, 0, row, 0, 1);
    }
    allocationGuard += mutations.count();
  }

  private static void append(
      PendingMutationBuffer mutations, ByteBuffer row, long space, long key, int value) {
    row.put(0, (byte) value);
    assertEquals(StatusCode.OK, mutations.reserve(1, 1));
    mutations.append(IndexedWalCodec.MUTATION_INSERT, space, key, 0, row, 0, 1);
  }

  private static ThreadMXBean allocationBean() {
    java.lang.management.ThreadMXBean standardBean = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standardBean instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standardBean;
    Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    bean.setThreadAllocatedMemoryEnabled(true);
    return bean;
  }

  private static final class FailingChunkAllocator implements PendingRowChunkAllocator {
    private final int failedCall;
    private int calls;
    private boolean failing = true;

    private FailingChunkAllocator(int call) {
      failedCall = call;
    }

    @Override
    public byte[] allocate(int bytes) {
      calls++;
      if (failing && calls == failedCall) throw new OutOfMemoryError("injected");
      return new byte[bytes];
    }

    private void allowAllocations() {
      failing = false;
    }
  }
}
