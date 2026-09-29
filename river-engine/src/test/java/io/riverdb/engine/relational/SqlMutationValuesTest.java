package io.riverdb.engine.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.BoundedByteSource;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.TableDescriptor;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class SqlMutationValuesTest {
  private static volatile long allocationGuard;

  @Test
  void retainsTypedScalarsNullsAndUtf8() {
    int text = SqlTypeDescriptor.varchar(4);
    int decimal = SqlTypeDescriptor.decimal(38, 7);
    TableDescriptor table = table(SqlTypeDescriptor.BIGINT, text, decimal);
    SqlMutationValues values = prepared(table, 32);
    assertEquals(StatusCode.OK, values.setFixed(0, SqlTypeDescriptor.BIGINT, 42));
    assertEquals(StatusCode.OK, values.setText(1, text, "A£河🌊"));
    assertEquals(StatusCode.OK, values.setDecimal128(
        2, decimal, 542_101_086_242_752_217L, 68_739_955_140_067_328L));
    assertEquals(42, values.valueAt(0));
    assertEquals(10, values.textByteLengthAt(1));
    assertEquals("A£河🌊", text(values, 1));
    assertEquals(542_101_086_242_752_217L, values.highValueAt(2));
    assertEquals(68_739_955_140_067_328L, values.valueAt(2));
    assertEquals(StatusCode.OK, values.begin(table, null));
    assertEquals(StatusCode.OK, values.setNull(1, text));
    assertTrue(values.isNull(1));
    assertEquals(-1, values.textByteLengthAt(1));
  }

  @Test
  void rejectsMalformedAndNarrowTextWithoutPublishingAChange() {
    int text = SqlTypeDescriptor.varchar(1);
    TableDescriptor table = table(text);
    SqlMutationValues values = prepared(table, 8);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        values.setTextBytes(0, text, ByteBuffer.wrap(new byte[] {(byte) 0xc0}), 0, 1));
    assertEquals(0, values.descriptorAt(0));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, values.setText(0, text, "ab"));
    assertEquals(0, values.descriptorAt(0));
    assertEquals(StatusCode.OK, values.setText(0, text, "🌊"));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, values.setText(0, text, "x"));
    assertEquals("🌊", text(values, 0));
  }

  @Test
  void unchangedValuesBorrowTheOriginalWithoutASecondTextCopy() {
    int text = SqlTypeDescriptor.varchar(8);
    TableDescriptor table = table(SqlTypeDescriptor.BIGINT, text);
    SqlMutationValues original = prepared(table, 32);
    assertEquals(StatusCode.OK, original.setFixed(0, SqlTypeDescriptor.BIGINT, 7));
    assertEquals(StatusCode.OK, original.setText(1, text, "多🙂"));
    SqlMutationValues changed = prepared(table, 32);
    assertEquals(StatusCode.OK, changed.begin(table, original));
    assertEquals(StatusCode.OK, changed.setFixed(0, SqlTypeDescriptor.BIGINT, 9));
    assertSame(original.textSource(1), changed.textSource(1));
    assertEquals(original.textByteOffsetAt(1), changed.textByteOffsetAt(1));
    assertEquals("多🙂", text(changed, 1));
    assertEquals(7, original.valueAt(0));
    assertEquals(9, changed.valueAt(0));
  }

  @Test
  void rejectsUnadmittedSourceBeforeBorrowingAnyValues() {
    int text = SqlTypeDescriptor.varchar(8);
    TableDescriptor table = table(text);
    SqlMutationValues values = prepared(table, 8);
    SqlValueAccess forged = new SqlValueAccess() {
      @Override public int count() { return 1; }
      @Override public int descriptorAt(int column) { return text; }
      @Override public boolean isNull(int column) { return false; }
      @Override public long valueAt(int column) { return 0; }
      @Override public long highValueAt(int column) { return 0; }
      @Override public int textByteLengthAt(int column) { return 1; }
      @Override public int textByteOffsetAt(int column) { return 0; }
      @Override public BoundedByteSource textSource(int column) {
        return new BoundedByteSource() {
          @Override public int length() { return 1; }
          @Override public byte getByte(int offset) { return (byte) 0xc0; }
        };
      }
      @Override public int copyTextChars(int column, char[] target, int offset) {
        throw new AssertionError("forged source was consumed");
      }
    };
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, values.begin(table, forged));
    assertEquals(0, values.descriptorAt(0));
    assertEquals(StatusCode.OK, values.setText(0, text, "valid"));
    assertEquals("valid", text(values, 0));
  }

  @Test
  void warmedGenerationReuseDoesNotAllocatePerRow() {
    ThreadMXBean bean = allocationBean();
    TableDescriptor table = table(SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.varchar(8));
    SqlMutationValues values = prepared(table, 32);
    char[] chars = "river".toCharArray();
    exercise(values, table, chars, 100_000);
    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    exercise(values, table, chars, 100_000);
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;
    assertTrue(allocated <= 512, "warmed changed values allocated: " + allocated);
  }

  private static void exercise(
      SqlMutationValues values, TableDescriptor table, char[] chars, int iterations) {
    for (int index = 0; index < iterations; index++) {
      values.begin(table, null);
      values.setFixed(0, SqlTypeDescriptor.BIGINT, index);
      values.setText(1, SqlTypeDescriptor.varchar(8), chars, 0, chars.length);
      allocationGuard += values.valueAt(0) + values.textByteLengthAt(1);
    }
  }

  private static String text(SqlValueAccess values, int column) {
    int offset = values.textByteOffsetAt(column);
    int length = values.textByteLengthAt(column);
    byte[] bytes = new byte[length];
    for (int index = 0; index < length; index++) {
      bytes[index] = values.textSource(column).getByte(offset + index);
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static SqlMutationValues prepared(TableDescriptor table, int textBytes) {
    SqlMutationValues values = new SqlMutationValues();
    assertEquals(StatusCode.OK, values.reserve(table, textBytes));
    assertEquals(StatusCode.OK, values.begin(table, null));
    return values;
  }

  private static TableDescriptor table(int... types) {
    CharSequence[] names = new CharSequence[types.length];
    boolean[] nullable = new boolean[types.length];
    for (int index = 0; index < types.length; index++) {
      names[index] = "c" + index;
      nullable[index] = true;
    }
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(types, names, nullable, columns));
    TableDescriptor.Result table = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.create(
        1, 1, 1, columns.value(), null, null, null, table, null));
    return table.value();
  }

  private static ThreadMXBean allocationBean() {
    java.lang.management.ThreadMXBean standard = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standard instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standard;
    Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    bean.setThreadAllocatedMemoryEnabled(true);
    return bean;
  }
}
