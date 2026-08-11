package io.riverdb.sql;

/** Literal BIGINT comparison admitted by the current SQL execution profile. */
public enum SqlComparison {
  EQUAL,
  NOT_EQUAL,
  LESS_THAN,
  LESS_OR_EQUAL,
  GREATER_THAN,
  GREATER_OR_EQUAL,
  HALF_OPEN_RANGE
}
