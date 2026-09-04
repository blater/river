package io.riverdb.engine.relational;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RelationalDescriptorBatchUniqueKeysTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4241544348554e49L, 0x5155454b45595331L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static final long RECEIPT_BYTES = 8L * (Integer.BYTES + Long.BYTES);
  private static final long INITIAL_UNIQUE_BYTES =
      8L * (Long.BYTES + 3L * Integer.BYTES) + 256L + 16L * Integer.BYTES;

  @Test
  void hashCollisionStillUsesExactKeyComparison() {
    RelationalDescriptorBatchUniqueKeys keys = new RelationalDescriptorBatchUniqueKeys();
    ByteBuffer first = ByteBuffer.wrap(new byte[] {0, 31});
    ByteBuffer collision = ByteBuffer.wrap(new byte[] {1, 0});

    assertEquals(StatusCode.OK, keys.add(7, first, first.remaining()));
    assertEquals(StatusCode.OK, keys.add(7, collision, collision.remaining()));
    assertEquals(StatusCode.UNIQUE_VIOLATION, keys.add(7, first, first.remaining()));

    keys.reset();
    assertEquals(StatusCode.OK, keys.add(7, first, first.remaining()));
    assertEquals(StatusCode.OK, keys.add(8, first, first.remaining()));
  }

  @Test
  void retainedGrowthIsChargedOnceAndReusedAcrossStatements() {
    TrackingBudget budget = new TrackingBudget(Long.MAX_VALUE);
    RelationalDescriptorInsertBatch batch = new RelationalDescriptorInsertBatch(budget);
    TableDescriptor table = table();

    assertEquals(StatusCode.OK, batch.begin(table, 1));
    assertEquals(RECEIPT_BYTES, budget.retained);
    assertEquals(StatusCode.OK,
        batch.admitUnique(1, ByteBuffer.wrap(new byte[] {1}), 1));
    assertEquals(RECEIPT_BYTES + INITIAL_UNIQUE_BYTES, budget.retained);
    assertEquals(budget.retained, batch.retainedBytes());

    batch.reset();
    assertEquals(RECEIPT_BYTES + INITIAL_UNIQUE_BYTES, budget.retained);
    assertEquals(StatusCode.OK, batch.begin(table, 1));
    assertEquals(StatusCode.OK,
        batch.admitUnique(1, ByteBuffer.wrap(new byte[] {2}), 1));
    assertEquals(RECEIPT_BYTES + INITIAL_UNIQUE_BYTES, budget.retained);
    assertEquals(budget.retained, batch.retainedBytes());
  }

  @Test
  void receiptGrowsBeyondLegacyInsertBatchSize() {
    TrackingBudget budget = new TrackingBudget(Long.MAX_VALUE);
    RelationalDescriptorInsertBatch batch = new RelationalDescriptorInsertBatch(budget);
    TableDescriptor table = table();
    int rows = 257;

    assertEquals(StatusCode.OK, batch.begin(table, rows));
    for (int row = 0; row < rows; row++) {
      assertEquals(StatusCode.OK, batch.admit(table, 1, 0, 0, row));
    }

    assertEquals(rows, batch.rowCount());
    assertTrue(batch.admittedFor(table));
    assertEquals(512L * (Integer.BYTES + Long.BYTES), batch.retainedBytes());
    assertEquals(batch.retainedBytes(), budget.retained);
  }

  @Test
  void uniqueKeyWorkspaceGrowsBeyondLegacyRowTimesIndexLimit() {
    RelationalDescriptorBatchUniqueKeys keys = new RelationalDescriptorBatchUniqueKeys();
    ByteBuffer key = ByteBuffer.allocate(Long.BYTES);
    int entries = 64 * SqlShapeLimits.MAX_TABLE_INDEXES + 1;

    for (int entry = 0; entry < entries; entry++) {
      key.putLong(0, entry);
      assertEquals(StatusCode.OK, keys.add(1, key, Long.BYTES));
    }

    key.putLong(0, entries - 1L);
    assertEquals(StatusCode.UNIQUE_VIOLATION, keys.add(1, key, Long.BYTES));
  }

  @Test
  void retainedBudgetRejectsReceiptGrowthWithoutDiscardingPriorCapacity() {
    long replacement = 128L * (Integer.BYTES + Long.BYTES);
    TrackingBudget budget = new TrackingBudget(RECEIPT_BYTES + replacement - 1);
    RelationalDescriptorInsertBatch batch = new RelationalDescriptorInsertBatch(budget);
    TableDescriptor table = table();

    assertEquals(StatusCode.OK, batch.begin(table, 1));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, batch.begin(table, 65));

    assertEquals(RECEIPT_BYTES, batch.retainedBytes());
    assertEquals(RECEIPT_BYTES, budget.retained);
    assertFalse(batch.admittedFor(table));
  }

  @Test
  void retainedBudgetRejectsUniqueKeyGrowthWithoutPublishingPartialArrays() {
    TrackingBudget budget = new TrackingBudget(RECEIPT_BYTES + INITIAL_UNIQUE_BYTES);
    RelationalDescriptorInsertBatch batch = new RelationalDescriptorInsertBatch(budget);
    ByteBuffer key = ByteBuffer.allocate(Long.BYTES);

    assertEquals(StatusCode.OK, batch.begin(table(), 1));
    for (int entry = 0; entry < 8; entry++) {
      key.putLong(0, entry);
      assertEquals(StatusCode.OK, batch.admitUnique(1, key, Long.BYTES));
    }
    key.putLong(0, 8);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        batch.admitUnique(1, key, Long.BYTES));

    assertEquals(RECEIPT_BYTES + INITIAL_UNIQUE_BYTES, batch.retainedBytes());
    assertEquals(batch.retainedBytes(), budget.retained);
  }

  @Test
  void allocationFailureRollsBackTheWholeGrowthChargeForExactRetry() {
    TrackingBudget budget =
        new TrackingBudget(RECEIPT_BYTES + INITIAL_UNIQUE_BYTES);
    FailingAllocator allocator = new FailingAllocator();
    RelationalDescriptorInsertBatch batch =
        new RelationalDescriptorInsertBatch(budget, allocator);

    assertEquals(StatusCode.OK, batch.begin(table(), 1));
    allocator.failNextBytes = true;
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        batch.admitUnique(1, ByteBuffer.wrap(new byte[] {1}), 1));
    assertEquals(RECEIPT_BYTES, budget.retained);
    assertEquals(RECEIPT_BYTES, batch.retainedBytes());

    assertEquals(StatusCode.OK,
        batch.admitUnique(1, ByteBuffer.wrap(new byte[] {1}), 1));
    assertEquals(RECEIPT_BYTES + INITIAL_UNIQUE_BYTES, budget.retained);
    assertEquals(budget.retained, batch.retainedBytes());
  }

  @Test
  void receiptAllocationFailureAlsoRollsBackForRetry() {
    TrackingBudget budget = new TrackingBudget(RECEIPT_BYTES);
    FailingAllocator allocator = new FailingAllocator();
    RelationalDescriptorInsertBatch batch =
        new RelationalDescriptorInsertBatch(budget, allocator);
    TableDescriptor table = table();

    allocator.failNextLongs = true;
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, batch.begin(table, 1));
    assertEquals(0, budget.retained);
    assertEquals(0, batch.retainedBytes());
    assertFalse(batch.admittedFor(table));

    assertEquals(StatusCode.OK, batch.begin(table, 1));
    assertEquals(RECEIPT_BYTES, budget.retained);
    assertEquals(RECEIPT_BYTES, batch.retainedBytes());
  }

  @Test
  void tinyBudgetRejectsBeforePublishingAnAdmittedBatch() {
    TrackingBudget budget = new TrackingBudget(RECEIPT_BYTES - 1);
    RelationalDescriptorInsertBatch batch = new RelationalDescriptorInsertBatch(budget);
    TableDescriptor table = table();

    assertEquals(StatusCode.RESOURCE_EXHAUSTED, batch.begin(table, 1));
    assertEquals(0, budget.retained);
    assertEquals(0, batch.retainedBytes());
    assertFalse(batch.admittedFor(table));
  }

  @Test
  void tinyBudgetFailureDoesNotReserveALogicalRowId(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin pin = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        table(), pin, new StatusDetail(128)));
    RelationalSessionOpenResult sessionResult = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RelationalSession session = sessionResult.session();
    RelationalDescriptorBatchInsert inserts = session.descriptorRows().batchInsert();
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));

    RelationalDescriptorInsertBatch rejected = new RelationalDescriptorInsertBatch(
        new TrackingBudget(RECEIPT_BYTES - 1));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, inserts.begin(rejected, pin, 1));

    RelationalDescriptorInsertBatch accepted = new RelationalDescriptorInsertBatch();
    SqlValueBuffer values = values(41);
    assertEquals(StatusCode.OK, inserts.begin(accepted, pin, 1));
    assertEquals(StatusCode.OK, inserts.admit(accepted, pin, values));
    assertEquals(StatusCode.OK, inserts.reserve(accepted, pin));
    RelationalRowIdentityResult identity = new RelationalRowIdentityResult();
    assertEquals(StatusCode.OK, inserts.insert(accepted, pin, 0, values, identity));
    assertEquals(1, identity.logicalRowId());

    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(StatusCode.OK, pin.release());
    assertEquals(StatusCode.OK, database.close());
  }

  private static TableDescriptor table() {
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {SqlTypeDescriptor.BIGINT}, new CharSequence[] {"id"},
        new boolean[] {false}, columns));
    KeyDescriptor.Result primary = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.createForTest(
        KeyDescriptor.KIND_PRIMARY, true, columns.value(), new int[] {0},
        primary, null));
    TableDescriptor.Result table = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.createForTest(
        columns.value(), primary.value(), null, null, table));
    return table.value();
  }

  private static SqlValueBuffer values(long id) {
    SqlValueBuffer values = new SqlValueBuffer();
    assertEquals(StatusCode.OK, values.reserve(1, 1, 0, 0));
    assertEquals(StatusCode.OK, values.clearForSize(1));
    assertEquals(StatusCode.OK, values.setFixed(0, SqlTypeDescriptor.BIGINT, id));
    return values;
  }

  private static final class TrackingBudget implements RelationalRetainedBudget {
    private final long maximum;
    private long retained;

    private TrackingBudget(long maximumBytes) { maximum = maximumBytes; }

    @Override
    public StatusCode reserve(long bytes) {
      if (bytes <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      if (bytes > maximum - retained) return StatusCode.RESOURCE_EXHAUSTED;
      retained += bytes;
      return StatusCode.OK;
    }

    @Override
    public void rollback(long bytes) {
      if (bytes > 0 && bytes <= retained) retained -= bytes;
    }
  }

  private static final class FailingAllocator extends RelationalDescriptorBatchAllocator {
    private boolean failNextBytes;
    private boolean failNextLongs;

    @Override
    byte[] bytes(int capacity) {
      if (failNextBytes) {
        failNextBytes = false;
        throw new OutOfMemoryError("injected retained byte allocation failure");
      }
      return super.bytes(capacity);
    }

    @Override
    long[] longs(int capacity) {
      if (failNextLongs) {
        failNextLongs = false;
        throw new OutOfMemoryError("injected retained long allocation failure");
      }
      return super.longs(capacity);
    }
  }
}
