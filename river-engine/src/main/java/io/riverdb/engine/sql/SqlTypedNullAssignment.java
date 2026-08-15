package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlTypeDescriptor;

/** Validates a typed NULL family without applying value width or precision. */
final class SqlTypedNullAssignment {
  private SqlTypedNullAssignment() {
  }

  static boolean compatible(int source, int target) {
    return source == 0
        || SqlTypeDescriptor.canCompare(source, target);
  }
}
