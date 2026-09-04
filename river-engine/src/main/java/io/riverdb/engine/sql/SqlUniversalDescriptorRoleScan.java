package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalDescriptorIndexBounds;
import io.riverdb.engine.relational.RelationalDescriptorScanCursor;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.tx.api.lock.LockMode;

/** Owns the reopenable scan pin and cursor for one universal join role. */
final class SqlUniversalDescriptorRoleScan {
  private final RelationalSession session;
  private final SchemaPin pin = new SchemaPin();
  private final RelationalDescriptorScanCursor cursor = new RelationalDescriptorScanCursor();
  private final SqlUniversalDescriptorScanAdmission admission =
      new SqlUniversalDescriptorScanAdmission();
  private boolean empty;

  SqlUniversalDescriptorRoleScan(RelationalSession relationalSession) {
    session = relationalSession;
  }

  StatusCode open(
      SqlUniversalDescriptorName name, TableDescriptor descriptor,
      SqlUniversalDescriptorIndexAccess access,
      SqlUniversalDescriptorIndexAccess fixedAccess,
      SqlUniversalJoinRows rows, SqlNestedRowProvider ancestors,
      boolean fullScan,
      int mergeColumn, RelationalDescriptorIndexBounds mergeBounds) {
    if (cursor.isActive() || pin.isActive()) return StatusCode.CONFLICT;
    StatusCode status = admission.prepare(
        session, name, pin, descriptor, access, fixedAccess,
        rows, ancestors, fullScan);
    empty = admission.empty();
    if (status.isOk() && !empty) {
      status = begin(admission.selected(), fullScan, mergeColumn, mergeBounds);
    }
    if (!status.isOk() && pin.isActive()) pin.release();
    if (status.isOk() && empty && pin.isActive()) status = pin.release();
    return status;
  }

  private StatusCode begin(
      SqlUniversalDescriptorIndexAccess selected, boolean fullScan,
      int mergeColumn, RelationalDescriptorIndexBounds mergeBounds) {
    if (!fullScan && mergeColumn >= 0) {
      return session.descriptorRows().beginIndexScan(
          pin, mergeBounds, LockMode.SHARED, cursor);
    }
    return !fullScan && selected.active()
        ? session.descriptorRows().beginIndexScan(
            pin, selected.bounds(), LockMode.SHARED, cursor)
        : session.descriptorRows().beginScan(pin, cursor);
  }

  StatusCode next(SqlUniversalDescriptorJoinRow current, TableDescriptor descriptor) {
    return empty ? StatusCode.CONFLICT : current.next(session, cursor, descriptor);
  }

  StatusCode close() {
    StatusCode status = cursor.isActive()
        ? session.descriptorRows().closeScan(cursor) : StatusCode.OK;
    if (status.isOk() && !cursor.isActive()) status = cursor.reset();
    if (status.isOk() && pin.isActive()) status = pin.release();
    return status;
  }

  boolean hasResources() { return cursor.isActive() || pin.isActive(); }

}
