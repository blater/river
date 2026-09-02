package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.runtime.RiverRuntimeConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlBlockRowPagedStoreTest {
  @Test
  void exceedsFormerRowBoundaryWithoutResidentRowMetadata(@TempDir Path root) {
    SqlMaterializedTestFixture fixture = SqlMaterializedTestFixture.open(root);
    SqlBlockSchema schema = schema();
    SqlBlockRow row = new SqlBlockRow();
    assertEquals(StatusCode.OK, row.reset(1));
    SqlBlockRowStore store = new SqlBlockRowStore(fixture.budget());
    assertEquals(StatusCode.OK, store.begin(schema, -1, false));
    for (int value = 0; value <= 65_536; value++) {
      row.setKey(1_000_000L + value);
      row.setValue(0, value);
      assertEquals(StatusCode.OK, store.append(row));
    }
    assertEquals(StatusCode.OK, store.finish());
    assertEquals(65_537L, store.rowCount());
    assertEquals(StatusCode.OK, store.readAt(65_536L, row));
    assertEquals(65_536L, row.value(0));
    assertEquals(1_065_536L, row.key());
    assertEquals(StatusCode.OK, store.close());
    fixture.close();
  }

  @Test
  void externallyOrdersRowsAndPreservesPublicKeys(@TempDir Path root) {
    SqlMaterializedTestFixture fixture = SqlMaterializedTestFixture.open(root);
    SqlBlockRow row = new SqlBlockRow();
    assertEquals(StatusCode.OK, row.reset(1));
    SqlBlockRowStore store = new SqlBlockRowStore(fixture.budget());
    assertEquals(StatusCode.OK, store.begin(schema(), 0, true));
    append(store, row, 101, 1);
    append(store, row, 202, 3);
    append(store, row, 303, 3);
    append(store, row, 404, 2);
    assertEquals(StatusCode.OK, store.finish());
    assertRow(store, row, 0, 202, 3);
    assertRow(store, row, 1, 303, 3);
    assertRow(store, row, 2, 404, 2);
    assertRow(store, row, 3, 101, 1);
    assertEquals(StatusCode.OK, store.close());
    fixture.close();
  }

  @Test
  void ordersMultipartMixedDirectionsWithStableDuplicateTies(@TempDir Path root) {
    SqlMaterializedTestFixture fixture = SqlMaterializedTestFixture.open(root);
    SqlBlockSchema schema = new SqlBlockSchema();
    schema.set(2);
    schema.setColumn(0, "group_id", SqlTypeDescriptor.BIGINT, false);
    schema.setColumn(1, "rank", SqlTypeDescriptor.BIGINT, false);
    SqlBlockRow row = new SqlBlockRow();
    assertEquals(StatusCode.OK, row.reset(2));
    SqlBlockRowStore store = new SqlBlockRowStore(fixture.budget());
    assertEquals(StatusCode.OK, store.begin(
        schema, new int[] {0, 1}, new boolean[] {false, true}, 2));
    append(store, row, 10, 2, 4);
    append(store, row, 11, 1, 7);
    append(store, row, 12, 2, 9);
    append(store, row, 13, 1, 7);
    append(store, row, 14, 1, 3);
    assertEquals(StatusCode.OK, store.finish());
    assertRow(store, row, 0, 11, 1, 7);
    assertRow(store, row, 1, 13, 1, 7);
    assertRow(store, row, 2, 14, 1, 3);
    assertRow(store, row, 3, 12, 2, 9);
    assertRow(store, row, 4, 10, 2, 4);
    assertEquals(StatusCode.OK, store.close());
    fixture.close();
  }

  @Test
  void mergesMoreThanSixtyFourConfiguredRunsAndOddTail(@TempDir Path root)
      throws IOException {
    Files.writeString(
        root.resolve(RiverRuntimeConfig.FILE_NAME),
        "river.sql.materialized.page=8KB\n"
            + "river.sql.materialized.cache=64KB\n"
            + "river.sql.materialized.sort-run=16KB\n",
        StandardCharsets.UTF_8);
    SqlMaterializedTestFixture fixture = SqlMaterializedTestFixture.open(root);
    SqlBlockRow row = new SqlBlockRow();
    assertEquals(StatusCode.OK, row.reset(1));
    SqlBlockRowStore store = new SqlBlockRowStore(fixture.budget());
    assertEquals(StatusCode.OK, store.begin(schema(), 0, false));
    int configuredRunRows = 2 * (8_000 - 32) / Long.BYTES;
    int rowCount = configuredRunRows * 65 + 1;
    for (int key = 0; key < rowCount; key++) {
      append(store, row, key, rowCount - key);
    }
    assertEquals(StatusCode.OK, store.finish());
    for (int position = 0; position < rowCount; position++) {
      assertRow(store, row, position, rowCount - position - 1L, position + 1L);
    }
    assertEquals(StatusCode.OK, store.close());
    fixture.close();
  }

  @Test
  void warmedSortedStoreRetainsOperatorHighWaterWithoutAllocation(@TempDir Path root) {
    java.lang.management.ThreadMXBean standard = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standard instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standard;
    bean.setThreadAllocatedMemoryEnabled(true);
    SqlMaterializedTestFixture fixture = SqlMaterializedTestFixture.open(root);
    SqlBlockRow row = new SqlBlockRow();
    assertEquals(StatusCode.OK, row.reset(1));
    SqlBlockRowStore store = new SqlBlockRowStore(fixture.budget());
    SqlBlockSchema schema = schema();
    for (int iteration = 0; iteration < 100; iteration++) sortedExercise(store, row, schema);
    long before = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
    for (int iteration = 0; iteration < 100; iteration++) sortedExercise(store, row, schema);
    long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
    assertTrue(allocated <= 512, "warmed sorted store allocated bytes: " + allocated);
    assertEquals(StatusCode.OK, store.close());
    fixture.close();
  }

  private static void sortedExercise(
      SqlBlockRowStore store, SqlBlockRow row, SqlBlockSchema schema) {
    assertEquals(StatusCode.OK, store.begin(schema, 0, false));
    append(store, row, 3, 3);
    append(store, row, 1, 1);
    append(store, row, 2, 2);
    assertEquals(StatusCode.OK, store.finish());
    assertRow(store, row, 0, 1, 1);
    assertEquals(StatusCode.OK, store.close());
  }

  private static SqlBlockSchema schema() {
    SqlBlockSchema schema = new SqlBlockSchema();
    schema.set(1);
    schema.setColumn(0, "id", SqlTypeDescriptor.BIGINT, false);
    return schema;
  }

  private static void append(SqlBlockRowStore store, SqlBlockRow row, long key, long value) {
    row.setKey(key);
    row.setValue(0, value);
    assertEquals(StatusCode.OK, store.append(row));
  }

  private static void append(
      SqlBlockRowStore store, SqlBlockRow row, long key, long first, long second) {
    row.setKey(key);
    row.setValue(0, first);
    row.setValue(1, second);
    assertEquals(StatusCode.OK, store.append(row));
  }

  private static void assertRow(
      SqlBlockRowStore store, SqlBlockRow row, long position, long key, long value) {
    assertEquals(StatusCode.OK, store.readAt(position, row));
    assertEquals(key, row.key());
    assertEquals(value, row.value(0));
  }

  private static void assertRow(
      SqlBlockRowStore store,
      SqlBlockRow row,
      long position,
      long key,
      long first,
      long second) {
    assertEquals(StatusCode.OK, store.readAt(position, row));
    assertEquals(key, row.key());
    assertEquals(first, row.value(0));
    assertEquals(second, row.value(1));
  }

}
