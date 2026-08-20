package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import org.junit.jupiter.api.Test;

final class SqlBlockRowStoreTest {
  @Test
  void enforcesRowBoundAndReusesWarmStoreAfterSpill() {
    SqlBlockSchema schema = new SqlBlockSchema();
    schema.set(1);
    schema.setColumn(0, "id", SqlTypeDescriptor.BIGINT, false);
    SqlBlockRow row = new SqlBlockRow();
    row.reset(1);
    SqlBlockRowStore store = new SqlBlockRowStore();
    assertEquals(StatusCode.OK, store.begin(schema, -1, false));
    for (int value = 0; value < SqlBlockRowStore.MAXIMUM_ROWS; value++) {
      row.setValue(0, value);
      assertEquals(StatusCode.OK, store.append(row));
    }
    assertTrue(store.spilled());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, store.append(row));
    assertEquals(StatusCode.OK, store.finish());
    assertEquals(StatusCode.OK, store.next(row));
    assertEquals(0, row.value(0));
    assertEquals(StatusCode.OK, store.close());

    assertEquals(StatusCode.OK, store.begin(schema, -1, false));
    row.setValue(0, 7);
    assertEquals(StatusCode.OK, store.append(row));
    assertEquals(StatusCode.OK, store.finish());
    assertEquals(StatusCode.OK, store.next(row));
    assertEquals(7, row.value(0));
    assertEquals(StatusCode.CONFLICT, store.next(row));
    assertEquals(StatusCode.OK, store.close());
  }
}
