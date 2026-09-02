package io.riverdb.engine.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.format.btree.TupleKeyCodec;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class RelationalDescriptorTupleDeltaPlanTest {
  private static volatile long allocationGuard;

  @Test
  void insertAndDeleteRetainEveryPhysicalIndexInKeyIdOrder() {
    TableDescriptor table = threeKeyTable();
    SqlValueBuffer values = threeValues(1, 10, 20);
    RelationalDescriptorTupleDeltaPlan plan = new RelationalDescriptorTupleDeltaPlan();

    assertEquals(StatusCode.OK, plan.insert(table, values, 91));
    assertEquals(3, plan.keyCount());
    assertEquals(3, plan.mutationCount());
    assertTrue(plan.payloadBytes() > 0);
    assertEquals(10, plan.keyAt(0).keyId());
    assertEquals(20, plan.keyAt(1).keyId());
    assertEquals(30, plan.keyAt(2).keyId());
    for (int index = 0; index < plan.keyCount(); index++) {
      assertEquals(0, plan.beforeLengthAt(index));
      assertEquals(91, TupleKeyCodec.logicalRowId(
          plan.bytes(), plan.afterOffsetAt(index), plan.afterLengthAt(index)));
    }

    assertEquals(StatusCode.OK, plan.delete(table, values, 92));
    assertEquals(3, plan.mutationCount());
    for (int index = 0; index < plan.keyCount(); index++) {
      assertEquals(0, plan.afterLengthAt(index));
      assertEquals(92, TupleKeyCodec.logicalRowId(
          plan.bytes(), plan.beforeOffsetAt(index), plan.beforeLengthAt(index)));
    }
  }

  @Test
  void updateSharesUnchangedKeysAndEmitsOnlyChangedBeforeAfterDeltas() {
    TableDescriptor table = threeKeyTable();
    SqlValueBuffer before = threeValues(1, 10, 20);
    SqlValueBuffer after = threeValues(1, 10, 21);
    RelationalDescriptorTupleDeltaPlan plan = new RelationalDescriptorTupleDeltaPlan();

    assertEquals(StatusCode.OK, plan.update(table, before, after, 7));
    assertEquals(2, plan.mutationCount());
    assertTrue(plan.changedAt(0));
    assertFalse(plan.changedAt(1));
    assertFalse(plan.changedAt(2));
    assertTrue(TupleKeyCodec.compareUserTuple(
        plan.bytes(), plan.beforeOffsetAt(0), plan.beforeLengthAt(0),
        plan.bytes(), plan.afterOffsetAt(0), plan.afterLengthAt(0)) < 0);
    for (int index = 1; index < plan.keyCount(); index++) {
      assertEquals(plan.beforeOffsetAt(index), plan.afterOffsetAt(index));
      assertEquals(plan.beforeLengthAt(index), plan.afterLengthAt(index));
    }
  }

  @Test
  void failedPreparationPublishesNothingAndScrubsThePreviousPlan() {
    TableDescriptor table = threeKeyTable();
    RelationalDescriptorTupleDeltaPlan plan = new RelationalDescriptorTupleDeltaPlan();
    assertEquals(StatusCode.OK, plan.insert(table, threeValues(1, 2, 3), 1));
    assertTrue(plan.bytes().get(0) != 0);

    SqlValueBuffer invalid = new SqlValueBuffer();
    assertEquals(StatusCode.OK, invalid.reserve(1, 1, 0, 0));
    assertEquals(StatusCode.OK, invalid.clearForSize(1));
    assertEquals(StatusCode.OK, invalid.setFixed(0, SqlTypeDescriptor.BIGINT, 1));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, plan.insert(table, invalid, 2));
    assertEquals(0, plan.keyCount());
    assertEquals(0, plan.mutationCount());
    assertEquals(0, plan.payloadBytes());
    assertEquals(0, plan.bytes().get(0));
  }

  @Test
  void allocationFailureRollsBackTheWholeRetainedChargeForRetry() {
    TrackingBudget budget = new TrackingBudget(Long.MAX_VALUE);
    FailingAllocator allocator = new FailingAllocator();
    RelationalDescriptorTupleDeltaPlan plan =
        new RelationalDescriptorTupleDeltaPlan(budget, allocator);
    allocator.failNextBytes = true;

    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        plan.insert(threeKeyTable(), threeValues(1, 2, 3), 1));
    assertEquals(0, budget.retained);
    assertEquals(0, plan.keyCount());
    assertEquals(StatusCode.OK,
        plan.insert(threeKeyTable(), threeValues(1, 2, 3), 1));
    assertTrue(budget.retained > 0);
  }

  @Test
  void enterpriseMaximumIndexesAndWideKeysFitTheSemanticEnvelope() {
    TableDescriptor table = maximumIndexTable();
    String text = "x".repeat(765);
    SqlValueBuffer values = new SqlValueBuffer();
    assertEquals(StatusCode.OK, values.reserve(1, 1, text.length(), text.length()));
    assertEquals(StatusCode.OK, values.clearForSize(1));
    assertEquals(StatusCode.OK,
        values.setText(0, SqlTypeDescriptor.varchar(765), text));
    RelationalDescriptorTupleDeltaPlan plan = new RelationalDescriptorTupleDeltaPlan();

    assertEquals(StatusCode.OK, plan.insert(table, values, Long.MAX_VALUE));
    assertEquals(SqlShapeLimits.MAX_TABLE_INDEXES, plan.keyCount());
    assertEquals(SqlShapeLimits.MAX_TABLE_INDEXES, plan.mutationCount());
    assertTrue(plan.payloadBytes() >= SqlShapeLimits.MAX_TABLE_INDEXES * 765);
    assertEquals(StatusCode.OK, plan.bindLogicalRowId(17));
    for (int index = 0; index < plan.keyCount(); index++) {
      assertEquals(17, TupleKeyCodec.logicalRowId(
          plan.bytes(), plan.afterOffsetAt(index), plan.afterLengthAt(index)));
    }
  }

  @Test
  void warmedMaximumPlanReuseAllocatesNoPerKeyObjects() {
    ThreadMXBean allocations = allocationBean();
    TableDescriptor table = maximumIndexTable();
    SqlValueBuffer before = textValue("a".repeat(765));
    SqlValueBuffer after = textValue("b".repeat(765));
    RelationalDescriptorTupleDeltaPlan plan = new RelationalDescriptorTupleDeltaPlan();
    assertEquals(StatusCode.OK, plan.update(table, before, after, 1));
    assertEquals(StatusCode.OK, plan.update(table, after, before, 1));

    long thread = Thread.currentThread().threadId();
    long allocatedBefore = allocations.getThreadAllocatedBytes(thread);
    for (int iteration = 0; iteration < 10; iteration++) {
      allocationGuard += plan.update(table, before, after, 1).stableCode();
      allocationGuard += plan.mutationCount();
    }
    long allocated = allocations.getThreadAllocatedBytes(thread) - allocatedBefore;
    assertEquals(0, allocated);
  }

  private static TableDescriptor threeKeyTable() {
    ColumnDescriptorSet columns = columns(
        new int[] {SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.BIGINT},
        new CharSequence[] {"id", "first_value", "second_value"});
    KeyDescriptor primary = key(30, KeyDescriptor.KIND_PRIMARY, true, columns, 0);
    KeyDescriptor[] secondary = {
        key(10, KeyDescriptor.KIND_SECONDARY, false, columns, 2),
        key(20, KeyDescriptor.KIND_SECONDARY, false, columns, 1)
    };
    return table(columns, primary, secondary);
  }

  private static TableDescriptor maximumIndexTable() {
    int type = SqlTypeDescriptor.varchar(765);
    ColumnDescriptorSet columns = columns(new int[] {type}, new CharSequence[] {"value"});
    KeyDescriptor primary = key(10_000, KeyDescriptor.KIND_PRIMARY, true, columns, 0);
    KeyDescriptor[] secondary = new KeyDescriptor[SqlShapeLimits.MAX_SECONDARY_INDEXES];
    for (int index = 0; index < secondary.length; index++) {
      secondary[index] = key(
          1 + index, KeyDescriptor.KIND_SECONDARY, false, columns, 0);
    }
    return table(columns, primary, secondary);
  }

  private static ColumnDescriptorSet columns(int[] types, CharSequence[] names) {
    boolean[] nullable = new boolean[types.length];
    ColumnDescriptorSet.Result result = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK,
        ColumnDescriptorSet.create(types, names, nullable, result));
    return result.value();
  }

  private static KeyDescriptor key(
      long id, int kind, boolean unique, ColumnDescriptorSet columns, int ordinal) {
    KeyDescriptor.Result result = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.create(
        id, kind, unique, columns, new int[] {ordinal}, 0, result, null));
    return result.value();
  }

  private static TableDescriptor table(
      ColumnDescriptorSet columns, KeyDescriptor primary, KeyDescriptor[] secondary) {
    TableDescriptor.Result result = new TableDescriptor.Result();
    assertEquals(StatusCode.OK,
        TableDescriptor.create(1, 1, 1, columns, primary, secondary, null, result, null));
    return result.value();
  }

  private static SqlValueBuffer threeValues(long id, long first, long second) {
    SqlValueBuffer values = new SqlValueBuffer();
    assertEquals(StatusCode.OK, values.reserve(3, 3, 0, 0));
    assertEquals(StatusCode.OK, values.clearForSize(3));
    assertEquals(StatusCode.OK, values.setFixed(0, SqlTypeDescriptor.BIGINT, id));
    assertEquals(StatusCode.OK, values.setFixed(1, SqlTypeDescriptor.BIGINT, first));
    assertEquals(StatusCode.OK, values.setFixed(2, SqlTypeDescriptor.BIGINT, second));
    return values;
  }

  private static SqlValueBuffer textValue(String text) {
    SqlValueBuffer values = new SqlValueBuffer();
    assertEquals(StatusCode.OK, values.reserve(1, 1, text.length(), text.length()));
    assertEquals(StatusCode.OK, values.clearForSize(1));
    assertEquals(StatusCode.OK,
        values.setText(0, SqlTypeDescriptor.varchar(765), text));
    return values;
  }

  private static ThreadMXBean allocationBean() {
    java.lang.management.ThreadMXBean standard = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standard instanceof ThreadMXBean);
    ThreadMXBean allocations = (ThreadMXBean) standard;
    Assumptions.assumeTrue(allocations.isThreadAllocatedMemorySupported());
    allocations.setThreadAllocatedMemoryEnabled(true);
    return allocations;
  }

  private static final class TrackingBudget implements RelationalRetainedBudget {
    private final long maximum;
    private long retained;

    TrackingBudget(long maximumBytes) { maximum = maximumBytes; }

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

  private static final class FailingAllocator
      extends RelationalDescriptorTupleDeltaAllocator {
    private boolean failNextBytes;

    @Override
    byte[] bytes(int capacity) {
      if (failNextBytes) {
        failNextBytes = false;
        throw new OutOfMemoryError("injected tuple plan byte allocation failure");
      }
      return super.bytes(capacity);
    }
  }
}
