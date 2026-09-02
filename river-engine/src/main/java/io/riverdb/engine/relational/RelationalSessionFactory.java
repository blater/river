package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.engine.EmbeddedDatabase;
import io.riverdb.engine.EmbeddedSessionOpenResult;

/** Opens reusable relational sessions with row capacity for the canonical 8 KiB format. */
final class RelationalSessionFactory {
  private final EmbeddedDatabase embedded;
  private final RelationalSchemaGate gate;
  private final RelationalDatabaseServices services;

  RelationalSessionFactory(
      EmbeddedDatabase database,
      RelationalSchemaGate schemaGate,
      RelationalDatabaseServices databaseServices) {
    embedded = database;
    gate = schemaGate;
    services = databaseServices;
  }

  StatusCode open(
      RelationalSchemaLifecycle lifecycle,
      RelationalSessionOpenResult relationalResult) {
    if (relationalResult == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    relationalResult.reset();
    EmbeddedSessionOpenResult result = new EmbeddedSessionOpenResult();
    StatusCode status = embedded.createSession(SqlShapeLimits.MAX_STORED_ROW_BYTES, result);
    if (!status.isOk()) return status;
    try {
      relationalResult.set(
          new RelationalSession(lifecycle, gate, result.session(), services));
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      result.session().close();
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  RelationalSession openOrNull(RelationalSchemaLifecycle lifecycle) {
    RelationalSessionOpenResult result = new RelationalSessionOpenResult();
    return open(lifecycle, result).isOk() ? result.session() : null;
  }

  StatusCode close() {
    return StatusCode.OK;
  }
}
