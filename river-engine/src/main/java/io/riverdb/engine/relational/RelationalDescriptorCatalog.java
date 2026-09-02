package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;

/** Public catalog-v2 table descriptor boundary sharing the database close gate. */
public final class RelationalDescriptorCatalog {
  private final RelationalDatabaseServices services;

  RelationalDescriptorCatalog(RelationalDatabaseServices databaseServices) {
    services = databaseServices;
  }

  public StatusCode create(
      TableDescriptor provisional, SchemaPin pin, StatusDetail detail) {
    return services.create(provisional, pin, detail);
  }

  public StatusCode open(long objectId, SchemaPin pin, StatusDetail detail) {
    return services.open(objectId, pin, detail);
  }
}
