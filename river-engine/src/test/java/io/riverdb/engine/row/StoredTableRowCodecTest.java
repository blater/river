package io.riverdb.engine.row;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.base.type.SqlValueDomain;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.row.StoredTableRowHeaderCodec;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class StoredTableRowCodecTest {
  private static final int START = 5;
  private static volatile long allocationGuard;

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

    StoredTableRowCodec codec = new StoredTableRowCodec();
    assertEquals(StatusCode.OK, codec.encode(table, 31, input, row, START, encoded));
    assertEquals(2, row.position());
    assertEquals(200, row.limit());
    assertEquals(ByteOrder.BIG_ENDIAN, row.order());
    assertEquals(31, FormatBytes.getLong(row, START + 24));
    assertEquals(10, FormatBytes.getInt(row, START + table.fixedOffsetAt(3) + 4));

    SqlValueBuffer output = values(4, 32);
    assertEquals(StatusCode.OK,
        codec.decode(table, 31, row, START, encoded.length(), output));
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
    StoredTableRowCodec codec = new StoredTableRowCodec();

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        codec.encode(table, 1, wrong, ByteBuffer.wrap(bytes), 0, result));
    assertArrayEquals(before, bytes);
    assertEquals(0, result.length());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        codec.encode(table, 0, wrong, ByteBuffer.wrap(bytes), 0, result));
    assertArrayEquals(before, bytes);
    SqlValueBuffer right = values(1, 0);
    assertEquals(StatusCode.OK, right.setFixed(0, SqlTypeDescriptor.BIGINT, 1));
    ByteBuffer shortTarget = ByteBuffer.wrap(bytes).limit(39);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        codec.encode(table, 1, right, shortTarget, 0, result));
    assertArrayEquals(before, bytes);
  }

  @Test
  void rejectsNoncanonicalBitmapNullSlotsAndFixedValuesBeforePublish() {
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
    corrupt[START + StoredTableRowHeaderCodec.HEADER_BYTES + 1] |= (byte) 0x80;
    assertCorruptPreserves(table, row.length, corrupt, output);

    corrupt = row.bytes.clone();
    corrupt[START + table.fixedOffsetAt(7)] = 1;
    assertCorruptPreserves(table, row.length, corrupt, output);

    corrupt = row.bytes.clone();
    corrupt[START + table.fixedOffsetAt(0)] = 2;
    assertCorruptPreserves(table, row.length, corrupt, output);
  }

  @Test
  void rejectsTextGapsMalformedUtf8WrongIdentityAndTrailingBytes() {
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
    assertCorruptPreserves(table, row.length, corrupt, output);

    corrupt = row.bytes.clone();
    int textStart = START + FormatBytes.getInt(ByteBuffer.wrap(corrupt), firstSlot);
    corrupt[textStart] = (byte) 0xc0;
    assertCorruptPreserves(table, row.length, corrupt, output);

    corrupt = row.bytes.clone();
    FormatBytes.putInt(ByteBuffer.wrap(corrupt), firstSlot + Integer.BYTES, -1);
    assertCorruptPreserves(table, row.length, corrupt, output);

    corrupt = row.bytes.clone();
    FormatBytes.putInt(
        ByteBuffer.wrap(corrupt), firstSlot + Integer.BYTES, Integer.MAX_VALUE);
    assertCorruptPreserves(table, row.length, corrupt, output);

    corrupt = row.bytes.clone();
    StoredTableRowHeaderCodec.encode(ByteBuffer.wrap(corrupt), START, 99, 71);
    assertCorruptPreserves(table, row.length, corrupt, output);

    assertEquals(StatusCode.CORRUPTION, new StoredTableRowCodec().decode(
        table, 71, ByteBuffer.wrap(row.bytes), START, row.length + 1, output));
    assertEquals(StatusCode.CORRUPTION, new StoredTableRowCodec().decode(
        table, 72, ByteBuffer.wrap(row.bytes), START, row.length, output));
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
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, new StoredTableRowCodec().decode(
        table, 71, ByteBuffer.wrap(row.bytes), START, row.length, tooFewLanes));
    assertEquals(1, tooFewLanes.count());
    assertEquals(4, tooFewLanes.textByteLengthAt(0));

    SqlValueBuffer tooLittleText = values(2, 1);
    assertEquals(StatusCode.OK, tooLittleText.setNull(0, varchar));
    assertEquals(StatusCode.OK, tooLittleText.setText(1, varchar, "x"));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, new StoredTableRowCodec().decode(
        table, 71, ByteBuffer.wrap(row.bytes), START, row.length, tooLittleText));
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
    assertEquals(StatusCode.OK, new StoredTableRowCodec().decode(
        table, 71, ByteBuffer.wrap(row.bytes), START, row.length, output));
    for (int boundary : boundaries) assertEquals(true, output.isNull(boundary));
    assertEquals(false, output.isNull(6));
    assertEquals(false, output.isNull(65));
  }

  @Test
  void encodesAndDecodesTheExactEightKilobyteBoundary() {
    int[] types = new int[10];
    boolean[] nullable = new boolean[10];
    types[0] = SqlTypeDescriptor.varchar(238);
    for (int index = 1; index < 8; index++) types[index] = SqlTypeDescriptor.varchar(255);
    types[8] = SqlTypeDescriptor.BOOLEAN;
    types[9] = SqlTypeDescriptor.BOOLEAN;
    TableDescriptor table = table(types, nullable);
    assertEquals(8_192, table.encodedMaximumRowBytes());
    SqlValueBuffer input = values(10, 8_092);
    for (int index = 0; index < 8; index++) {
      int scalars = index == 0 ? 238 : 255;
      assertEquals(StatusCode.OK,
          input.setText(index, types[index], supplementaryText(scalars), 0, scalars * 2));
    }
    assertEquals(StatusCode.OK, input.setFixed(8, SqlTypeDescriptor.BOOLEAN, 0));
    assertEquals(StatusCode.OK, input.setFixed(9, SqlTypeDescriptor.BOOLEAN, 1));
    byte[] bytes = new byte[8_192];
    StoredTableRowEncodeResult result = new StoredTableRowEncodeResult();
    assertEquals(StatusCode.OK, new StoredTableRowCodec().encode(
        table, 71, input, ByteBuffer.wrap(bytes), 0, result));
    assertEquals(8_192, result.length());
    SqlValueBuffer output = values(10, 8_092);
    assertEquals(StatusCode.OK, new StoredTableRowCodec().decode(
        table, 71, ByteBuffer.wrap(bytes), 0, result.length(), output));
    assertEquals(1, output.valueAt(9));
    assertEquals(1_020, output.textByteLengthAt(7));
  }

  @Test
  void roundTripsTemporalAndDecimalDomainBoundariesAndRejectsOverflow() {
    int[] types = {
      SqlTypeDescriptor.decimal(18, 2),
      SqlTypeDescriptor.time(6),
      SqlTypeDescriptor.timestamp(6),
      SqlTypeDescriptor.timestampWithTimeZone(6)
    };
    TableDescriptor table = table(types, new boolean[types.length]);
    StoredTableRowCodec codec = new StoredTableRowCodec();
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
        assertEquals(StatusCode.OK, codec.decode(
            table, 71, ByteBuffer.wrap(row.bytes), START, row.length, output));
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
      assertCorruptPreserves(table, row.length, corrupt, output);
    }
  }

  @Test
  void roundTripsWideDecimalAndRejectsNoncanonicalStoredValue() {
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
    assertEquals(StatusCode.OK, new StoredTableRowCodec().decode(
        table, 71, ByteBuffer.wrap(row.bytes), START, row.length, output));
    assertEquals(high, output.highValueAt(0));
    assertEquals(low, output.valueAt(0));

    byte[] corrupt = row.bytes.clone();
    FormatBytes.putLong(
        ByteBuffer.wrap(corrupt), START + table.fixedOffsetAt(0), Long.MAX_VALUE);
    assertCorruptPreserves(table, row.length, corrupt, output);
  }

  @Test
  void warmedCodecDoesNotAllocatePerRow() {
    ThreadMXBean bean = allocationBean();
    int varchar = SqlTypeDescriptor.varchar(8);
    TableDescriptor table = table(
        new int[] {SqlTypeDescriptor.BIGINT, varchar}, new boolean[] {false, false});
    SqlValueBuffer input = values(2, 16);
    assertEquals(StatusCode.OK, input.setFixed(0, SqlTypeDescriptor.BIGINT, 42));
    assertEquals(StatusCode.OK, input.setText(1, varchar, "river"));
    SqlValueBuffer output = values(2, 16);
    ByteBuffer row = ByteBuffer.allocate(128);
    StoredTableRowCodec codec = new StoredTableRowCodec();
    StoredTableRowEncodeResult result = new StoredTableRowEncodeResult();
    exercise(codec, table, input, output, row, result, 10_000);

    long thread = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(thread);
    exercise(codec, table, input, output, row, result, 100_000);
    long allocated = bean.getThreadAllocatedBytes(thread) - before;
    assertEquals(true, allocated <= 256, "warmed row codec allocated: " + allocated);
  }

  private static void assertCorruptPreserves(
      TableDescriptor table, int length, byte[] bytes, SqlValueBuffer output) {
    int count = output.count();
    long first = output.valueAt(0);
    assertEquals(StatusCode.CORRUPTION, new StoredTableRowCodec().decode(
        table, 71, ByteBuffer.wrap(bytes), START, length, output));
    assertEquals(count, output.count());
    assertEquals(first, output.valueAt(0));
  }

  private static Encoded encode(TableDescriptor table, SqlValueBuffer input) {
    byte[] bytes = new byte[START + table.encodedMaximumRowBytes() + 1];
    StoredTableRowEncodeResult result = new StoredTableRowEncodeResult();
    assertEquals(StatusCode.OK, new StoredTableRowCodec().encode(
        table, 71, input, ByteBuffer.wrap(bytes), START, result));
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
      StoredTableRowCodec codec, TableDescriptor table, SqlValueBuffer input,
      SqlValueBuffer output, ByteBuffer row, StoredTableRowEncodeResult result,
      int iterations) {
    for (int index = 0; index < iterations; index++) {
      codec.encode(table, 71, input, row, 0, result);
      codec.decode(table, 71, row, 0, result.length(), output);
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
