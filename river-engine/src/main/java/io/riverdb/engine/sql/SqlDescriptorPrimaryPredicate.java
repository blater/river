package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.SqlValueAccess;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;

/** Binds one exact, typed primary-key equality predicate without per-statement allocation. */
final class SqlDescriptorPrimaryPredicate {
  private final SqlDescriptorPrimaryBinding binding = new SqlDescriptorPrimaryBinding();

  SqlValueAccess values() { return binding.values(); }

  StatusCode bind(SqlCommand sql, TableDescriptor descriptor) {
    return binding.bind(sql, descriptor);
  }
}
