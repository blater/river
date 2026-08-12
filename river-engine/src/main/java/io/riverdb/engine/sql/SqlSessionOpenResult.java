package io.riverdb.engine.sql;

/** Caller-owned output for an executable SQL session. */
public final class SqlSessionOpenResult {
  private SqlSession session;

  public void reset() {
    session = null;
  }

  void set(SqlSession opened) {
    session = opened;
  }

  public SqlSession session() {
    return session;
  }
}
