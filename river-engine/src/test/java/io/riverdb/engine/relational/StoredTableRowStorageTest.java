package io.riverdb.engine.relational;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.format.FormatBytes;
import io.riverdb.storage.heap.HeapPage;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.sql.SqlComparison;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class StoredTableRowStorageTest {
  private static final int START = 5;
  private static volatile long allocationGuard;

  @Test
  void rowStartsWithNullBitmapAndContainsOnlyColumnData() {
    TableDescriptor table = table(
        new int[] {SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.BOOLEAN,
          SqlTypeDescriptor.varchar(4)}, new boolean[] {false, true, true});
    SqlValueBuffer input = values(3, 8);
    assertEquals(StatusCode.OK, input.setFixed(0, SqlTypeDescriptor.BIGINT, 42));
    assertEquals(StatusCode.OK, input.setNull(1, SqlTypeDescriptor.BOOLEAN));
    assertEquals(StatusCode.OK, input.setText(2, SqlTypeDescriptor.varchar(4), "ab"));

    Encoded row = encode(table, input);
    assertEquals(20, row.length);
    assertArrayEquals(HexFormat.of().parseHex("022a000000000000000012000000020000006162"),
        Arrays.copyOfRange(row.bytes, START, START + row.length));
    SqlValueBuffer output = values(3, 8);
    assertEquals(StatusCode.OK, decodeTrusted(
        table, ByteBuffer.wrap(row.bytes), START, row.length, output));
    assertEquals(42, output.valueAt(0));
    assertEquals(true, output.isNull(1));
    assertEquals('a', output.textByteAt(2, 0));
    assertEquals('b', output.textByteAt(2, 1));
  }

  @Test
  void filtersAfterStructuralChecksAndPreservesDestination() {
    int text = SqlTypeDescriptor.varchar(4);
    TableDescriptor table = table(
        new int[] {SqlTypeDescriptor.SMALLINT, text},
        new boolean[] {true, false});
    SqlValueBuffer input = values(2, 16);
    assertEquals(StatusCode.OK, input.setFixed(0, SqlTypeDescriptor.SMALLINT, 40));
    assertEquals(StatusCode.OK, input.setText(1, text, "yes"));
    Encoded encoded = encode(table, input);
    SqlValueBuffer output = values(2, 16);
    assertEquals(StatusCode.OK, output.setFixed(0, SqlTypeDescriptor.SMALLINT, 7));
    StoredTableRowIntegerFilter filter = new StoredTableRowIntegerFilter();
    assertEquals(StatusCode.OK, filter.configure(0, SqlComparison.LESS_THAN, 30));
    assertEquals(StatusCode.CONFLICT, decodeTrusted(
        table, ByteBuffer.wrap(encoded.bytes), START, encoded.length, output, filter));
    assertEquals(7, output.valueAt(0));

    byte[] corrupt = encoded.bytes.clone();
    int textSlot = START + table.fixedOffsetAt(1);
    int textStart = START + FormatBytes.getInt(ByteBuffer.wrap(corrupt), textSlot);
    corrupt[textStart] = (byte) 0xc0;
    assertEquals(StatusCode.CONFLICT, decodeTrusted(
        table, ByteBuffer.wrap(corrupt), START, encoded.length, output, filter));
    assertEquals(7, output.valueAt(0));

    assertEquals(StatusCode.OK, filter.configure(0, SqlComparison.LESS_OR_EQUAL, 40));
    assertEquals(StatusCode.OK, decodeTrusted(
        table, ByteBuffer.wrap(corrupt), START, encoded.length, output, filter));
    assertEquals(40, output.valueAt(0));
    assertEquals(0xc0, output.textByteAt(1, 0));
  }

  @Test
  void invalidIntegerFilterNeverReadsOutsideTheFixedPrefix() {
    int text = SqlTypeDescriptor.varchar(4);
    TableDescriptor table = table(
        new int[] {SqlTypeDescriptor.BIGINT, text}, new boolean[] {false, false});
    SqlValueBuffer input = values(2, 16);
    assertEquals(StatusCode.OK, input.setFixed(0, SqlTypeDescriptor.BIGINT, 4));
    assertEquals(StatusCode.OK, input.setText(1, text, "safe"));
    Encoded encoded = encode(table, input);
    SqlValueBuffer output = values(2, 16);
    assertEquals(StatusCode.OK, output.setFixed(0, SqlTypeDescriptor.BIGINT, 9));
    StoredTableRowIntegerFilter filter = new StoredTableRowIntegerFilter();

    assertEquals(StatusCode.OK, filter.configure(2, SqlComparison.EQUAL, 4));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, decodeTrusted(
        table, ByteBuffer.wrap(encoded.bytes), START, encoded.length, output, filter));
    assertEquals(9, output.valueAt(0));

    assertEquals(StatusCode.OK, filter.configure(1, SqlComparison.EQUAL, 4));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, decodeTrusted(
        table, ByteBuffer.wrap(encoded.bytes), START, encoded.length, output, filter));
    assertEquals(9, output.valueAt(0));

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        filter.configure(0, SqlComparison.IN, 4));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, decodeTrusted(
        table, ByteBuffer.wrap(encoded.bytes), START, encoded.length, output, filter));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        filter.configure(-1, SqlComparison.EQUAL, 4));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, decodeTrusted(
        table, ByteBuffer.wrap(encoded.bytes), START, encoded.length, output, filter));
    assertEquals(9, output.valueAt(0));
  }

  @Test
  void omittedTextIsNotInspectedAndNumericColumnsRemainAvailable() {
    int text = SqlTypeDescriptor.varchar(4);
    TableDescriptor table = table(
        new int[] {SqlTypeDescriptor.SMALLINT, text, SqlTypeDescriptor.BOOLEAN},
        new boolean[] {false, false, false});
    SqlValueBuffer input = values(3, 16);
    assertEquals(StatusCode.OK, input.setFixed(0, SqlTypeDescriptor.SMALLINT, 40));
    assertEquals(StatusCode.OK, input.setText(1, text, "yes"));
    assertEquals(StatusCode.OK, input.setFixed(2, SqlTypeDescriptor.BOOLEAN, 1));
    Encoded encoded = encode(table, input);
    SqlValueBuffer output = values(3, 16);

    assertEquals(StatusCode.OK, decodeTrusted(
        table, ByteBuffer.wrap(encoded.bytes), START, encoded.length,
        output, null, false));
    assertEquals(40, output.valueAt(0));
    assertEquals(0, output.descriptorAt(1));
    assertEquals(0, output.textBytesUsed());
    assertEquals(1, output.valueAt(2));

    byte[] corrupt = encoded.bytes.clone();
    int slot = START + table.fixedOffsetAt(1);
    corrupt[START + FormatBytes.getInt(ByteBuffer.wrap(corrupt), slot)] = (byte) 0xc0;
    assertEquals(StatusCode.OK, decodeTrusted(
        table, ByteBuffer.wrap(corrupt), START, encoded.length,
        output, null, false));
    assertEquals(40, output.valueAt(0));
    assertEquals(0, output.descriptorAt(1));

    corrupt = encoded.bytes.clone();
    FormatBytes.putInt(ByteBuffer.wrap(corrupt), slot, Integer.MAX_VALUE);
    FormatBytes.putInt(ByteBuffer.wrap(corrupt), slot + Integer.BYTES, -1);
    assertEquals(StatusCode.OK, decodeTrusted(
        table, ByteBuffer.wrap(corrupt), START, encoded.length,
        output, null, false));
    assertEquals(40, output.valueAt(0));
    assertEquals(1, output.valueAt(2));
    assertCorruptPreserves(table, encoded.length, corrupt, output);
  }

  @Test
  void projectedReadPublishesOnlySelectedNumericColumns() {
    int text = SqlTypeDescriptor.varchar(4);
    TableDescriptor table = table(
        new int[] {SqlTypeDescriptor.SMALLINT, text, SqlTypeDescriptor.INTEGER},
        new boolean[] {false, false, false});
    SqlValueBuffer input = values(3, 16);
    assertEquals(StatusCode.OK, input.setFixed(0, SqlTypeDescriptor.SMALLINT, 40));
    assertEquals(StatusCode.OK, input.setText(1, text, "wide"));
    assertEquals(StatusCode.OK, input.setFixed(2, SqlTypeDescriptor.INTEGER, 91));
    Encoded encoded = encode(table, input);
    StoredTableColumnSelection selected = new StoredTableColumnSelection();
    assertEquals(StatusCode.OK, selected.selectNone(table.columnCount()));
    selected.select(2);
    SqlValueBuffer output = values(3, 0);
    assertEquals(StatusCode.OK, StoredTableRowDecoder.decode(
        table, ByteBuffer.wrap(encoded.bytes), START, encoded.length,
        output, null, selected));
    assertEquals(0, output.descriptorAt(0));
    assertEquals(0, output.descriptorAt(1));
    assertEquals(SqlTypeDescriptor.INTEGER, output.descriptorAt(2));
    assertEquals(91, output.valueAt(2));

    byte[] corrupt = encoded.bytes.clone();
    int slot = START + table.fixedOffsetAt(1);
    FormatBytes.putInt(ByteBuffer.wrap(corrupt), slot, Integer.MAX_VALUE);
    assertEquals(StatusCode.OK, StoredTableRowDecoder.decode(
        table, ByteBuffer.wrap(corrupt), START, encoded.length,
        output, null, selected));
    selected.select(1);
    assertEquals(StatusCode.CORRUPTION, StoredTableRowDecoder.decode(
        table, ByteBuffer.wrap(corrupt), START, encoded.length,
        output, null, selected));
  }

  @Test
  void roundTripsMixedValuesWithoutChangingBufferState() {
    TableDescriptor table = table(
        new int[] {SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.BOOLEAN,
          SqlTypeDescriptor.DATE, SqlTypeDescriptor.varchar(4)},
        new boolean[] {false, true, true, true});
    SqlValueBuffer input = values(4, 32);
    assertEquals(StatusCode.OK, input.setFixed(0, SqlTypeDescriptor.BIGINT, -19));
    assertEquals(StatusCode.OK, input.setNull(1, SqlTypeDescriptor.BOOLEAN));
    assertEquals(StatusCode.OK, input.setFixed(2, SqlTypeDescriptor.DATE, 0));
    assertEquals(StatusCode.OK, input.setText(3, SqlTypeDescriptor.varchar(4), "A£河🌊"));
    ByteBuffer row = ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN);
    row.position(2).limit(200);
    StoredTableRowEncodeResult encoded = new StoredTableRowEncodeResult();

    assertEquals(StatusCode.OK, StoredTableRowEncoder.encode(table, input, row, START, encoded));
    assertEquals(2, row.position());
    assertEquals(200, row.limit());
    assertEquals(ByteOrder.BIG_ENDIAN, row.order());
    assertEquals(10, FormatBytes.getInt(row, START + table.fixedOffsetAt(3) + 4));

    SqlValueBuffer output = values(4, 32);
    assertEquals(StatusCode.OK,
        decodeTrusted(table, row, START, encoded.length(), output));
    assertEquals(-19, output.valueAt(0));
    assertEquals(true, output.isNull(1));
    assertEquals(0, output.valueAt(2));
    byte[] text = new byte[10];
    assertEquals(StatusCode.OK, output.copyTextBytes(3, text, 0));
    assertEquals("A£河🌊", new String(text, StandardCharsets.UTF_8));
    assertEquals(2, row.position());
    assertEquals(200, row.limit());
  }

  @Test
  void preservesDestinationOnEveryEncodePreflightFailure() {
    TableDescriptor table = table(
        new int[] {SqlTypeDescriptor.BIGINT}, new boolean[] {false});
    SqlValueBuffer wrong = values(1, 0);
    assertEquals(StatusCode.OK, wrong.setFixed(0, SqlTypeDescriptor.BOOLEAN, 1));
    byte[] bytes = new byte[64];
    Arrays.fill(bytes, (byte) 0x5a);
    byte[] before = bytes.clone();
    StoredTableRowEncodeResult result = new StoredTableRowEncodeResult();

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        StoredTableRowEncoder.encode(table, wrong, ByteBuffer.wrap(bytes), 0, result));
    assertArrayEquals(before, bytes);
    assertEquals(0, result.length());
    SqlValueBuffer right = values(1, 0);
    assertEquals(StatusCode.OK, right.setFixed(0, SqlTypeDescriptor.BIGINT, 1));
    ByteBuffer shortTarget = ByteBuffer.wrap(bytes).limit(table.encodedMaximumRowBytes() - 1);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        StoredTableRowEncoder.encode(table, right, shortTarget, 0, result));
    assertArrayEquals(before, bytes);
  }

  @Test
  void trustsContentOutsideRequiredStructuralMetadata() {
    TableDescriptor table = table(9, SqlTypeDescriptor.BOOLEAN, true);
    SqlValueBuffer input = values(9, 0);
    for (int index = 0; index < 9; index++) {
      StatusCode status = index == 7
          ? input.setNull(index, SqlTypeDescriptor.BOOLEAN)
          : input.setFixed(index, SqlTypeDescriptor.BOOLEAN, index & 1);
      assertEquals(StatusCode.OK, status);
    }
    Encoded row = encode(table, input);
    SqlValueBuffer output = values(9, 0);
    assertEquals(StatusCode.OK, output.setFixed(0, SqlTypeDescriptor.BOOLEAN, 1));

    byte[] corrupt = row.bytes.clone();
    corrupt[START + 1] |= (byte) 0x80;
    assertEquals(StatusCode.OK, decodeTrusted(
        table, ByteBuffer.wrap(corrupt), START, row.length, output));
    assertEquals(true, output.isNull(7));

    corrupt = row.bytes.clone();
    corrupt[START + table.fixedOffsetAt(7)] = 1;
    assertEquals(StatusCode.OK, decodeTrusted(
        table, ByteBuffer.wrap(corrupt), START, row.length, output));
    assertEquals(true, output.isNull(7));

    corrupt = row.bytes.clone();
    corrupt[START + table.fixedOffsetAt(0)] = 2;
    assertEquals(StatusCode.OK, decodeTrusted(
        table, ByteBuffer.wrap(corrupt), START, row.length, output));
    assertEquals(2, output.valueAt(0));
  }

  @Test
  void rejectsTextBoundsWhileTrustingStoredContent() {
    int varchar = SqlTypeDescriptor.varchar(4);
    TableDescriptor table = table(new int[] {varchar, varchar}, new boolean[] {true, true});
    SqlValueBuffer input = values(2, 32);
    assertEquals(StatusCode.OK, input.setText(0, varchar, "ab"));
    assertEquals(StatusCode.OK, input.setText(1, varchar, "£"));
    Encoded row = encode(table, input);
    SqlValueBuffer output = values(2, 32);

    byte[] corrupt = row.bytes.clone();
    int firstSlot = START + table.fixedOffsetAt(0);
    FormatBytes.putInt(ByteBuffer.wrap(corrupt), firstSlot,
        FormatBytes.getInt(ByteBuffer.wrap(corrupt), firstSlot) + 1);
    assertEquals(StatusCode.OK, decodeTrusted(
        table, ByteBuffer.wrap(corrupt), START, row.length, output));
    assertEquals('b', output.textByteAt(0, 0));

    corrupt = row.bytes.clone();
    FormatBytes.putInt(ByteBuffer.wrap(corrupt), firstSlot, row.length);
    assertCorruptPreserves(table, row.length, corrupt, output);

    corrupt = row.bytes.clone();
    int textStart = START + FormatBytes.getInt(ByteBuffer.wrap(corrupt), firstSlot);
    corrupt[textStart] = (byte) 0xc0;
    assertEquals(StatusCode.OK, decodeTrusted(
        table, ByteBuffer.wrap(corrupt), START, row.length, output));
    assertEquals(0xc0, output.textByteAt(0, 0));

    corrupt = row.bytes.clone();
    FormatBytes.putInt(ByteBuffer.wrap(corrupt), firstSlot + Integer.BYTES, -1);
    assertCorruptPreserves(table, row.length, corrupt, output);

    corrupt = row.bytes.clone();
    FormatBytes.putInt(
        ByteBuffer.wrap(corrupt), firstSlot + Integer.BYTES, Integer.MAX_VALUE);
    assertCorruptPreserves(table, row.length, corrupt, output);

    assertEquals(StatusCode.OK, decodeTrusted(
        table, ByteBuffer.wrap(row.bytes), START, row.length + 1, output));
    assertEquals(StatusCode.CORRUPTION, decodeTrusted(
        table, ByteBuffer.wrap(row.bytes), START,
        HeapPage.MAXIMUM_ROW_BYTES + 1, output));
  }

  @Test
  void preservesDestinationWhenDecodeStorageIsInsufficient() {
    int varchar = SqlTypeDescriptor.varchar(4);
    TableDescriptor table = table(new int[] {varchar, varchar}, new boolean[] {true, true});
    SqlValueBuffer input = values(2, 16);
    assertEquals(StatusCode.OK, input.setText(0, varchar, "old"));
    assertEquals(StatusCode.OK, input.setText(1, varchar, "new"));
    Encoded row = encode(table, input);

    SqlValueBuffer tooFewLanes = new SqlValueBuffer();
    assertEquals(StatusCode.OK, tooFewLanes.reserve(1, 1, 16, 16));
    assertEquals(StatusCode.OK, tooFewLanes.clearForSize(1));
    assertEquals(StatusCode.OK, tooFewLanes.setText(0, varchar, "keep"));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, decodeTrusted(
        table, ByteBuffer.wrap(row.bytes), START, row.length, tooFewLanes));
    assertEquals(1, tooFewLanes.count());
    assertEquals(4, tooFewLanes.textByteLengthAt(0));

    SqlValueBuffer tooLittleText = values(2, 1);
    assertEquals(StatusCode.OK, tooLittleText.setNull(0, varchar));
    assertEquals(StatusCode.OK, tooLittleText.setText(1, varchar, "x"));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, decodeTrusted(
        table, ByteBuffer.wrap(row.bytes), START, row.length, tooLittleText));
    assertEquals(true, tooLittleText.isNull(0));
    assertEquals(1, tooLittleText.textByteLengthAt(1));
  }

  @Test
  void preservesNullOrdinalsAcrossByteAndWordBoundaries() {
    int count = 1_024;
    TableDescriptor table = table(count, SqlTypeDescriptor.BOOLEAN, true);
    SqlValueBuffer input = values(count, 0);
    int[] boundaries = {0, 7, 8, 63, 64, 255, 1_023};
    for (int index = 0; index < count; index++) {
      boolean boundary = false;
      for (int candidate : boundaries) boundary |= index == candidate;
      assertEquals(StatusCode.OK, boundary
          ? input.setNull(index, SqlTypeDescriptor.BOOLEAN)
          : input.setFixed(index, SqlTypeDescriptor.BOOLEAN, index & 1));
    }
    Encoded row = encode(table, input);
    SqlValueBuffer output = values(count, 0);
    assertEquals(StatusCode.OK, decodeTrusted(
        table, ByteBuffer.wrap(row.bytes), START, row.length, output));
    for (int boundary : boundaries) assertEquals(true, output.isNull(boundary));
    assertEquals(false, output.isNull(6));
    assertEquals(false, output.isNull(65));
  }

  @Test
  void encodesAndDecodesTheExactSingleHeapRowBoundary() {
    int[] types = {
      SqlTypeDescriptor.varchar(4_051),
      SqlTypeDescriptor.BOOLEAN,
      SqlTypeDescriptor.BOOLEAN,
      SqlTypeDescriptor.BOOLEAN
    };
    boolean[] nullable = new boolean[types.length];
    TableDescriptor table = table(types, nullable);
    assertEquals(HeapPage.MAXIMUM_ROW_BYTES, table.encodedMaximumRowBytes());
    SqlValueBuffer input = values(types.length, 4_051 * 4);
    assertEquals(StatusCode.OK, input.setText(
        0, types[0], supplementaryText(4_051), 0, 4_051 * 2));
    assertEquals(StatusCode.OK, input.setFixed(1, SqlTypeDescriptor.BOOLEAN, 0));
    assertEquals(StatusCode.OK, input.setFixed(2, SqlTypeDescriptor.BOOLEAN, 1));
    assertEquals(StatusCode.OK, input.setFixed(3, SqlTypeDescriptor.BOOLEAN, 0));
    byte[] bytes = new byte[HeapPage.MAXIMUM_ROW_BYTES];
    StoredTableRowEncodeResult result = new StoredTableRowEncodeResult();
    assertEquals(StatusCode.OK, StoredTableRowEncoder.encode(
        table, input, ByteBuffer.wrap(bytes), 0, result));
    assertEquals(HeapPage.MAXIMUM_ROW_BYTES, result.length());
    SqlValueBuffer output = values(types.length, 4_051 * 4);
    assertEquals(StatusCode.OK, decodeTrusted(
        table, ByteBuffer.wrap(bytes), 0, result.length(), output));
    assertEquals(1, output.valueAt(2));
    assertEquals(4_051 * 4, output.textByteLengthAt(0));
  }

  @Test
  void roundTripsTemporalAndDecimalBoundariesWithoutRecheckingStoredDomains() {
    int[] types = {
      SqlTypeDescriptor.decimal(18, 2),
      SqlTypeDescriptor.time(6),
      SqlTypeDescriptor.timestamp(6),
      SqlTypeDescriptor.timestampWithTimeZone(6)
    };
    TableDescriptor table = table(types, new boolean[types.length]);
    for (int column = 0; column < types.length; column++) {
      for (int edge = 0; edge < 2; edge++) {
        SqlValueBuffer input = values(types.length, 0);
        for (int index = 0; index < types.length; index++) {
          long value = edge == 0
              ? SqlValueDomain.minimumFixed(types[index])
              : SqlValueDomain.exclusiveMaximumFixed(types[index]) - 1;
          assertEquals(StatusCode.OK, input.setFixed(index, types[index], value));
        }
        Encoded row = encode(table, input);
        SqlValueBuffer output = values(types.length, 0);
        assertEquals(StatusCode.OK, decodeTrusted(
            table, ByteBuffer.wrap(row.bytes), START, row.length, output));
        for (int index = 0; index < types.length; index++) {
          assertEquals(input.valueAt(index), output.valueAt(index));
        }
      }

      SqlValueBuffer valid = values(types.length, 0);
      for (int index = 0; index < types.length; index++) {
        assertEquals(StatusCode.OK, valid.setFixed(
            index, types[index], SqlValueDomain.minimumFixed(types[index])));
      }
      Encoded row = encode(table, valid);
      byte[] corrupt = row.bytes.clone();
      FormatBytes.putLong(ByteBuffer.wrap(corrupt),
          START + table.fixedOffsetAt(column),
          SqlValueDomain.exclusiveMaximumFixed(types[column]));
      SqlValueBuffer output = values(types.length, 0);
      assertEquals(StatusCode.OK, output.setFixed(0, types[0], 7));
      assertEquals(StatusCode.OK, decodeTrusted(
          table, ByteBuffer.wrap(corrupt), START, row.length, output));
      assertEquals(SqlValueDomain.exclusiveMaximumFixed(types[column]),
          output.valueAt(column));
    }
  }

  @Test
  void roundTripsWideDecimalAndPreservesTrustedStoredBits() {
    int decimal = SqlTypeDescriptor.decimal(38, 9);
    TableDescriptor table = table(
        new int[] {decimal, SqlTypeDescriptor.INTEGER}, new boolean[] {false, false});
    assertEquals(16, table.fixedWidthAt(0));
    SqlValueBuffer input = values(2, 0);
    long high = 542_101_086_242_752_217L;
    long low = 68_739_955_140_067_328L;
    assertEquals(StatusCode.OK, input.setDecimal128(0, decimal, high, low));
    assertEquals(StatusCode.OK, input.setFixed(1, SqlTypeDescriptor.INTEGER, 7));
    Encoded row = encode(table, input);
    SqlValueBuffer output = values(2, 0);
    assertEquals(StatusCode.OK, decodeTrusted(
        table, ByteBuffer.wrap(row.bytes), START, row.length, output));
    assertEquals(high, output.highValueAt(0));
    assertEquals(low, output.valueAt(0));

    byte[] corrupt = row.bytes.clone();
    FormatBytes.putLong(
        ByteBuffer.wrap(corrupt), START + table.fixedOffsetAt(0), Long.MAX_VALUE);
    assertEquals(StatusCode.OK, decodeTrusted(
        table, ByteBuffer.wrap(corrupt), START, row.length, output));
    assertEquals(Long.MAX_VALUE, output.highValueAt(0));
  }

  @Test
  void warmedStoredRowEntryDoesNotAllocatePerRow() {
    ThreadMXBean bean = allocationBean();
    int varchar = SqlTypeDescriptor.varchar(8);
    TableDescriptor table = table(
        new int[] {SqlTypeDescriptor.BIGINT, varchar}, new boolean[] {false, false});
    SqlValueBuffer input = values(2, 16);
    assertEquals(StatusCode.OK, input.setFixed(0, SqlTypeDescriptor.BIGINT, 42));
    assertEquals(StatusCode.OK, input.setText(1, varchar, "river"));
    SqlValueBuffer output = values(2, 16);
    ByteBuffer row = ByteBuffer.allocate(128);
    StoredTableRowEncodeResult result = new StoredTableRowEncodeResult();
    assertEquals(StatusCode.OK,
        StoredTableRowEncoder.encode(table, input, row, 0, result));
    HeapRowResult fetched = new HeapRowResult();
    fetched.set(row, 1, 0, result.length());
    RelationalDescriptorRowBuffer reader = new RelationalDescriptorRowBuffer();
    assertEquals(StatusCode.OK, reader.reserve(result.length()));
    exercise(reader, table, fetched, output, 300_000);

    long thread = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(thread);
    exercise(reader, table, fetched, output, 100_000);
    long allocated = bean.getThreadAllocatedBytes(thread) - before;
    assertEquals(true, allocated <= 256, "warmed stored row read allocated: " + allocated);
    before = bean.getThreadAllocatedBytes(thread);
    exercise(reader, table, fetched, output, 1_000_000);
    allocated = bean.getThreadAllocatedBytes(thread) - before;
    assertEquals(true, allocated <= 256, "longer stored row read allocated: " + allocated);
  }

  private static void assertCorruptPreserves(
      TableDescriptor table, int length, byte[] bytes, SqlValueBuffer output) {
    int count = output.count();
    long first = output.valueAt(0);
    assertEquals(StatusCode.CORRUPTION, decodeTrusted(
        table, ByteBuffer.wrap(bytes), START, length, output));
    assertEquals(count, output.count());
    assertEquals(first, output.valueAt(0));
  }

  private static StatusCode decodeTrusted(
      TableDescriptor table, ByteBuffer source, int start, int length,
      SqlValueBuffer output) {
    return decodeTrusted(table, source, start, length, output, null, true);
  }

  private static StatusCode decodeTrusted(
      TableDescriptor table, ByteBuffer source, int start, int length,
      SqlValueBuffer output, StoredTableRowIntegerFilter filter) {
    return decodeTrusted(table, source, start, length, output, filter, true);
  }

  private static StatusCode decodeTrusted(
      TableDescriptor table, ByteBuffer source, int start, int length,
      SqlValueBuffer output, StoredTableRowIntegerFilter filter, boolean publishText) {
    StoredTableColumnSelection selection = null;
    if (!publishText) {
      selection = new StoredTableColumnSelection();
      StatusCode status = selection.selectNone(table.columnCount());
      if (!status.isOk()) return status;
      for (int column = 0; column < table.columnCount(); column++) {
        if (!StoredTableRowEncoder.isText(table.typeDescriptorAt(column))) {
          selection.select(column);
        }
      }
    }
    return StoredTableRowDecoder.decode(
        table, source, start, length, output, filter, selection);
  }

  private static Encoded encode(TableDescriptor table, SqlValueBuffer input) {
    byte[] bytes = new byte[START + table.encodedMaximumRowBytes() + 1];
    StoredTableRowEncodeResult result = new StoredTableRowEncodeResult();
    assertEquals(StatusCode.OK, StoredTableRowEncoder.encode(
        table, input, ByteBuffer.wrap(bytes), START, result));
    return new Encoded(bytes, result.length());
  }

  private static SqlValueBuffer values(int lanes, int textBytes) {
    SqlValueBuffer result = new SqlValueBuffer();
    assertEquals(StatusCode.OK, result.reserve(lanes, 1_024, textBytes, textBytes));
    assertEquals(StatusCode.OK, result.clearForSize(lanes));
    return result;
  }

  private static TableDescriptor table(int count, int type, boolean nullable) {
    int[] types = new int[count];
    boolean[] nullability = new boolean[count];
    Arrays.fill(types, type);
    Arrays.fill(nullability, nullable);
    return table(types, nullability);
  }

  private static TableDescriptor table(int[] types, boolean[] nullable) {
    CharSequence[] names = new CharSequence[types.length];
    for (int index = 0; index < names.length; index++) names[index] = "c" + index;
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(types, names, nullable, columns));
    TableDescriptor.Result table = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.create(
        17, 23, 29, columns.value(), null, null, null, table, null));
    return table.value();
  }

  private static char[] supplementaryText(int scalars) {
    char[] text = new char[scalars * 2];
    char high = Character.highSurrogate(0x1f30a);
    char low = Character.lowSurrogate(0x1f30a);
    for (int index = 0; index < scalars; index++) {
      text[index * 2] = high;
      text[index * 2 + 1] = low;
    }
    return text;
  }

  private static void exercise(
      RelationalDescriptorRowBuffer reader, TableDescriptor table,
      HeapRowResult fetched, SqlValueBuffer output, int iterations) {
    for (int index = 0; index < iterations; index++) {
      StatusCode status = reader.decode(table, fetched, output);
      if (!status.isOk()) throw new AssertionError(status);
      allocationGuard += output.valueAt(0) + output.textByteLengthAt(1);
    }
  }

  private static ThreadMXBean allocationBean() {
    java.lang.management.ThreadMXBean standardBean = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standardBean instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standardBean;
    Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    bean.setThreadAllocatedMemoryEnabled(true);
    return bean;
  }

  private record Encoded(byte[] bytes, int length) {
  }
}
