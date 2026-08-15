package io.riverdb.sql;

/** Stable primitive aggregate operation identifiers. */
public final class SqlAggregateKind {
  public static final int COUNT = 1;
  public static final int COUNT_VALUE = 2;
  public static final int SUM = 3;
  public static final int AVG = 4;
  public static final int MIN = 5;
  public static final int MAX = 6;

  private SqlAggregateKind() {}
}
