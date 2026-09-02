package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalDescriptorJoinTableView;
import io.riverdb.engine.relational.RelationalDescriptorScanCursor;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;

/** Descriptor-native full scan feeding the shared bounded ANALYZE accumulator. */
final class SqlDescriptorAnalyzeScan {
  private final RelationalSession session;
  private final SchemaPin pin = new SchemaPin();
  private final RelationalDescriptorScanCursor cursor =
      new RelationalDescriptorScanCursor();
  private final RelationalDescriptorJoinTableView view =
      new RelationalDescriptorJoinTableView();
  private final SqlUniversalDescriptorJoinRow current =
      new SqlUniversalDescriptorJoinRow();
  private TableDescriptor descriptor;

  SqlDescriptorAnalyzeScan(RelationalSession relationalSession) {
    session = relationalSession;
  }

  StatusCode resolve(CharSequence name, TableDefinition table) {
    StatusCode status = reset();
    if (status.isOk()) status = session.resolveDescriptor(name, pin, null);
    if (!status.isOk()) return status;
    descriptor = pin.descriptor();
    status = view.prepare(descriptor, table);
    if (status.isOk()) status = current.prepare(descriptor);
    return status.isOk() ? status : fail(status);
  }

  StatusCode begin() {
    return descriptor == null
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : session.descriptorRows().beginScan(pin, cursor);
  }

  StatusCode next() { return current.next(session, cursor, descriptor); }
  SqlBlockRow row() { return current.row(); }
  boolean hasResources() { return cursor.isActive() || pin.isActive(); }

  StatusCode reset() {
    StatusCode status = cursor.isActive()
        ? session.descriptorRows().closeScan(cursor) : StatusCode.OK;
    if (status.isOk() && !cursor.isActive()) status = cursor.reset();
    if (status.isOk() && pin.isActive()) status = pin.release();
    if (status.isOk()) {
      descriptor = null;
      current.reset();
    }
    return status;
  }

  private StatusCode fail(StatusCode failure) {
    StatusCode closed = reset();
    return failure.isOk() ? closed : failure;
  }
}
