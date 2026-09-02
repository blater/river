package io.riverdb.engine.sql;

import io.riverdb.sql.SqlComparison;

/** Shared truth mapping for an already-compared descriptor value pair. */
final class SqlDescriptorComparison {
  private SqlDescriptorComparison() { }

  static boolean matches(int compared, SqlComparison comparison) {
    return switch (comparison) {
      case EQUAL -> compared == 0;
      case NOT_EQUAL -> compared != 0;
      case LESS_THAN -> compared < 0;
      case LESS_OR_EQUAL -> compared <= 0;
      case GREATER_THAN -> compared > 0;
      case GREATER_OR_EQUAL -> compared >= 0;
      default -> false;
    };
  }
}
