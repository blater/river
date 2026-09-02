package io.riverdb.engine.schema.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;

final class CatalogIndexRootTableFixture {
  private CatalogIndexRootTableFixture() {
  }

  static TableDescriptor table() {
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.BIGINT},
        new CharSequence[] {"a", "b"}, new boolean[2], columns));
    KeyDescriptor primary = key(21, KeyDescriptor.KIND_PRIMARY, true, columns.value(), 0);
    KeyDescriptor secondary = key(22, KeyDescriptor.KIND_SECONDARY, false, columns.value(), 1);
    TableDescriptor.Result result = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.create(
        11, 12, 14, columns.value(), primary, new KeyDescriptor[] {secondary}, null,
        result, null));
    return result.value();
  }

  private static KeyDescriptor key(long id, int kind, boolean unique,
      ColumnDescriptorSet columns, int ordinal) {
    KeyDescriptor.Result result = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.create(
        id, kind, unique, columns, new int[] {ordinal}, 0, result, null));
    return result.value();
  }
}
