package io.riverdb.engine.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.format.btree.TupleKeyBuilder;
import io.riverdb.storage.heap.HeapPage;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class SchemaDescriptorTest {
  @Test
  void buildsImmutableColumnsWithPackedUnicodeNamesAndLookup() {
    int[] types = {
      SqlTypeDescriptor.BIGINT,
      SqlTypeDescriptor.varchar(16),
      SqlTypeDescriptor.BOOLEAN
    };
    CharSequence[] names = {"id", "café😀", "flag"};
    boolean[] nullable = {false, true, false};
    ColumnDescriptorSet.Result result = new ColumnDescriptorSet.Result();
    StatusDetail detail = new StatusDetail(64);

    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        types, names, nullable, result, detail));
    ColumnDescriptorSet columns = result.value();
    assertNotNull(columns);
    assertEquals(3, columns.count());
    assertEquals(SqlTypeDescriptor.varchar(16), columns.typeDescriptorAt(1));
    assertTrue(columns.isNullable(1));
    assertFalse(columns.isNullable(2));
    assertEquals(1, columns.find("café😀"));
    assertEquals(-1, columns.find("cafe😀"));
    assertEquals(0, columns.find("id"));
    assertEquals(9, columns.nameByteLength(1));
    assertTrue(columns.byteCharge() > columns.nameByteLength(1));
    char[] copied = new char[8];
    assertEquals(6, columns.copyNameChars(1, copied, 0));
    assertEquals("café😀", new String(copied, 0, 6));
  }

  @Test
  void rejectsMalformedNamesDuplicateNamesAndExcessColumns() {
    ColumnDescriptorSet.Result result = new ColumnDescriptorSet.Result();
    StatusDetail detail = new StatusDetail(64);
    int[] types = {SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.BIGINT};
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, ColumnDescriptorSet.create(
        types, new CharSequence[] {"id", "id"}, new boolean[] {false, true}, result, detail));
    assertNull(result.value());

    char[] malformed = {'x', (char) 0xd800};
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, ColumnDescriptorSet.create(
        new int[] {SqlTypeDescriptor.BIGINT},
        new CharSequence[] {new String(malformed)},
        new boolean[] {false}, result, detail));

    int[] tooMany = new int[SqlShapeLimits.MAX_TABLE_COLUMNS + 1];
    CharSequence[] tooManyNames = new CharSequence[tooMany.length];
    boolean[] tooManyNullability = new boolean[tooMany.length];
    for (int index = 0; index < tooMany.length; index++) tooManyNames[index] = "c" + index;
    for (int index = 0; index < tooMany.length; index++) tooMany[index] = SqlTypeDescriptor.BIGINT;
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, ColumnDescriptorSet.create(
        tooMany, tooManyNames, tooManyNullability, result, detail));
  }

  @Test
  void rejectsCheckLiteralsIncompatibleWithTheirOwnerColumn() {
    ColumnConstraintDescriptorSet.Result result =
        new ColumnConstraintDescriptorSet.Result();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, ColumnConstraintDescriptorSet.create(
        new int[] {SqlTypeDescriptor.BOOLEAN},
        new byte[] {0}, new long[] {0}, new long[] {0},
        new byte[] {ColumnConstraintDescriptorSet.CHECK_EQUAL},
        new int[] {SqlTypeDescriptor.BIGINT}, new long[] {0}, new long[] {1},
        1, result));
    assertNull(result.value());

    assertEquals(StatusCode.OK, ColumnConstraintDescriptorSet.create(
        new int[] {SqlTypeDescriptor.decimal(22, 18)},
        new byte[] {0}, new long[] {0}, new long[] {0},
        new byte[] {ColumnConstraintDescriptorSet.CHECK_GREATER_OR_EQUAL},
        new int[] {SqlTypeDescriptor.INTEGER}, new long[] {0}, new long[] {1},
        1, result));
    assertNotNull(result.value());
  }

  @Test
  void buildsTupleAndCompositeKeyWithIndependentLimits() {
    ColumnDescriptorSet.Result columnsResult = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {
          SqlTypeDescriptor.BIGINT,
          SqlTypeDescriptor.varchar(12),
          SqlTypeDescriptor.BOOLEAN
        },
        new CharSequence[] {"id", "name", "active"},
        new boolean[] {false, false, true},
        columnsResult));
    ColumnDescriptorSet columns = columnsResult.value();
    KeyDescriptor.Result keyResult = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.createForTest(
        KeyDescriptor.KIND_PRIMARY,
        true,
        columns,
        new int[] {0, 1},
        keyResult));
    KeyDescriptor key = keyResult.value();
    assertEquals(2, key.partCount());
    assertEquals(0, key.columnOrdinalAt(0));
    assertEquals(SqlTypeDescriptor.varchar(12), key.typeDescriptorAt(1));
    assertEquals(SqlTypeDescriptor.COMPARISON_TEXT, key.comparisonFamilyAt(1));
    assertEquals(0, key.referencedKeyId());
    assertTrue(key.maximumEncodedBytes() <= SqlShapeLimits.MAX_INDEX_USER_KEY_BYTES);

    TupleShape.Result shapeResult = new TupleShape.Result();
    assertEquals(StatusCode.OK, TupleShape.create(
        new int[] {SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.BOOLEAN}, shapeResult));
    assertEquals(2, shapeResult.value().partCount());
    assertEquals(23, shapeResult.value().maximumEncodedBytes());
    assertEquals(31, shapeResult.value().maximumPhysicalEncodedBytes());
  }

  @Test
  void rejectsDuplicateAndOutOfRangeKeyParts() {
    ColumnDescriptorSet.Result columnsResult = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.BIGINT},
        new CharSequence[] {"a", "b"}, new boolean[] {false, false}, columnsResult));
    KeyDescriptor.Result result = new KeyDescriptor.Result();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, KeyDescriptor.createForTest(
        KeyDescriptor.KIND_UNIQUE, true, columnsResult.value(), new int[] {0, 0}, result));
    assertNull(result.value());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, KeyDescriptor.createForTest(
        KeyDescriptor.KIND_SECONDARY, false, columnsResult.value(), new int[] {2}, result));
  }

  @Test
  void tupleShapeMaximumMatchesCanonicalBuilderAndKeyAdmission() {
    int[] descriptors = {
      SqlTypeDescriptor.BIGINT,
      SqlTypeDescriptor.BOOLEAN,
      SqlTypeDescriptor.DATE,
      SqlTypeDescriptor.varchar(3)
    };
    TupleShape.Result shape = new TupleShape.Result();
    assertEquals(StatusCode.OK, TupleShape.create(descriptors, shape));
    ByteBuffer bytes = ByteBuffer.allocate(shape.value().maximumEncodedBytes());
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginTuple(bytes, 0, descriptors.length));
    assertEquals(StatusCode.OK, builder.addFixed(descriptors[0], Long.MIN_VALUE));
    assertEquals(StatusCode.OK, builder.addFixed(descriptors[1], 1));
    assertEquals(StatusCode.OK, builder.addFixed(descriptors[2], 0));
    assertEquals(StatusCode.OK, builder.addText(descriptors[3], "abc"));
    assertEquals(StatusCode.OK, builder.finishTuple());
    assertEquals(shape.value().maximumEncodedBytes(), builder.keyBytes());

    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {
          SqlTypeDescriptor.varchar(255),
          SqlTypeDescriptor.varchar(255),
          SqlTypeDescriptor.varchar(255)
        },
        new CharSequence[] {"a", "b", "c"},
        new boolean[] {false, false, false},
        columns));
    KeyDescriptor.Result key = new KeyDescriptor.Result();
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, KeyDescriptor.createForTest(
        KeyDescriptor.KIND_PRIMARY, true, columns.value(), new int[] {0, 1, 2}, key));
    assertNull(key.value());
  }

  @Test
  void requiresBoundPositiveKeyIdsAndRejectsNullablePrimaryParts() {
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.BIGINT},
        new CharSequence[] {"id", "optional"}, new boolean[] {false, true}, columns));
    KeyDescriptor.Result result = new KeyDescriptor.Result();
    StatusDetail detail = new StatusDetail(64);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, KeyDescriptor.create(
        0, KeyDescriptor.KIND_PRIMARY, true, columns.value(), new int[] {0}, 0, result, detail));
    assertNull(result.value());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, KeyDescriptor.createForTest(
        KeyDescriptor.KIND_PRIMARY, true, columns.value(), new int[] {1}, result, detail));
    assertNull(result.value());
    assertEquals(StatusCode.OK, KeyDescriptor.create(
        19, KeyDescriptor.KIND_PRIMARY, true, columns.value(), new int[] {0}, 0, result, detail));
    assertEquals(19, result.value().keyId());
  }

  @Test
  void productionTablesRejectUnboundAndDuplicateKeyIds() {
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.BIGINT},
        new CharSequence[] {"id", "lookup"}, new boolean[] {false, false}, columns));
    KeyDescriptor.Result unbound = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.createForTest(
        KeyDescriptor.KIND_PRIMARY, true, columns.value(), new int[] {0}, unbound));
    TableDescriptor.Result table = new TableDescriptor.Result();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, TableDescriptor.create(
        1, 1, 1, columns.value(), unbound.value(), null, null, table, null));
    assertNull(table.value());
    assertEquals(StatusCode.OK, TableDescriptor.createForTest(
        columns.value(), unbound.value(), null, null, table));

    KeyDescriptor.Result first = new KeyDescriptor.Result();
    KeyDescriptor.Result second = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.create(
        31, KeyDescriptor.KIND_SECONDARY, false, columns.value(), new int[] {0},
        0, first, null));
    assertEquals(StatusCode.OK, KeyDescriptor.create(
        31, KeyDescriptor.KIND_SECONDARY, false, columns.value(), new int[] {1},
        0, second, null));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, TableDescriptor.create(
        1, 1, 1, columns.value(), null,
        new KeyDescriptor[] {first.value(), second.value()}, null, table, null));
    assertNull(table.value());
  }

  @Test
  void computesPhysicalLayoutAndAdmitsMaximumBigintColumns() {
    ColumnDescriptorSet.Result columnsResult = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {
          SqlTypeDescriptor.BOOLEAN,
          SqlTypeDescriptor.DATE,
          SqlTypeDescriptor.varchar(12)
        },
        new CharSequence[] {"active", "day", "name"},
        new boolean[] {false, true, true},
        columnsResult));
    TableDescriptor.Result result = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.create(
        7, 3, 9, columnsResult.value(), null, null, null, result, null));
    TableDescriptor table = result.value();
    assertEquals(1, table.nullBitmapBytes());
    assertEquals(1, table.fixedWidthAt(0));
    assertEquals(4, table.fixedWidthAt(1));
    assertEquals(8, table.fixedWidthAt(2));
    assertTrue(table.fixedOffsetAt(1) > table.fixedOffsetAt(0));
    assertTrue(table.encodedMaximumRowBytes() <= HeapPage.MAXIMUM_ROW_BYTES);
    assertEquals(7, table.tableId());
    assertEquals(3, table.rowLayoutId());
    assertEquals(9, table.catalogGeneration());

    int[] wideTypes = new int[1_024];
    CharSequence[] wideNames = new CharSequence[wideTypes.length];
    boolean[] wideNullability = new boolean[wideTypes.length];
    for (int index = 0; index < wideTypes.length; index++) {
      wideTypes[index] = SqlTypeDescriptor.BIGINT;
      wideNames[index] = "c" + index;
    }
    ColumnDescriptorSet.Result wideColumns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        wideTypes, wideNames, wideNullability, wideColumns));
    TableDescriptor.Result wideTable = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.createForTest(
        wideColumns.value(), null, null, null, wideTable));
    assertEquals(8_352, wideTable.value().encodedMaximumRowBytes());
  }

  @Test
  void checksCompleteWorstCaseRowAtSingleHeapRowBoundary() {
    int[] exactTypes = {
      SqlTypeDescriptor.varchar(4_043),
      SqlTypeDescriptor.BOOLEAN,
      SqlTypeDescriptor.BOOLEAN,
      SqlTypeDescriptor.BOOLEAN
    };
    CharSequence[] exactNames = {"v0", "b1", "b2", "b3"};
    boolean[] exactNullable = new boolean[exactTypes.length];
    ColumnDescriptorSet.Result exact = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        exactTypes, exactNames, exactNullable, exact));
    TableDescriptor.Result exactTable = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.createForTest(
        exact.value(), null, null, null, exactTable));
    assertEquals(HeapPage.MAXIMUM_ROW_BYTES, exactTable.value().encodedMaximumRowBytes());

    int[] overTypes = {
      SqlTypeDescriptor.varchar(4_043),
      SqlTypeDescriptor.BOOLEAN,
      SqlTypeDescriptor.BOOLEAN,
      SqlTypeDescriptor.BOOLEAN,
      SqlTypeDescriptor.BOOLEAN
    };
    CharSequence[] overNames = {"v0", "b1", "b2", "b3", "b4"};
    boolean[] overNullable = new boolean[overTypes.length];
    ColumnDescriptorSet.Result over = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        overTypes, overNames, overNullable, over));
    TableDescriptor.Result overTable = new TableDescriptor.Result();
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, TableDescriptor.createForTest(
        over.value(), null, null, null, overTable));
    assertNull(overTable.value());
  }
}
