package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalScanCursor;

/** Caller-owned capability for one ordered SQL table scan. */
public final class SqlScanCursor {
  private final RelationalScanCursor relational = new RelationalScanCursor();
  private SqlSession owner;
  private boolean implicitTransaction;
  private boolean active;
  private long rowsReturned;

  public StatusCode reset() {
    if (active) {
      return StatusCode.CONFLICT;
    }
    owner = null;
    implicitTransaction = false;
    rowsReturned = 0;
    return relational.reset();
  }

  RelationalScanCursor relational() {
    return relational;
  }

  StatusCode claim(SqlSession session, boolean implicit) {
    if (active) {
      return StatusCode.CONFLICT;
    }
    owner = session;
    implicitTransaction = implicit;
    active = true;
    rowsReturned = 0;
    return StatusCode.OK;
  }

  boolean isOwnedBy(SqlSession session) {
    return active && owner == session;
  }

  boolean implicitTransaction() {
    return implicitTransaction;
  }

  void complete() {
    active = false;
  }

  void rowReturned() {
    rowsReturned++;
  }

  public long rowsReturned() {
    return rowsReturned;
  }

  public boolean isActive() {
    return active;
  }
}
