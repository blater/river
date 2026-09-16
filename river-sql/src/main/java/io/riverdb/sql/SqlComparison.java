package io.riverdb.sql;

/** Literal BIGINT comparison admitted by the current SQL execution profile. */
public enum SqlComparison {
  EQUAL,
  NOT_EQUAL,
  LESS_THAN,
  LESS_OR_EQUAL,
  GREATER_THAN,
  GREATER_OR_EQUAL,
  HALF_OPEN_RANGE,
  IN,
  NOT_IN;

  /** Swaps ordered comparison direction; leaves other operators unchanged. */
  public SqlComparison reverseOrder() {
    return switch (this) {
      case LESS_THAN -> GREATER_THAN;
      case LESS_OR_EQUAL -> GREATER_OR_EQUAL;
      case GREATER_THAN -> LESS_THAN;
      case GREATER_OR_EQUAL -> LESS_OR_EQUAL;
      default -> this;
    };
  }
}
