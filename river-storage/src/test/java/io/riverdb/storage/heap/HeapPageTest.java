package io.riverdb.storage.heap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class HeapPageTest {
  @Test
  void projectedRetentionCopiesSelectedValuesAndOwnsThemAfterSourceReuse() {
    ByteBuffer source = ByteBuffer.allocate(256);
    int start = 17;
    FormatBytes.putLong(source, start + 1, 1234);
    FormatBytes.putInt(source, start + 9, 25);
    FormatBytes.putInt(source, start + 13, 2);
    FormatBytes.putInt(source, start + 17, 27);
    FormatBytes.putInt(source, start + 21, 100);
    source.put(start + 25, (byte) 'o');
    source.put(start + 26, (byte) 'k');
    for (int index = 27; index < 127; index++) {
      source.put(start + index, (byte) 0x55);
    }
    HeapRowProjection plan = new HeapRowProjection();
    assertEquals(StatusCode.OK, plan.prepare(1, 25, 2));
    assertEquals(StatusCode.OK, plan.add(0, 1, 8, false));
    assertEquals(StatusCode.OK, plan.add(1, 9, 8, true));
    HeapRowResult result = new HeapRowResult();
    result.retentionProjection(plan);
    result.set(source, 1, start, 127);
    assertEquals(StatusCode.OK, result.retainBytes());
    assertEquals(27, result.length());
    ByteBuffer retained = result.retainedReadOnlyBytes();
    assertEquals(1234, FormatBytes.getLong(retained, 1));
    assertEquals(25, FormatBytes.getInt(retained, 9));
    assertEquals(0, FormatBytes.getInt(retained, 17));
    assertEquals('o', retained.get(25));
    source.put(start + 25, (byte) 'x');
    assertEquals('o', result.retainedReadOnlyBytes().get(25));

    FormatBytes.putInt(source, start + 17, 10_000);
    HeapRowResult unusedDamaged = new HeapRowResult();
    unusedDamaged.retentionProjection(plan);
    unusedDamaged.set(source, 1, start, 127);
    assertEquals(StatusCode.OK, unusedDamaged.retainBytes());
    assertEquals(27, unusedDamaged.length());

    FormatBytes.putInt(source, start + 9, 200);
    HeapRowResult damaged = new HeapRowResult();
    damaged.retentionProjection(plan);
    damaged.set(source, 1, start, 127);
    assertEquals(StatusCode.CORRUPTION, damaged.retainBytes());
  }

  @Test
  void maximumRowDerivesFromOnePageSlot() {
    ByteBuffer page = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    assertEquals(StatusCode.OK, HeapPage.initialize(page));
    assertEquals(StatusCode.OK, HeapPage.insert(
        page, ByteBuffer.allocate(HeapPage.MAXIMUM_ROW_BYTES), new HeapInsertResult()));

    page.clear();
    assertEquals(StatusCode.OK, HeapPage.initialize(page));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, HeapPage.insert(
        page, ByteBuffer.allocate(HeapPage.MAXIMUM_ROW_BYTES + 1),
        new HeapInsertResult()));
  }

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
    ByteBuffer directCopy = ByteBuffer.allocate(8);
    assertEquals(StatusCode.OK, HeapPage.copyRowTo(page, 1, directCopy, 2));
    assertEquals(3, HeapPage.rowLength(page, 1));
    assertEquals(1, directCopy.get(2));
    assertEquals(3, directCopy.get(4));
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
  void retainedRowSurvivesSourceReuse() {
    ByteBuffer page = ByteBuffer.allocate(128);
    assertEquals(StatusCode.OK, HeapPage.initialize(page));
    assertEquals(
        StatusCode.OK,
        HeapPage.insert(
            page, ByteBuffer.wrap(new byte[] {1, 2, 3}), new HeapInsertResult()));
    HeapRowResult row = new HeapRowResult();
    assertEquals(StatusCode.OK, HeapPage.fetch(page, 1, row));
    assertEquals(null, row.retainedReadOnlyBytes());
    assertEquals(StatusCode.OK, row.retainBytes());

    ByteBuffer retained = row.retainedReadOnlyBytes();
    assertEquals(true, retained.isReadOnly());
    assertEquals(2, retained.get(1));

    assertEquals(StatusCode.OK, HeapPage.initialize(page));

    assertEquals(3, row.length());
    assertEquals(2, row.getByte(1));
    assertEquals(2, retained.get(1));
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
