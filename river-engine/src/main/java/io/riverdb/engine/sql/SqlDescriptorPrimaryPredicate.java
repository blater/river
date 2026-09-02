package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;

/** Binds one exact, typed primary-key equality predicate without per-statement allocation. */
final class SqlDescriptorPrimaryPredicate {
  private final SqlDescriptorPrimaryBinding binding = new SqlDescriptorPrimaryBinding();

  SqlValueBuffer values() { return binding.values(); }

  StatusCode bind(SqlCommand sql, TableDescriptor descriptor) {
    return binding.bind(sql, descriptor);
  }

  static boolean same(CharSequence left, CharSequence right) {
    if (left.length() != right.length()) return false;
    for (int index = 0; index < left.length(); index++) {
      if (left.charAt(index) != right.charAt(index)) return false;
    }
    return true;
  }
}
