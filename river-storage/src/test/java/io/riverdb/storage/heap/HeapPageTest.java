package io.riverdb.storage.heap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class HeapPageTest {
  @Test
  void insertsFetchesScansAndStopsAtCapacity() {
    ByteBuffer page = ByteBuffer.allocate(128);
    assertEquals(StatusCode.OK, HeapPage.initialize(page));
    HeapInsertResult inserted = new HeapInsertResult();
    assertEquals(
        StatusCode.OK,
        HeapPage.insert(page, ByteBuffer.wrap(new byte[] {1, 2, 3}), inserted));
    assertEquals(1, inserted.rowId());
    assertEquals(
        StatusCode.OK,
        HeapPage.insert(page, ByteBuffer.wrap(new byte[] {4, 5}), inserted));
    assertEquals(2, inserted.rowId());

    HeapRowResult row = new HeapRowResult();
    assertEquals(StatusCode.OK, HeapPage.fetch(page, 1, row));
    assertEquals(3, row.length());
    ByteBuffer copied = ByteBuffer.allocate(3);
    assertEquals(StatusCode.OK, row.copyTo(copied));
    assertEquals(2, copied.get(1));
    HeapScanCursor scan = new HeapScanCursor();
    assertEquals(StatusCode.OK, HeapPage.next(page, scan, row));
    assertEquals(1, row.rowId());
    assertEquals(StatusCode.OK, HeapPage.next(page, scan, row));
    assertEquals(2, row.rowId());
    assertEquals(StatusCode.CONFLICT, HeapPage.next(page, scan, row));

    byte[] oversized = new byte[100];
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        HeapPage.insert(page, ByteBuffer.wrap(oversized), inserted));
  }

  @Test
  void rejectsSlotCorruption() {
    ByteBuffer page = ByteBuffer.allocate(128);
    assertEquals(StatusCode.OK, HeapPage.initialize(page));
    assertEquals(
        StatusCode.OK,
        HeapPage.insert(
            page,
            ByteBuffer.wrap(new byte[] {8, 9}),
            new HeapInsertResult()));
    page.putInt(HeapPage.HEADER_BYTES, 17);
    assertEquals(StatusCode.CORRUPTION, HeapPage.validate(page));
  }
}
