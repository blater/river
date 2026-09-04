package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Paged internal tuple identities aligned with universal-join build ordinals. */
final class SqlUniversalJoinIdentities {
  private final SqlBlockSchema schema;
  private final SqlBlockRow row;
  private final SqlBlockRowStore store;

  SqlUniversalJoinIdentities(SqlSessionShapeBudget budget) {
    schema = new SqlBlockSchema(budget);
    row = new SqlBlockRow(budget);
    store = new SqlBlockRowStore(budget);
  }

  StatusCode begin() {
    StatusCode status = close();
    if (!status.isOk()) return status;
    schema.set(1);
    schema.setColumn(0, "", SqlTypeDescriptor.BIGINT, false);
    status = schema.status();
    if (status.isOk()) status = row.reset(1);
    return status.isOk() ? store.begin(schema, -1, false) : status;
  }

  StatusCode append(long identity) {
    row.setValue(0, identity);
    return store.append(row);
  }

  StatusCode finish() {
    return store.finish();
  }

  StatusCode read(long ordinal) {
    return store.readAt(ordinal, row);
  }

  long identity() {
    return row.value(0);
  }

  StatusCode close() {
    StatusCode status = store.close();
    row.reset(0);
    schema.reset();
    return status;
  }

  boolean hasResources() { return store.hasResources(); }
}
