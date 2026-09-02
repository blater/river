package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;

/** Descriptor fallback and pin ownership for SHOW catalog scans. */
final class SqlCatalogDescriptorResolution {
  private final RelationalSession session;
  private final SchemaPin pin = new SchemaPin();
  private final StatusDetail detail = new StatusDetail(128);
  private TableDescriptor table;

  SqlCatalogDescriptorResolution(RelationalSession relationalSession) {
    session = relationalSession;
  }

  void reset() { table = null; }
  TableDescriptor table() { return table; }

  StatusCode resolve(CharSequence tableName, StatusCode legacy) {
    if (legacy != StatusCode.CONFLICT && legacy != StatusCode.CORRUPTION) return legacy;
    detail.reset();
    StatusCode status = session.resolveDescriptor(tableName, pin, detail);
    if (status.isOk()) table = pin.descriptor();
    return legacy == StatusCode.CORRUPTION && status == StatusCode.CONFLICT
        ? legacy : status;
  }

  StatusCode close() {
    StatusCode status = pin.isActive() ? pin.release() : StatusCode.OK;
    if (status.isOk()) table = null;
    return status;
  }
}
