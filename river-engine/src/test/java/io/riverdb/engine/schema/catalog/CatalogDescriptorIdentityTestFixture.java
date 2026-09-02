package io.riverdb.engine.schema.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;

final class CatalogDescriptorIdentityTestFixture {
  private CatalogDescriptorIdentityTestFixture() { }

  static TableDescriptor table() {
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.BIGINT},
        new CharSequence[] {"a", "b"}, new boolean[2], columns));
    KeyDescriptor primary = key(1, KeyDescriptor.KIND_PRIMARY, true, 0, columns.value(), 0);
    KeyDescriptor secondary = key(2, KeyDescriptor.KIND_SECONDARY, false, 0,
        columns.value(), 1);
    KeyDescriptor foreign = key(3, KeyDescriptor.KIND_FOREIGN, false, 999,
        columns.value(), 1);
    TableDescriptor.Result result = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.createForTest(columns.value(), primary,
        new KeyDescriptor[] {secondary}, new KeyDescriptor[] {foreign}, result));
    return result.value();
  }

  static TableDescriptor tableWithoutKeys() {
    TableDescriptor.Result result = new TableDescriptor.Result();
    assertEquals(StatusCode.OK,
        TableDescriptor.createForTest(table().columns(), null, null, null, result));
    return result.value();
  }

  private static KeyDescriptor key(long id, int kind, boolean unique, long reference,
      ColumnDescriptorSet columns, int ordinal) {
    KeyDescriptor.Result result = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.create(id, kind, unique, columns,
        new int[] {ordinal}, reference, result, null));
    return result.value();
  }
}
