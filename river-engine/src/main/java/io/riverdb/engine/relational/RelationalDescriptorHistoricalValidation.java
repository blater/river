package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;

/** Exact retained-layout lookup with immediate pin release on every local failure. */
final class RelationalDescriptorHistoricalValidation {
  private final RelationalDatabaseServices services;
  private final SchemaPin pin = new SchemaPin();
  private final StatusDetail detail = new StatusDetail(128);

  RelationalDescriptorHistoricalValidation(RelationalDatabaseServices databaseServices) {
    services = databaseServices;
  }

  StatusCode open(TableDescriptor current, long rowLayoutId, SqlValueBuffer values) {
    StatusCode status = close();
    if (!status.isOk()) return status;
    detail.reset();
    status = services.openRetained(current.tableId(), rowLayoutId, pin, detail);
    if (status == StatusCode.CONFLICT) return StatusCode.CORRUPTION;
    if (!status.isOk()) return status;
    TableDescriptor descriptor = pin.descriptor();
    status = pin.isPublished() && descriptor != null
        && descriptor.tableId() == current.tableId()
        && descriptor.rowLayoutId() == rowLayoutId
        ? RelationalDescriptorShapeValidation.reserve(descriptor, values)
        : StatusCode.CORRUPTION;
    if (!status.isOk()) status = finish(status, close());
    return status;
  }

  TableDescriptor value() {
    return pin.descriptor();
  }

  StatusCode close() {
    return pin.isActive() ? pin.release() : StatusCode.OK;
  }

  private static StatusCode finish(StatusCode status, StatusCode cleanup) {
    return status.isOk() ? cleanup : status;
  }
}
