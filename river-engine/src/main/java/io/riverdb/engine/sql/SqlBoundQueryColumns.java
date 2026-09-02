package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.sql.SqlCommand;

/** Lazily retained immutable-name snapshot for the actual output shape of one query block. */
final class SqlBoundQueryColumns {
  private static final long CHARGED_BYTES_PER_LANE = 640;
  private final SqlSessionShapeBudget budget;
  private SqlBoundName[] names = new SqlBoundName[0];
  private SqlBoundName[] tables = new SqlBoundName[0];
  private SqlBoundName[] outputs = new SqlBoundName[0];
  private SqlBoundName[] aliases = new SqlBoundName[0];
  private boolean[] nulls = new boolean[0];
  private int count;

  SqlBoundQueryColumns(SqlSessionShapeBudget shapeBudget) {
    budget = shapeBudget;
  }

  StatusCode capture(SqlCommand source) {
    int columns = source.columnCount();
    StatusCode status = reserve(columns);
    if (!status.isOk()) return status;
    clear();
    for (int index = 0; index < columns; index++) {
      names[index].copyFrom(source.columnName(index));
      tables[index].copyFrom(source.columnTableName(index));
      outputs[index].copyFrom(source.columnOutputName(index));
      aliases[index].copyFrom(source.columnAlias(index));
      nulls[index] = source.isNullProjection(index);
    }
    count = columns;
    return StatusCode.OK;
  }

  void clear() {
    for (int index = 0; index < count; index++) {
      names[index].copyFrom("");
      tables[index].copyFrom("");
      outputs[index].copyFrom("");
      aliases[index].copyFrom("");
      nulls[index] = false;
    }
    count = 0;
  }

  CharSequence name(int index) { return names[index]; }
  CharSequence table(int index) { return tables[index]; }
  CharSequence output(int index) { return outputs[index]; }
  CharSequence alias(int index) { return aliases[index]; }
  boolean isNull(int index) { return nulls[index]; }

  private StatusCode reserve(int required) {
    if (required < 0 || required > SqlShapeLimits.MAX_RESULT_COLUMNS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (required <= names.length) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(
        names.length, required, SqlShapeLimits.MAX_RESULT_COLUMNS, 8);
    long charged = (capacity - names.length) * CHARGED_BYTES_PER_LANE;
    StatusCode admission = budget.reserve(charged);
    if (!admission.isOk()) return admission;
    try {
      SqlBoundName[] nextNames = new SqlBoundName[capacity];
      SqlBoundName[] nextTables = new SqlBoundName[capacity];
      SqlBoundName[] nextOutputs = new SqlBoundName[capacity];
      SqlBoundName[] nextAliases = new SqlBoundName[capacity];
      boolean[] nextNulls = new boolean[capacity];
      System.arraycopy(names, 0, nextNames, 0, names.length);
      System.arraycopy(tables, 0, nextTables, 0, tables.length);
      System.arraycopy(outputs, 0, nextOutputs, 0, outputs.length);
      System.arraycopy(aliases, 0, nextAliases, 0, aliases.length);
      System.arraycopy(nulls, 0, nextNulls, 0, nulls.length);
      for (int index = names.length; index < capacity; index++) {
        nextNames[index] = new SqlBoundName();
        nextTables[index] = new SqlBoundName();
        nextOutputs[index] = new SqlBoundName();
        nextAliases[index] = new SqlBoundName();
      }
      names = nextNames;
      tables = nextTables;
      outputs = nextOutputs;
      aliases = nextAliases;
      nulls = nextNulls;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      budget.rollback(charged);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }
}
