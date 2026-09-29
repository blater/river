package io.riverdb.engine.relational;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.table.IndexedTupleScanResult;
import io.riverdb.storage.btree.TupleBTreeScanBounds;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.lock.LockMode;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RelationalClusteredReadAllocationTest {
  private static volatile long readGuard;

  @Test
  void warmedPrimaryPointsAndPrimarySecondaryScansBorrowWithoutAllocation(@TempDir Path root)
      throws ReflectiveOperationException {
    Assumptions.assumeTrue(ManagementFactory.getThreadMXBean() instanceof ThreadMXBean);
    ThreadMXBean allocations = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    allocations.setThreadAllocatedMemoryEnabled(true);
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK, RelationalDatabase.create(databaseRequest(8), root,
        DatabaseIncarnation.of(503, 509), WalGeneration.of(1), 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    StatusDetail detail = new StatusDetail(128);
    assertEquals(StatusCode.OK, database.services().descriptors().create(table(), table, detail));
    long tableId = table.tableId();
    RelationalSessionOpenResult sessionResult = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RelationalSession session = sessionResult.session();
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    SqlMutationValues values = new SqlMutationValues();
    assertEquals(StatusCode.OK, values.reserve(table.descriptor(), 0));
    RelationalRowIdentityResult identity = new RelationalRowIdentityResult();
    for (int key = 1; key <= 64; key++) {
      assertEquals(StatusCode.OK, values.begin(table.descriptor(), null));
      assertEquals(StatusCode.OK, values.setFixed(0, SqlTypeDescriptor.BIGINT, key));
      assertEquals(StatusCode.OK, values.setFixed(1, SqlTypeDescriptor.BIGINT, key * 10L));
      assertEquals(StatusCode.OK, session.descriptorRows().insert(table, values, identity));
    }
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    StoredTableRowView row = new StoredTableRowView();
    IndexedTupleScanResult point = row.pointRow();
    for (int warm = 0; warm < 5; warm++) readPoints(session, table, row);
    long thread = Thread.currentThread().threadId();
    long before = allocations.getThreadAllocatedBytes(thread);
    readPoints(session, table, row);
    long pointBytes = allocations.getThreadAllocatedBytes(thread) - before;
    assertEquals(0, pointBytes, "warmed primary point bytes");
    Field bytes = StoredTableRowView.class.getDeclaredField("bytes");
    bytes.setAccessible(true);
    assertSame(point.page(), bytes.get(row), "point row must borrow its selected leaf bytes");
    assertEquals(StatusCode.OK, row.reset());
    KeyDescriptor primary = table.descriptor().primaryKey();
    KeyDescriptor secondary = table.descriptor().secondaryKeyAt(0);
    assertEquals(StatusCode.OK, table.release());
    for (KeyDescriptor key : new KeyDescriptor[] {primary, secondary}) {
      RelationalDescriptorIndexBounds bounds = new RelationalDescriptorIndexBounds();
      assertEquals(StatusCode.OK, bounds.set(key, null, 0, true, null, 0, true,
          TupleBTreeScanBounds.FORWARD));
      RelationalDescriptorScanCursor cursor = new RelationalDescriptorScanCursor();
      for (int iteration = 0; iteration < 100; iteration++) {
        assertEquals(StatusCode.OK, database.services().descriptors().open(tableId, table, detail));
        assertEquals(StatusCode.OK, session.descriptorRows().beginIndexScan(
            table, bounds, LockMode.SHARED, cursor));
        readScan(session, cursor, row, identity);
        assertEquals(StatusCode.OK, session.descriptorRows().closeScan(cursor));
      }
      assertEquals(StatusCode.OK, database.services().descriptors().open(tableId, table, detail));
      assertEquals(StatusCode.OK, session.descriptorRows().beginIndexScan(
          table, bounds, LockMode.SHARED, cursor));
      before = allocations.getThreadAllocatedBytes(thread);
      readScan(session, cursor, row, identity);
      long scanBytes = allocations.getThreadAllocatedBytes(thread) - before;
      assertEquals(0, scanBytes, "warmed scan bytes for key " + key.keyId());
      assertSame(key == primary ? cursor.tupleRow().page() : point.page(), bytes.get(row),
          "scan row must borrow its selected primary leaf bytes");
      assertEquals(StatusCode.CONFLICT, session.descriptorRows().nextScan(cursor, row, identity));
      assertEquals(StatusCode.OK, session.descriptorRows().closeScan(cursor));
      System.out.println("clustered_read key=" + key.keyId() + " point_bytes=" + pointBytes
          + " scan_rows=64 scan_bytes=" + scanBytes);
    }
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, database.close());
  }

  private static void readPoints(RelationalSession session, SchemaPin table, StoredTableRowView row) {
    for (int index = 0; index < 10_000; index++) readPoint(session, table, row, index % 64 + 1);
  }

  private static void readPoint(
      RelationalSession session, SchemaPin table, StoredTableRowView row, int key) {
    if (session.descriptorRows().fetch(table, key, row) != StatusCode.OK
        || row.valueAt(0) != key || row.valueAt(1) != key * 10L) {
      throw new AssertionError("point read changed its row");
    }
    readGuard += row.valueAt(1);
  }

  private static void readScan(RelationalSession session, RelationalDescriptorScanCursor cursor,
      StoredTableRowView row, RelationalRowIdentityResult identity) {
    for (int index = 0; index < 64; index++) {
      if (session.descriptorRows().nextScan(cursor, row, identity) != StatusCode.OK) {
        throw new AssertionError("scan lost a row");
      }
      readGuard += row.valueAt(1);
    }
  }

  private static TableDescriptor table() {
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.BIGINT},
        new CharSequence[] {"id", "value"}, new boolean[] {false, false}, columns));
    KeyDescriptor.Result primary = new KeyDescriptor.Result();
    KeyDescriptor.Result secondary = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.create(1, KeyDescriptor.KIND_PRIMARY, true,
        columns.value(), new int[] {0}, 0, primary, null));
    assertEquals(StatusCode.OK, KeyDescriptor.create(2, KeyDescriptor.KIND_SECONDARY, false,
        columns.value(), new int[] {1}, 0, secondary, null));
    TableDescriptor.Result result = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.create(1, 1, 1, columns.value(), primary.value(),
        new KeyDescriptor[] {secondary.value()}, null, result, null));
    return result.value();
  }
}
