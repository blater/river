package io.riverdb.engine.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import org.junit.jupiter.api.Test;

final class RelationalDescriptorIndexChangeTest {
  @Test
  void addsThirtyTwoPartHighOrdinalIndexAndPreservesExistingIdentities() {
    TableDescriptor current = table(40, true);
    int[] ordinals = new int[32];
    for (int index = 0; index < ordinals.length; index++) ordinals[index] = index + 8;
    TableDescriptor.Result result = new TableDescriptor.Result();
    StatusDetail detail = new StatusDetail(128);
    RelationalDescriptorIndexChange change = new RelationalDescriptorIndexChange();

    assertEquals(StatusCode.OK, change.add(
        current, "by_wide", false, ordinals, 0, ordinals.length, result, detail),
        detail.toString());
    TableDescriptor proposed = result.value();
    assertSame(current.columns(), proposed.columns());
    assertSame(current.primaryKey(), proposed.primaryKey());
    assertSame(current.secondaryKeyAt(0), proposed.secondaryKeyAt(0));
    KeyDescriptor added = proposed.secondaryKeyAt(1);
    assertEquals(0, added.keyId());
    assertEquals(32, added.partCount());
    assertEquals(39, added.columnOrdinalAt(31));
    assertEquals(true, added.matchesName("by_wide"));
  }

