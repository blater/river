package io.riverdb.engine.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlApproximateNumeric;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.format.btree.TupleKeyCodec;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class RelationalTupleKeyEncoderTest {
  @Test
  void encodesCanonicalApproximateCompositeKeys() {
    int[] types = {SqlTypeDescriptor.REAL, SqlTypeDescriptor.DOUBLE};
    CharSequence[] names = {"single_value", "double_value"};
    boolean[] nullable = {false, false};
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        types, 0, names, 0, nullable, 0, types.length, columns, null));
    KeyDescriptor.Result key = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.createForTest(
        KeyDescriptor.KIND_PRIMARY, true, columns.value(), new int[] {0, 1}, key));

    SqlValueBuffer values = new SqlValueBuffer();
    assertEquals(StatusCode.OK, values.reserve(2, 2, 0, 0));
    assertEquals(StatusCode.OK, values.clearForSize(2));
    assertEquals(StatusCode.OK, values.setFixed(
        0, types[0], SqlApproximateNumeric.realBits(1.25f)));
    assertEquals(StatusCode.OK, values.setFixed(
        1, types[1], SqlApproximateNumeric.doubleBits(-2.5d)));
    RelationalTupleKeyEncoder encoder = new RelationalTupleKeyEncoder();
    assertEquals(StatusCode.OK, encoder.encodeUser(key.value(), values));
    assertTrue(TupleKeyCodec.matchesShape(
        encoder.bytes(), 0, encoder.length(), key.value().shape()));
    assertEquals(StatusCode.OK, encoder.encodePhysical(key.value(), values, 1));
    assertTrue(TupleKeyCodec.matchesPhysicalIndexKey(
        encoder.bytes(), 0, encoder.length(), key.value().shape()));
  }

  @Test
  void encodesMixedCompositePhysicalKeyAndNullUserTuple() {
    int[] types = {
        SqlTypeDescriptor.BIGINT,
        SqlTypeDescriptor.varchar(16),
        SqlTypeDescriptor.BIGINT
    };
    CharSequence[] names = {"id", "region", "optional"};
    boolean[] nullable = {false, false, true};
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        types, 0, names, 0, nullable, 0, types.length, columns, null));
    KeyDescriptor.Result key = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.createForTest(
        KeyDescriptor.KIND_SECONDARY, false, columns.value(),
        new int[] {1, 2}, key));

    SqlValueBuffer values = new SqlValueBuffer();
    assertEquals(StatusCode.OK, values.reserve(3, 3, 16, 16));
    assertEquals(StatusCode.OK, values.clearForSize(3));
    assertEquals(StatusCode.OK, values.setFixed(0, types[0], 7));
    assertEquals(StatusCode.OK, values.setText(1, types[1], "Nørth"));
    assertEquals(StatusCode.OK, values.setNull(2, types[2]));

    RelationalTupleKeyEncoder encoder = new RelationalTupleKeyEncoder();
    assertEquals(StatusCode.OK, encoder.encodePhysical(key.value(), values, 91));
    assertTrue(TupleKeyCodec.matchesPhysicalIndexKey(
        encoder.bytes(), 0, encoder.length(), key.value().shape()));
    assertEquals(91, TupleKeyCodec.logicalRowId(
        encoder.bytes(), 0, encoder.length()));
    assertTrue(encoder.containsNull());

    assertEquals(StatusCode.OK, encoder.encodeUser(key.value(), values));
    assertTrue(TupleKeyCodec.matchesShape(
        encoder.bytes(), 0, encoder.length(), key.value().shape()));
    assertFalse(TupleKeyCodec.isPhysical(encoder.bytes(), 0, encoder.length()));
    assertTrue(encoder.containsNull());
  }

  @Test
  void rejectsNullPrimaryPartAndDescriptorMismatch() {
    int[] types = {SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.varchar(4)};
    CharSequence[] names = {"tenant", "code"};
    boolean[] nullable = {false, false};
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        types, 0, names, 0, nullable, 0, types.length, columns, null));
    KeyDescriptor.Result key = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.createForTest(
        KeyDescriptor.KIND_PRIMARY, true, columns.value(),
        new int[] {0, 1}, key));

    SqlValueBuffer values = new SqlValueBuffer();
    assertEquals(StatusCode.OK, values.reserve(2, 2, 4, 4));
    assertEquals(StatusCode.OK, values.clearForSize(2));
    assertEquals(StatusCode.OK, values.setNull(0, types[0]));
    assertEquals(StatusCode.OK, values.setText(1, types[1], "x"));
    RelationalTupleKeyEncoder encoder = new RelationalTupleKeyEncoder();
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        encoder.encodePhysical(key.value(), values, 1));
    assertEquals(0, encoder.length());
    assertFalse(encoder.containsNull());
    assertEquals(0, encoder.bytes().remaining());

    assertEquals(StatusCode.OK, values.clearForSize(2));
    assertEquals(StatusCode.OK, values.setFixed(0, types[0], 1));
    assertEquals(StatusCode.OK, values.setFixed(1, SqlTypeDescriptor.BIGINT, 2));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        encoder.encodePhysical(key.value(), values, 1));
  }

  @Test
  void encodesWideDecimalAsPrimarySecondaryAndCompositeKeyPart() {
    int decimal = SqlTypeDescriptor.decimal(38, 6);
    int[] types = {decimal, SqlTypeDescriptor.INTEGER};
    CharSequence[] names = {"amount", "tenant"};
    boolean[] nullable = {false, false};
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        types, 0, names, 0, nullable, 0, types.length, columns, null));
    KeyDescriptor.Result primary = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.createForTest(
        KeyDescriptor.KIND_PRIMARY, true, columns.value(), new int[] {1, 0}, primary));
    KeyDescriptor.Result secondary = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.createForTest(
        KeyDescriptor.KIND_SECONDARY, false, columns.value(), new int[] {0}, secondary));
    KeyDescriptor.Result foreign = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.createForTest(
        KeyDescriptor.KIND_FOREIGN, false, columns.value(), new int[] {1, 0},
        -1, foreign, null));

    SqlValueBuffer values = new SqlValueBuffer();
    assertEquals(StatusCode.OK, values.reserve(2, 2, 0, 0));
    assertEquals(StatusCode.OK, values.clearForSize(2));
    assertEquals(StatusCode.OK, values.setDecimal128(
        0, decimal, 542_101_086_242_752_217L, 68_739_955_140_067_328L));
    assertEquals(StatusCode.OK, values.setFixed(1, SqlTypeDescriptor.INTEGER, 9));
    RelationalTupleKeyEncoder encoder = new RelationalTupleKeyEncoder();
    assertEquals(StatusCode.OK, encoder.encodePhysical(primary.value(), values, 41));
    assertTrue(TupleKeyCodec.matchesPhysicalIndexKey(
        encoder.bytes(), 0, encoder.length(), primary.value().shape()));
    assertEquals(StatusCode.OK, encoder.encodePhysical(secondary.value(), values, 41));
    assertTrue(TupleKeyCodec.matchesPhysicalIndexKey(
        encoder.bytes(), 0, encoder.length(), secondary.value().shape()));
    assertEquals(StatusCode.OK, encoder.encodeUser(foreign.value(), values));
    assertTrue(TupleKeyCodec.matchesShape(
        encoder.bytes(), 0, encoder.length(), foreign.value().shape()));
  }

  @Test
  void warmedThirtyTwoPartEncodingAllocatesNoBytes() {
    java.lang.management.ThreadMXBean standard = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standard instanceof ThreadMXBean);
    ThreadMXBean allocations = (ThreadMXBean) standard;
    Assumptions.assumeTrue(allocations.isThreadAllocatedMemorySupported());
    allocations.setThreadAllocatedMemoryEnabled(true);

    int count = KeyDescriptor.MAXIMUM_PARTS;
    int[] types = new int[count];
    int[] ordinals = new int[count];
    CharSequence[] names = new CharSequence[count];
    boolean[] nullable = new boolean[count];
    for (int index = 0; index < count; index++) {
      types[index] = SqlTypeDescriptor.BIGINT;
      ordinals[index] = index;
      names[index] = "c" + index;
    }
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        types, 0, names, 0, nullable, 0, count, columns, null));
    KeyDescriptor.Result key = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.createForTest(
        KeyDescriptor.KIND_PRIMARY, true, columns.value(), ordinals, key));
    SqlValueBuffer values = new SqlValueBuffer();
    assertEquals(StatusCode.OK, values.reserve(count, count, 0, 0));
    assertEquals(StatusCode.OK, values.clearForSize(count));
    for (int index = 0; index < count; index++) {
      assertEquals(StatusCode.OK, values.setFixed(index, types[index], index + 1L));
    }
    RelationalTupleKeyEncoder encoder = new RelationalTupleKeyEncoder();
    for (int index = 0; index < 10_000; index++) {
      assertEquals(StatusCode.OK, encoder.encodePhysical(key.value(), values, index + 1L));
    }
    long thread = Thread.currentThread().threadId();
    long before = allocations.getThreadAllocatedBytes(thread);
    for (int index = 0; index < 1_000; index++) {
      if (!encoder.encodePhysical(key.value(), values, index + 1L).isOk()) {
        throw new AssertionError("warmed composite key encode failed");
      }
    }
    long allocated = allocations.getThreadAllocatedBytes(thread) - before;
    assertEquals(0, allocated);
  }
}
