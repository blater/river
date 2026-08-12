package io.riverdb.engine.relational;

/** Caller-owned output for a logical relational session. */
public final class RelationalSessionOpenResult {
  private RelationalSession session;

  public void reset() {
    session = null;
  }

  void set(RelationalSession opened) {
    session = opened;
  }

  public RelationalSession session() {
    return session;
  }
}