  @Test
  void dropPreservesOrderedRetainedKeyObjects() {
    TableDescriptor current = table(4, true);
    TableDescriptor.Result added = new TableDescriptor.Result();
    RelationalDescriptorIndexChange change = new RelationalDescriptorIndexChange();
    assertEquals(StatusCode.OK, change.add(
        current, "remove_me", true, new int[] {2, 3}, 0, 2,
        added, new StatusDetail(64)));
    KeyDescriptor retained = added.value().secondaryKeyAt(0);
    TableDescriptor.Result dropped = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, change.drop(
        added.value(), "remove_me", dropped, new StatusDetail(64)));
    assertEquals(1, dropped.value().secondaryKeyCount());
    assertSame(retained, dropped.value().secondaryKeyAt(0));
    assertEquals(StatusCode.CONFLICT, change.drop(
        dropped.value(), "missing", new TableDescriptor.Result(), new StatusDetail(64)));
  }

  @Test
  void rejectsDuplicateNameColumnsAndThirtyThirdPart() {
    TableDescriptor current = table(40, true);
    RelationalDescriptorIndexChange change = new RelationalDescriptorIndexChange();
    TableDescriptor.Result result = new TableDescriptor.Result();
    assertEquals(StatusCode.CONFLICT, change.add(
        current, "existing", false, new int[] {1}, 0, 1,
        result, new StatusDetail(64)));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, change.add(
        current, "duplicate_parts", false, new int[] {7, 7}, 0, 2,
        result, new StatusDetail(64)));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, change.add(
        current, "too_wide", false, new int[33], 0, 33,
        result, new StatusDetail(64)));
  }

  @Test
  void allocationFailuresLeaveResultUnpublishedAndRetryable() {
    TableDescriptor current = table(4, true);
    for (int failure = 1; failure <= 3; failure++) {
      FailingAllocator allocator = new FailingAllocator(failure);
      RelationalDescriptorIndexChange change =
          new RelationalDescriptorIndexChange(allocator);
      TableDescriptor.Result result = new TableDescriptor.Result();
      assertEquals(StatusCode.RESOURCE_EXHAUSTED, change.add(
          current, "retry_" + failure, false, new int[] {2, 3}, 0, 2,
          result, new StatusDetail(64)));
      assertNull(result.value());
      assertEquals(StatusCode.OK, change.add(
          current, "retry_" + failure, false, new int[] {2, 3}, 0, 2,
          result, new StatusDetail(64)));
    }
  }

  @Test
  void dropAllocationFailuresLeaveResultUnpublishedAndRetryable() {
    TableDescriptor current = table(4, true);
    for (int failure = 1; failure <= 2; failure++) {
      RelationalDescriptorIndexChange change =
          new RelationalDescriptorIndexChange(new FailingAllocator(failure));
      TableDescriptor.Result result = new TableDescriptor.Result();
      assertEquals(StatusCode.RESOURCE_EXHAUSTED, change.drop(
          current, "existing", result, new StatusDetail(64)));
      assertNull(result.value());
      assertEquals(StatusCode.OK, change.drop(
          current, "existing", result, new StatusDetail(64)));
      assertEquals(0, result.value().secondaryKeyCount());
    }
  }

  @Test
  void rejectsPrimaryConstraintAndInternalSupportKeys() {
    TableDescriptor current = protectedKeysTable();
    RelationalDescriptorIndexChange change = new RelationalDescriptorIndexChange();
    TableDescriptor.Result result = new TableDescriptor.Result();
    StatusDetail detail = new StatusDetail(64);

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        change.drop(current, "named_pk", result, detail));
    assertNull(change.droppedKey());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        change.drop(current, "PRIMARY", result, detail));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        change.drop(current, "uq_pair", result, detail));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        change.drop(current, "_river_fk_3", result, detail));
    assertEquals(StatusCode.OK,
        change.drop(current, "ordinary_pair", result, detail));
    assertSame(current.secondaryKeyAt(2), change.droppedKey());
  }

  private static TableDescriptor table(int count, boolean secondary) {
    int[] types = new int[count];
    CharSequence[] names = new CharSequence[count];
    boolean[] nullable = new boolean[count];
    for (int index = 0; index < count; index++) {
      types[index] = SqlTypeDescriptor.BIGINT;
      names[index] = "c" + index;
    }
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK,
        ColumnDescriptorSet.create(types, names, nullable, columns));
    KeyDescriptor.Result primary = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.create(
        11, KeyDescriptor.KIND_PRIMARY, true, columns.value(), new int[] {0},
        0, primary, null));
    KeyDescriptor[] indexes = null;
    if (secondary) {
      KeyDescriptor.Result existing = new KeyDescriptor.Result();
      assertEquals(StatusCode.OK, KeyDescriptor.createNamed(
          12, KeyDescriptor.KIND_SECONDARY, false, columns.value(), new int[] {1},
          0, "existing", existing, null));
      indexes = new KeyDescriptor[] {existing.value()};
    }
    TableDescriptor.Result table = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.create(
        7, 8, 9, columns.value(), primary.value(), indexes, null, table, null));
    return table.value();
  }

  private static TableDescriptor protectedKeysTable() {
    int[] types = {
        SqlTypeDescriptor.INTEGER,
        SqlTypeDescriptor.decimal(22, 18),
        SqlTypeDescriptor.DOUBLE
    };
    CharSequence[] names = {"id", "amount", "ratio"};
    boolean[] nullable = new boolean[types.length];
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK,
        ColumnDescriptorSet.create(types, names, nullable, columns));
    KeyDescriptor.Result primary = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.createNamed(
        21, KeyDescriptor.KIND_PRIMARY, true, columns.value(), new int[] {0},
        0, "named_pk", primary, null));
    KeyDescriptor.Result unique = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.createNamed(
        22, KeyDescriptor.KIND_UNIQUE, true, columns.value(), new int[] {1, 2},
        0, "uq_pair", unique, null));
    KeyDescriptor.Result support = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.createNamed(
        23, KeyDescriptor.KIND_SECONDARY, false, columns.value(), new int[] {1, 2},
        0, "_river_fk_3", support, null));
    KeyDescriptor.Result ordinary = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.createNamed(
        24, KeyDescriptor.KIND_SECONDARY, false, columns.value(), new int[] {1, 2},
        0, "ordinary_pair", ordinary, null));
    TableDescriptor.Result table = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.create(
        20, 25, 1, columns.value(), primary.value(),
        new KeyDescriptor[] {unique.value(), support.value(), ordinary.value()},
        null, table, null));
    return table.value();
  }

  private static final class FailingAllocator
      implements RelationalDescriptorIndexArrayAllocator {
    private final int failAt;
    private int allocation;

    private FailingAllocator(int failure) {
      failAt = failure;
    }

    @Override
    public int[] integers(int count) {
      fail();
      return new int[count];
    }

    @Override
    public KeyDescriptor[] keys(int count) {
      fail();
      return new KeyDescriptor[count];
    }

    private void fail() {
      if (++allocation == failAt) throw new OutOfMemoryError("injected");
    }
  }
}
