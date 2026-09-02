package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalDescriptorScanCursor;
import io.riverdb.engine.relational.RelationalLockedCandidateResult;
import io.riverdb.engine.relational.RelationalRowIdentityResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.sql.SqlCommand;

/** Owns one reusable descriptor cursor and its independently selected access path. */
final class SqlDescriptorPointScanAccess {
  private final RelationalSession session;
  private final SqlDescriptorIndexAccess index = new SqlDescriptorIndexAccess();
  private final RelationalDescriptorScanCursor cursor = new RelationalDescriptorScanCursor();
  private final RelationalRowIdentityResult identity = new RelationalRowIdentityResult();
  private final RelationalLockedCandidateResult lockedCandidate =
      new RelationalLockedCandidateResult();

  SqlDescriptorPointScanAccess(RelationalSession relationalSession) {
    session = relationalSession;
  }

  StatusCode prepare(
      SqlCommand command, TableDescriptor table, SqlDescriptorPredicate predicate) {
    return index.prepare(command, table, predicate.bindings(), 0, null, null);
  }

  StatusCode open(SchemaPin pin) {
    StatusCode status = cursor.reset();
    if (!status.isOk()) return status;
    return index.active()
        ? session.descriptorRows().beginIndexScan(pin, index.bounds(), cursor)
        : session.descriptorRows().beginScan(pin, cursor);
  }

  StatusCode next(SqlDescriptorMutationValues values) {
    return session.descriptorRows().nextScan(cursor, values.fetched(), identity);
  }

  StatusCode lockCandidate(SqlDescriptorMutationValues values) {
    return session.descriptorRows().lockScannedCandidate(
        cursor, values.fetched(), lockedCandidate);
  }

  boolean candidateLocked() { return lockedCandidate.isLocked(); }

  StatusCode update(SqlDescriptorMutationValues values) {
    return session.descriptorRows().updateScanned(cursor, values.mutation());
  }

  boolean currentBorrowed() { return session.descriptorRows().currentBorrowed(); }
  StatusCode releaseCurrent() { return session.descriptorRows().releaseCurrent(); }
  StatusCode finishCandidate(StatusCode original) {
    if (!currentBorrowed()) return original;
    StatusCode released = releaseCurrent();
    return released.isOk() ? original : released;
  }

  StatusCode delete() { return session.descriptorRows().deleteScanned(cursor); }
  boolean exactUnique() { return index.exactUnique(); }
  boolean active() { return cursor.isActive(); }

  StatusCode close() {
    StatusCode status = cursor.isActive()
        ? session.descriptorRows().closeScan(cursor) : StatusCode.OK;
    index.reset();
    return status;
  }
}
