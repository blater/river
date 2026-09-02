package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.ColumnConstraintDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;

/** Freezes one wide SQL CREATE TABLE shape into an immutable catalog-v2 descriptor. */
final class SqlDescriptorTableBuilder {
  private final SqlRetainedArrayAllocator allocator;
  private int[] types = new int[0];
  private CharSequence[] names = new CharSequence[0];
  private boolean[] nullable = new boolean[0];
  private byte[] defaultKinds = new byte[0];
  private long[] defaultHighs = new long[0];
  private long[] defaultValues = new long[0];
  private byte[] checkComparisons = new byte[0];
  private int[] checkTypes = new int[0];
  private long[] checkHighs = new long[0];
  private long[] checkValues = new long[0];
  private final ColumnConstraintDescriptorSet.Result constraints =
      new ColumnConstraintDescriptorSet.Result();
  private final ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
  private final TableDescriptor.Result table = new TableDescriptor.Result();
  private final SqlDescriptorKeyBuilder keys;
  private int capacity;

  SqlDescriptorTableBuilder() { this(SqlRetainedArrayAllocator.STANDARD); }

  SqlDescriptorTableBuilder(SqlRetainedArrayAllocator arrayAllocator) {
    allocator = arrayAllocator;
    keys = new SqlDescriptorKeyBuilder();
  }

  StatusCode build(SqlCommand command, StatusDetail detail) {
    StatusCode status = freeze(command, detail);
    return status.isOk() && !SqlDescriptorLifecycleAdmission.ready(command, table.value())
        ? StatusCode.FEATURE_NOT_SUPPORTED : status;
  }

  StatusCode freeze(SqlCommand command, StatusDetail detail) {
    table.reset();
    int count = command.columnCount();
    if (count <= 0 || count > SqlShapeLimits.MAX_TABLE_COLUMNS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = reserve(count);
    if (!status.isOk()) return status;
    for (int index = 0; index < count; index++) {
      types[index] = command.columnTypeDescriptor(index);
      names[index] = command.columnName(index);
      nullable[index] = !command.columnIsNotNull(index);
      defaultKinds[index] = (byte) command.columnDefaultKind(index);
      defaultHighs[index] = command.columnDefaultHigh(index);
      defaultValues[index] = command.columnDefaultValue(index);
      checkComparisons[index] = (byte) comparison(command.columnCheckComparison(index));
      checkTypes[index] = command.columnCheckTypeDescriptor(index);
      checkHighs[index] = command.columnCheckHigh(index);
      checkValues[index] = command.columnCheckValue(index);
    }
    status = ColumnConstraintDescriptorSet.create(
        types, defaultKinds, defaultHighs, defaultValues,
        checkComparisons, checkTypes, checkHighs, checkValues,
        count, constraints);
    if (status.isOk()) status = ColumnDescriptorSet.createConstrained(
        types, names, nullable, count, constraints.value(), columns, detail);
    if (status.isOk()) status = keys.freeze(command, columns.value(), detail);
    KeyDescriptor[] exactSecondary = status.isOk() ? keys.exactSecondary() : null;
    if (status.isOk() && exactSecondary == null) status = StatusCode.RESOURCE_EXHAUSTED;
    return status.isOk() ? TableDescriptor.create(
        1, 1, 1, columns.value(), keys.primary(),
        exactSecondary, null, table, detail) : status;
  }

  TableDescriptor descriptor() { return table.value(); }

  StatusCode reserve(int count) {
    if (count <= capacity) return StatusCode.OK;
    try {
      int[] nextTypes = allocator.integers(count);
      CharSequence[] nextNames = allocator.names(count);
      boolean[] nextNullable = allocator.booleans(count);
      byte[] nextDefaultKinds = new byte[count];
      long[] nextDefaultHighs = new long[count];
      long[] nextDefaultValues = new long[count];
      byte[] nextCheckComparisons = new byte[count];
      int[] nextCheckTypes = allocator.integers(count);
      long[] nextCheckHighs = new long[count];
      long[] nextCheckValues = new long[count];
      types = nextTypes;
      names = nextNames;
      nullable = nextNullable;
      defaultKinds = nextDefaultKinds;
      defaultHighs = nextDefaultHighs;
      defaultValues = nextDefaultValues;
      checkComparisons = nextCheckComparisons;
      checkTypes = nextCheckTypes;
      checkHighs = nextCheckHighs;
      checkValues = nextCheckValues;
      capacity = count;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private static int comparison(SqlComparison value) {
    if (value == null) return ColumnConstraintDescriptorSet.CHECK_NONE;
    return switch (value) {
      case EQUAL -> ColumnConstraintDescriptorSet.CHECK_EQUAL;
      case NOT_EQUAL -> ColumnConstraintDescriptorSet.CHECK_NOT_EQUAL;
      case LESS_THAN -> ColumnConstraintDescriptorSet.CHECK_LESS_THAN;
      case LESS_OR_EQUAL -> ColumnConstraintDescriptorSet.CHECK_LESS_OR_EQUAL;
      case GREATER_THAN -> ColumnConstraintDescriptorSet.CHECK_GREATER_THAN;
      case GREATER_OR_EQUAL -> ColumnConstraintDescriptorSet.CHECK_GREATER_OR_EQUAL;
      case HALF_OPEN_RANGE, IN, NOT_IN -> ColumnConstraintDescriptorSet.CHECK_NONE;
    };
  }

}
