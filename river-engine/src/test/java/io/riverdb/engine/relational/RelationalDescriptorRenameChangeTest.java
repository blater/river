package io.riverdb.engine.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.type.SqlDefaultKind;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.schema.ColumnConstraintDescriptorSet;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import org.junit.jupiter.api.Test;

final class RelationalDescriptorRenameChangeTest {
  @Test
  void columnRenamePreservesCompositeKeyAndConstraintIdentities() {
    TableDescriptor current = constrainedTable();
    TableDescriptor.Result result = new TableDescriptor.Result();
    StatusDetail detail = new StatusDetail(128);

    assertEquals(StatusCode.OK, new RelationalDescriptorRenameChange().column(
        current, "amount", "total", result, detail), detail.toString());
    TableDescriptor renamed = result.value();
    assertEquals(current.tableId(), renamed.tableId());
    assertEquals(current.rowLayoutId(), renamed.rowLayoutId());
    assertEquals(current.catalogGeneration(), renamed.catalogGeneration());
    assertNotSame(current.columns(), renamed.columns());
    assertEquals(-1, renamed.findColumn("amount"));
    assertEquals(1, renamed.findColumn("total"));
    assertEquals(SqlDefaultKind.LITERAL, renamed.columns().defaultKindAt(1));
    assertEquals(7, renamed.columns().defaultValueAt(1));
    assertEquals(ColumnConstraintDescriptorSet.CHECK_GREATER_THAN,
        renamed.columns().checkComparisonAt(1));
    assertKey(current.primaryKey(), renamed.primaryKey());
    for (int index = 0; index < current.secondaryKeyCount(); index++) {
      assertKey(current.secondaryKeyAt(index), renamed.secondaryKeyAt(index));
    }
    assertKey(current.foreignKeyAt(0), renamed.foreignKeyAt(0));
    assertEquals(999, renamed.foreignKeyAt(0).referencedKeyId());
  }

  @Test
  void indexRenamePreservesStorageIdentityAndRejectsConstraintNames() {
    TableDescriptor current = constrainedTable();
    RelationalDescriptorRenameChange change = new RelationalDescriptorRenameChange();
    TableDescriptor.Result result = new TableDescriptor.Result();
    StatusDetail detail = new StatusDetail(128);

    assertEquals(StatusCode.OK,
        change.index(current, "ix_amount_code", "ix_total_code", result, detail));
    TableDescriptor renamed = result.value();
    assertSame(current.columns(), renamed.columns());
    assertSame(current.primaryKey(), renamed.primaryKey());
    assertSame(current.secondaryKeyAt(0), renamed.secondaryKeyAt(0));
    assertEquals(current.secondaryKeyAt(1).keyId(), renamed.secondaryKeyAt(1).keyId());
    assertEquals(true, renamed.secondaryKeyAt(1).matchesName("ix_total_code"));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        change.index(current, "uq_amount_code", "renamed_uq", result, detail));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        change.index(current, "pk_items", "renamed_pk", result, detail));
    assertEquals(StatusCode.CONFLICT,
        change.index(current, "ix_amount_code", "uq_amount_code", result, detail));
  }

  private static TableDescriptor constrainedTable() {
    int[] types = {
        SqlTypeDescriptor.INTEGER,
        SqlTypeDescriptor.decimal(22, 18),
        SqlTypeDescriptor.BIGINT
    };
    CharSequence[] names = {"tenant", "amount", "code"};
    boolean[] nullable = new boolean[types.length];
    byte[] defaultKinds = {0, (byte) SqlDefaultKind.LITERAL, 0};
    long[] defaultHighs = new long[types.length];
    long[] defaults = {0, 7, 0};
    byte[] comparisons = {
        0, (byte) ColumnConstraintDescriptorSet.CHECK_GREATER_THAN, 0
    };
    int[] checkTypes = {0, SqlTypeDescriptor.decimal(22, 18), 0};
    long[] checkHighs = new long[types.length];
    long[] checks = {0, 1, 0};
    ColumnConstraintDescriptorSet.Result constraints =
        new ColumnConstraintDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnConstraintDescriptorSet.create(
        types, defaultKinds, defaultHighs, defaults,
        comparisons, checkTypes, checkHighs, checks, types.length, constraints));
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.createConstrained(
        types, names, nullable, constraints.value(), columns, null));
    KeyDescriptor primary = key(
        31, KeyDescriptor.KIND_PRIMARY, true, columns.value(),
        new int[] {0, 1}, 0, "pk_items");
    KeyDescriptor unique = key(
        32, KeyDescriptor.KIND_UNIQUE, true, columns.value(),
        new int[] {1, 2}, 0, "uq_amount_code");
    KeyDescriptor index = key(
        33, KeyDescriptor.KIND_SECONDARY, false, columns.value(),
        new int[] {1, 2}, 0, "ix_amount_code");
    KeyDescriptor foreign = key(
        34, KeyDescriptor.KIND_FOREIGN, false, columns.value(),
        new int[] {1, 2}, 999, "fk_amount_code");
    TableDescriptor.Result table = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.create(
        30, 41, 5, columns.value(), primary,
        new KeyDescriptor[] {unique, index}, new KeyDescriptor[] {foreign}, table, null));
    return table.value();
  }

  private static KeyDescriptor key(
      long id,
      int kind,
      boolean unique,
      ColumnDescriptorSet columns,
      int[] ordinals,
      long referenced,
      String name) {
    KeyDescriptor.Result result = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.createNamed(
        id, kind, unique, columns, ordinals, referenced, name, result, null));
    return result.value();
  }

  private static void assertKey(KeyDescriptor expected, KeyDescriptor actual) {
    assertEquals(expected.keyId(), actual.keyId());
    assertEquals(expected.kind(), actual.kind());
    assertEquals(expected.partCount(), actual.partCount());
    assertEquals(expected.referencedKeyId(), actual.referencedKeyId());
    for (int part = 0; part < expected.partCount(); part++) {
      assertEquals(expected.columnOrdinalAt(part), actual.columnOrdinalAt(part));
      assertEquals(expected.typeDescriptorAt(part), actual.typeDescriptorAt(part));
    }
  }
}
