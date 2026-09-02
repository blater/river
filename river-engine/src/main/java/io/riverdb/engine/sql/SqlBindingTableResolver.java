package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.relational.RelationalDescriptorJoinTableView;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.schema.cache.SchemaPin;

/** Resolves a catalog-v2 or legacy table into the common binder-only table view. */
final class SqlBindingTableResolver {
  private final SchemaPin pin = new SchemaPin();
  private final StatusDetail detail = new StatusDetail(128);
  private final RelationalDescriptorJoinTableView descriptorView =
      new RelationalDescriptorJoinTableView();
  private boolean descriptor;

  StatusCode resolve(
      RelationalSession session, CharSequence name, TableDefinition target) {
    descriptor = false;
    if (target == null) return StatusCode.RESOURCE_EXHAUSTED;
    detail.reset();
    StatusCode status = session.resolveDescriptor(name, pin, detail);
    if (status == StatusCode.CONFLICT) return session.resolveTable(name, target);
    if (!status.isOk()) return status;
    status = descriptorView.prepare(pin.descriptor(), target);
    StatusCode released = pin.release();
    if (status.isOk()) status = released;
    descriptor = status.isOk();
    return status;
  }

  boolean descriptor() {
    return descriptor;
  }
}
