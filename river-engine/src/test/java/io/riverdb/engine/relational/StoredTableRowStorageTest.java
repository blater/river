package io.riverdb.engine.relational;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

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
  void borrowedViewReadsSelectedValuesAndUtf8WithoutValueLanes() {
    int text = SqlTypeDescriptor.varchar(8);
    TableDescriptor table = table(
        new int[] {SqlTypeDescriptor.BIGINT, text, SqlTypeDescriptor.BOOLEAN},
        new boolean[] {false, true, false});
    SqlMutationValues input = values(table, 32);
    assertEquals(StatusCode.OK, input.setFixed(0, SqlTypeDescriptor.BIGINT, -19));
    assertEquals(StatusCode.OK, input.setText(1, text, "A£河🌊"));
    assertEquals(StatusCode.OK, input.setFixed(2, SqlTypeDescriptor.BOOLEAN, 1));
    Encoded encoded = encode(table, input);
    ByteBuffer source = ByteBuffer.wrap(encoded.bytes).asReadOnlyBuffer();
    StoredTableColumnSelection selected = new StoredTableColumnSelection();
    assertEquals(StatusCode.OK, selected.selectNone(table.columnCount()));
    selected.select(0);
    selected.select(1);
    StoredTableRowView view = new StoredTableRowView();
    assertEquals(StatusCode.OK, view.bind(
        table, source, START, encoded.length, null, selected));
    assertEquals(-19, view.valueAt(0));
    assertEquals(0, view.descriptorAt(2));
    int offset = view.textByteOffsetAt(1);
    int length = view.textByteLengthAt(1);
    byte[] utf8 = new byte[length];
    for (int index = 0; index < length; index++) {
      utf8[index] = view.textSource(1).getByte(offset + index);
    }
    assertEquals("A£河🌊", new String(utf8, StandardCharsets.UTF_8));
    view.reset();
    assertEquals(0, view.count());
  }

  @Test
  void mutationOverlayRetainsUnchangedTextAndEncodesChangedValues() {
    int text = SqlTypeDescriptor.varchar(8);
    TableDescriptor table = table(
        new int[] {SqlTypeDescriptor.BIGINT, text, SqlTypeDescriptor.INTEGER},
        new boolean[] {false, false, false});
    SqlMutationValues original = values(table, 32);
    assertEquals(StatusCode.OK, original.setFixed(0, SqlTypeDescriptor.BIGINT, 7));
    assertEquals(StatusCode.OK, original.setText(1, text, "多🙂"));
    assertEquals(StatusCode.OK, original.setFixed(2, SqlTypeDescriptor.INTEGER, 4));
    SqlMutationValues changed = new SqlMutationValues();
    assertEquals(StatusCode.OK, changed.reserve(table, 32));
    assertEquals(StatusCode.OK, changed.begin(table, original));
    assertSame(original.textSource(1), changed.textSource(1));
    assertEquals(original.textByteOffsetAt(1), changed.textByteOffsetAt(1));
    assertEquals(StatusCode.OK, changed.setFixed(2, SqlTypeDescriptor.INTEGER, 9));
    ByteBuffer encoded = ByteBuffer.allocate(table.encodedMaximumRowBytes());
    StoredTableRowEncodeResult length = new StoredTableRowEncodeResult();
    assertEquals(StatusCode.OK, StoredTableRowEncoder.encode(
        table, changed, encoded, 0, length));
    StoredTableRowView decoded = new StoredTableRowView();
    assertEquals(StatusCode.OK, decoded.bind(
        table, encoded.asReadOnlyBuffer(), 0, length.length(), null, null));
    assertEquals(7, decoded.valueAt(0));
    assertEquals(9, decoded.valueAt(2));
    byte[] utf8 = new byte[decoded.textByteLengthAt(1)];
    copyTextBytes(decoded, 1, utf8);
    assertEquals("多🙂", new String(utf8, StandardCharsets.UTF_8));
    assertEquals(4, original.valueAt(2));

    assertEquals(StatusCode.OK, changed.begin(table, original));
    assertEquals(4, changed.valueAt(2));
    assertEquals(StatusCode.OK, changed.setTextBytes(
        1, text, ByteBuffer.wrap("".getBytes(StandardCharsets.UTF_8)), 0, 0));
    assertEquals(0, changed.textByteLengthAt(1));
    assertEquals(StatusCode.OK, changed.setNull(2, SqlTypeDescriptor.INTEGER));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, StoredTableRowEncoder.encode(
        table, changed, encoded, 0, length));
  }


  @Test
  void rowStartsWithNullBitmapAndContainsOnlyColumnData() {
    TableDescriptor table = table(
        new int[] {SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.BOOLEAN,
          SqlTypeDescriptor.varchar(4)}, new boolean[] {false, true, true});
    SqlMutationValues input = values(table, 8);
    assertEquals(StatusCode.OK, input.setFixed(0, SqlTypeDescriptor.BIGINT, 42));
    assertEquals(StatusCode.OK, input.setNull(1, SqlTypeDescriptor.BOOLEAN));
    assertEquals(StatusCode.OK, input.setText(2, SqlTypeDescriptor.varchar(4), "ab"));

    Encoded row = encode(table, input);
    assertEquals(20, row.length);
    assertArrayEquals(HexFormat.of().parseHex("022a000000000000000012000000020000006162"),
        Arrays.copyOfRange(row.bytes, START, START + row.length));
    StoredTableRowView output = new StoredTableRowView();
    assertEquals(StatusCode.OK, decodeTrusted(
        table, ByteBuffer.wrap(row.bytes), START, row.length, output));
    assertEquals(42, output.valueAt(0));
    assertEquals(true, output.isNull(1));
    assertEquals('a', textByteAt(output, 2, 0));
    assertEquals('b', textByteAt(output, 2, 1));
  }

  @Test
  void filtersAfterStructuralChecksAndPreservesDestination() {
    int text = SqlTypeDescriptor.varchar(4);
    TableDescriptor table = table(
        new int[] {SqlTypeDescriptor.SMALLINT, text},
        new boolean[] {true, false});
    SqlMutationValues input = values(table, 16);
    assertEquals(StatusCode.OK, input.setFixed(0, SqlTypeDescriptor.SMALLINT, 40));
    assertEquals(StatusCode.OK, input.setText(1, text, "yes"));
    Encoded encoded = encode(table, input);
    StoredTableRowView output = new StoredTableRowView();
    StoredTableRowIntegerFilter filter = new StoredTableRowIntegerFilter();
    assertEquals(StatusCode.OK, filter.configure(0, SqlComparison.LESS_THAN, 30));
    assertEquals(StatusCode.CONFLICT, decodeTrusted(
        table, ByteBuffer.wrap(encoded.bytes), START, encoded.length, output, filter));
    assertEquals(0, output.count());

    byte[] corrupt = encoded.bytes.clone();
    int textSlot = START + table.fixedOffsetAt(1);
    int textStart = START + FormatBytes.getInt(ByteBuffer.wrap(corrupt), textSlot);
    corrupt[textStart] = (byte) 0xc0;
    assertEquals(StatusCode.CONFLICT, decodeTrusted(
        table, ByteBuffer.wrap(corrupt), START, encoded.length, output, filter));
    assertEquals(0, output.count());

    assertEquals(StatusCode.OK, filter.configure(0, SqlComparison.LESS_OR_EQUAL, 40));
    assertEquals(StatusCode.OK, decodeTrusted(
        table, ByteBuffer.wrap(corrupt), START, encoded.length, output, filter));
    assertEquals(40, output.valueAt(0));
    assertEquals(0xc0, textByteAt(output, 1, 0));
  }

  @Test
  void invalidIntegerFilterNeverReadsOutsideTheFixedPrefix() {
    int text = SqlTypeDescriptor.varchar(4);
    TableDescriptor table = table(
        new int[] {SqlTypeDescriptor.BIGINT, text}, new boolean[] {false, false});
    SqlMutationValues input = values(table, 16);
    assertEquals(StatusCode.OK, input.setFixed(0, SqlTypeDescriptor.BIGINT, 4));
    assertEquals(StatusCode.OK, input.setText(1, text, "safe"));
    Encoded encoded = encode(table, input);
    StoredTableRowView output = new StoredTableRowView();
    StoredTableRowIntegerFilter filter = new StoredTableRowIntegerFilter();

    assertEquals(StatusCode.OK, filter.configure(2, SqlComparison.EQUAL, 4));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, decodeTrusted(
        table, ByteBuffer.wrap(encoded.bytes), START, encoded.length, output, filter));
    assertEquals(0, output.count());

    assertEquals(StatusCode.OK, filter.configure(1, SqlComparison.EQUAL, 4));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, decodeTrusted(
        table, ByteBuffer.wrap(encoded.bytes), START, encoded.length, output, filter));
    assertEquals(0, output.count());

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        filter.configure(0, SqlComparison.IN, 4));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, decodeTrusted(
        table, ByteBuffer.wrap(encoded.bytes), START, encoded.length, output, filter));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        filter.configure(-1, SqlComparison.EQUAL, 4));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, decodeTrusted(
        table, ByteBuffer.wrap(encoded.bytes), START, encoded.length, output, filter));
    assertEquals(0, output.count());
  }

  @Test
  void omittedTextIsNotInspectedAndNumericColumnsRemainAvailable() {
    int text = SqlTypeDescriptor.varchar(4);
    TableDescriptor table = table(
        new int[] {SqlTypeDescriptor.SMALLINT, text, SqlTypeDescriptor.BOOLEAN},
        new boolean[] {false, false, false});
    SqlMutationValues input = values(table, 16);
    assertEquals(StatusCode.OK, input.setFixed(0, SqlTypeDescriptor.SMALLINT, 40));
    assertEquals(StatusCode.OK, input.setText(1, text, "yes"));
    assertEquals(StatusCode.OK, input.setFixed(2, SqlTypeDescriptor.BOOLEAN, 1));
    Encoded encoded = encode(table, input);
    StoredTableRowView output = new StoredTableRowView();

    assertEquals(StatusCode.OK, decodeTrusted(
        table, ByteBuffer.wrap(encoded.bytes), START, encoded.length,
        output, null, false));
    assertEquals(40, output.valueAt(0));
    assertEquals(0, output.descriptorAt(1));
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
    SqlMutationValues input = values(table, 16);
    assertEquals(StatusCode.OK, input.setFixed(0, SqlTypeDescriptor.SMALLINT, 40));
    assertEquals(StatusCode.OK, input.setText(1, text, "wide"));
    assertEquals(StatusCode.OK, input.setFixed(2, SqlTypeDescriptor.INTEGER, 91));
    Encoded encoded = encode(table, input);
    StoredTableColumnSelection selected = new StoredTableColumnSelection();
    assertEquals(StatusCode.OK, selected.selectNone(table.columnCount()));
    selected.select(2);
    StoredTableRowView output = new StoredTableRowView();
    assertEquals(StatusCode.OK, output.bind(
        table, ByteBuffer.wrap(encoded.bytes).asReadOnlyBuffer(), START, encoded.length,
        null, selected));
    assertEquals(0, output.descriptorAt(0));
    assertEquals(0, output.descriptorAt(1));
    assertEquals(SqlTypeDescriptor.INTEGER, output.descriptorAt(2));
    assertEquals(91, output.valueAt(2));

    byte[] corrupt = encoded.bytes.clone();
    int slot = START + table.fixedOffsetAt(1);
    FormatBytes.putInt(ByteBuffer.wrap(corrupt), slot, Integer.MAX_VALUE);
    assertEquals(StatusCode.OK, output.bind(
        table, ByteBuffer.wrap(corrupt).asReadOnlyBuffer(), START, encoded.length,
        null, selected));
    selected.select(1);
    assertEquals(StatusCode.CORRUPTION, output.bind(
        table, ByteBuffer.wrap(corrupt).asReadOnlyBuffer(), START, encoded.length,
        null, selected));
  }

  @Test
  void roundTripsMixedValuesWithoutChangingBufferState() {
    TableDescriptor table = table(
        new int[] {SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.BOOLEAN,
          SqlTypeDescriptor.DATE, SqlTypeDescriptor.varchar(4)},
        new boolean[] {false, true, true, true});
    SqlMutationValues input = values(table, 32);
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

    StoredTableRowView output = new StoredTableRowView();
    assertEquals(StatusCode.OK,
        decodeTrusted(table, row, START, encoded.length(), output));
    assertEquals(-19, output.valueAt(0));
    assertEquals(true, output.isNull(1));
    assertEquals(0, output.valueAt(2));
    byte[] text = new byte[10];
    copyTextBytes(output, 3, text);
    assertEquals("A£河🌊", new String(text, StandardCharsets.UTF_8));
    assertEquals(2, row.position());
    assertEquals(200, row.limit());
  }

  @Test
  void preservesDestinationOnEveryEncodePreflightFailure() {
    TableDescriptor table = table(
        new int[] {SqlTypeDescriptor.BIGINT}, new boolean[] {false});
    SqlMutationValues wrong = values(table, 0);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        wrong.setFixed(0, SqlTypeDescriptor.BOOLEAN, 1));
    byte[] bytes = new byte[64];
    Arrays.fill(bytes, (byte) 0x5a);
    byte[] before = bytes.clone();
    StoredTableRowEncodeResult result = new StoredTableRowEncodeResult();

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        StoredTableRowEncoder.encode(table, wrong, ByteBuffer.wrap(bytes), 0, result));
    assertArrayEquals(before, bytes);
    assertEquals(0, result.length());
    SqlMutationValues right = values(table, 0);
    assertEquals(StatusCode.OK, right.setFixed(0, SqlTypeDescriptor.BIGINT, 1));
    ByteBuffer shortTarget = ByteBuffer.wrap(bytes).limit(table.encodedMaximumRowBytes() - 1);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        StoredTableRowEncoder.encode(table, right, shortTarget, 0, result));
    assertArrayEquals(before, bytes);
  }

  @Test
  void trustsContentOutsideRequiredStructuralMetadata() {
    TableDescriptor table = table(9, SqlTypeDescriptor.BOOLEAN, true);
    SqlMutationValues input = values(table, 0);
    for (int index = 0; index < 9; index++) {
      StatusCode status = index == 7
          ? input.setNull(index, SqlTypeDescriptor.BOOLEAN)
          : input.setFixed(index, SqlTypeDescriptor.BOOLEAN, index & 1);
      assertEquals(StatusCode.OK, status);
    }
    Encoded row = encode(table, input);
    StoredTableRowView output = new StoredTableRowView();

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
    SqlMutationValues input = values(table, 32);
    assertEquals(StatusCode.OK, input.setText(0, varchar, "ab"));
    assertEquals(StatusCode.OK, input.setText(1, varchar, "£"));
    Encoded row = encode(table, input);
    StoredTableRowView output = new StoredTableRowView();

    byte[] corrupt = row.bytes.clone();
    int firstSlot = START + table.fixedOffsetAt(0);
    FormatBytes.putInt(ByteBuffer.wrap(corrupt), firstSlot,
        FormatBytes.getInt(ByteBuffer.wrap(corrupt), firstSlot) + 1);
    assertEquals(StatusCode.OK, decodeTrusted(
        table, ByteBuffer.wrap(corrupt), START, row.length, output));
    assertEquals('b', textByteAt(output, 0, 0));

    corrupt = row.bytes.clone();
    FormatBytes.putInt(ByteBuffer.wrap(corrupt), firstSlot, row.length);
    assertCorruptPreserves(table, row.length, corrupt, output);

    corrupt = row.bytes.clone();
    int textStart = START + FormatBytes.getInt(ByteBuffer.wrap(corrupt), firstSlot);
    corrupt[textStart] = (byte) 0xc0;
    assertEquals(StatusCode.OK, decodeTrusted(
        table, ByteBuffer.wrap(corrupt), START, row.length, output));
    assertEquals(0xc0, textByteAt(output, 0, 0));

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
  void preservesPreviousViewWhenNewRowHasInvalidTextBounds() {
    int varchar = SqlTypeDescriptor.varchar(4);
    TableDescriptor table = table(new int[] {varchar, varchar}, new boolean[] {true, true});
    SqlMutationValues input = values(table, 16);
    assertEquals(StatusCode.OK, input.setText(0, varchar, "old"));
    assertEquals(StatusCode.OK, input.setText(1, varchar, "new"));
    Encoded row = encode(table, input);

    StoredTableRowView view = new StoredTableRowView();
    assertEquals(StatusCode.OK, decodeTrusted(
        table, ByteBuffer.wrap(row.bytes), START, row.length, view));
    byte[] corrupt = row.bytes.clone();
    FormatBytes.putInt(ByteBuffer.wrap(corrupt), START + table.fixedOffsetAt(1), row.length);
    assertEquals(StatusCode.CORRUPTION, decodeTrusted(
        table, ByteBuffer.wrap(corrupt), START, row.length, view));
    assertEquals(3, view.textByteLengthAt(0));
    assertEquals('n', textByteAt(view, 1, 0));
  }

  @Test
  void preservesNullOrdinalsAcrossByteAndWordBoundaries() {
    int count = 1_024;
    TableDescriptor table = table(count, SqlTypeDescriptor.BOOLEAN, true);
    SqlMutationValues input = values(table, 0);
    int[] boundaries = {0, 7, 8, 63, 64, 255, 1_023};
    for (int index = 0; index < count; index++) {
      boolean boundary = false;
      for (int candidate : boundaries) boundary |= index == candidate;
      assertEquals(StatusCode.OK, boundary
          ? input.setNull(index, SqlTypeDescriptor.BOOLEAN)
          : input.setFixed(index, SqlTypeDescriptor.BOOLEAN, index & 1));
    }
    Encoded row = encode(table, input);
    StoredTableRowView output = new StoredTableRowView();
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
    SqlMutationValues input = values(table, 4_051 * 4);
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
    StoredTableRowView output = new StoredTableRowView();
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
        SqlMutationValues input = values(table, 0);
        for (int index = 0; index < types.length; index++) {
          long value = edge == 0
              ? SqlValueDomain.minimumFixed(types[index])
              : SqlValueDomain.exclusiveMaximumFixed(types[index]) - 1;
          assertEquals(StatusCode.OK, input.setFixed(index, types[index], value));
        }
        Encoded row = encode(table, input);
        StoredTableRowView output = new StoredTableRowView();
        assertEquals(StatusCode.OK, decodeTrusted(
            table, ByteBuffer.wrap(row.bytes), START, row.length, output));
        for (int index = 0; index < types.length; index++) {
          assertEquals(input.valueAt(index), output.valueAt(index));
        }
      }

      SqlMutationValues valid = values(table, 0);
      for (int index = 0; index < types.length; index++) {
        assertEquals(StatusCode.OK, valid.setFixed(
            index, types[index], SqlValueDomain.minimumFixed(types[index])));
      }
      Encoded row = encode(table, valid);
      byte[] corrupt = row.bytes.clone();
      FormatBytes.putLong(ByteBuffer.wrap(corrupt),
          START + table.fixedOffsetAt(column),
          SqlValueDomain.exclusiveMaximumFixed(types[column]));
      StoredTableRowView output = new StoredTableRowView();
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
    SqlMutationValues input = values(table, 0);
    long high = 542_101_086_242_752_217L;
    long low = 68_739_955_140_067_328L;
    assertEquals(StatusCode.OK, input.setDecimal128(0, decimal, high, low));
    assertEquals(StatusCode.OK, input.setFixed(1, SqlTypeDescriptor.INTEGER, 7));
    Encoded row = encode(table, input);
    StoredTableRowView output = new StoredTableRowView();
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
    SqlMutationValues input = values(table, 16);
    assertEquals(StatusCode.OK, input.setFixed(0, SqlTypeDescriptor.BIGINT, 42));
    assertEquals(StatusCode.OK, input.setText(1, varchar, "river"));
    StoredTableRowView output = new StoredTableRowView();
    ByteBuffer row = ByteBuffer.allocate(128);
    StoredTableRowEncodeResult result = new StoredTableRowEncodeResult();
    assertEquals(StatusCode.OK,
        StoredTableRowEncoder.encode(table, input, row, 0, result));
    HeapRowResult fetched = new HeapRowResult();
    fetched.set(row, 1, 0, result.length());
    exercise(table, fetched, output, 300_000);

    long thread = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(thread);
    exercise(table, fetched, output, 100_000);
    long allocated = bean.getThreadAllocatedBytes(thread) - before;
    assertEquals(true, allocated <= 256, "warmed stored row read allocated: " + allocated);
    before = bean.getThreadAllocatedBytes(thread);
    exercise(table, fetched, output, 1_000_000);
    allocated = bean.getThreadAllocatedBytes(thread) - before;
    assertEquals(true, allocated <= 256, "longer stored row read allocated: " + allocated);
  }

  private static void assertCorruptPreserves(
      TableDescriptor table, int length, byte[] bytes, StoredTableRowView output) {
    int count = output.count();
    long first = output.valueAt(0);
    assertEquals(StatusCode.CORRUPTION, decodeTrusted(
        table, ByteBuffer.wrap(bytes), START, length, output));
    assertEquals(count, output.count());
    assertEquals(first, output.valueAt(0));
  }

  private static StatusCode decodeTrusted(
      TableDescriptor table, ByteBuffer source, int start, int length,
      StoredTableRowView output) {
    return decodeTrusted(table, source, start, length, output, null, true);
  }

  private static StatusCode decodeTrusted(
      TableDescriptor table, ByteBuffer source, int start, int length,
      StoredTableRowView output, StoredTableRowIntegerFilter filter) {
    return decodeTrusted(table, source, start, length, output, filter, true);
  }

  private static StatusCode decodeTrusted(
      TableDescriptor table, ByteBuffer source, int start, int length,
      StoredTableRowView output, StoredTableRowIntegerFilter filter, boolean publishText) {
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
    return output.bind(table, source.asReadOnlyBuffer(), start, length, filter, selection);
  }

  private static Encoded encode(TableDescriptor table, SqlValueAccess input) {
    byte[] bytes = new byte[START + table.encodedMaximumRowBytes() + 1];
    StoredTableRowEncodeResult result = new StoredTableRowEncodeResult();
    assertEquals(StatusCode.OK, StoredTableRowEncoder.encode(
        table, input, ByteBuffer.wrap(bytes), START, result));
    return new Encoded(bytes, result.length());
  }

  private static SqlMutationValues values(TableDescriptor table, int textBytes) {
    SqlMutationValues result = new SqlMutationValues();
    assertEquals(StatusCode.OK, result.reserve(table, textBytes));
    assertEquals(StatusCode.OK, result.begin(table, null));
    return result;
  }

  private static int textByteAt(SqlValueAccess values, int column, int index) {
    int offset = values.textByteOffsetAt(column);
    int length = values.textByteLengthAt(column);
    return offset < 0 || index < 0 || index >= length ? -1
        : Byte.toUnsignedInt(values.textSource(column).getByte(offset + index));
  }

  private static void copyTextBytes(SqlValueAccess values, int column, byte[] target) {
    int offset = values.textByteOffsetAt(column);
    for (int index = 0; index < target.length; index++) {
      target[index] = values.textSource(column).getByte(offset + index);
    }
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
      TableDescriptor table, HeapRowResult fetched,
      StoredTableRowView output, int iterations) {
    for (int index = 0; index < iterations; index++) {
      StatusCode status = output.bindFetched(table, fetched, null, null);
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
