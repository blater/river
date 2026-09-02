package io.riverdb.engine.relational;

/**
 * Caller-owned internal identity result.
 *
 * <p>An identity returned by INSERT is provisional until its outer transaction commits and must
 * not be retained after rollback or abort. Committed identities are never reused.
 */
public final class RelationalRowIdentityResult {
  private long logicalRowId;

  public void reset() {
    logicalRowId = 0;
  }

  void set(long value) {
    logicalRowId = value;
  }

  public boolean isAvailable() {
    return logicalRowId > 0;
  }

  public long logicalRowId() {
    return logicalRowId;
  }
}
