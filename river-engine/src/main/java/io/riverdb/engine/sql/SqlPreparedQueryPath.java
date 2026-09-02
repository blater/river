package io.riverdb.engine.sql;

/** Caller-owned physical path selected for one prepared singleton query. */
public final class SqlPreparedQueryPath {
  private boolean point;

  public void reset() { point = false; }

  public boolean point() { return point; }

  void point(boolean selected) { point = selected; }
}
