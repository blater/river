package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;

/** Validates the published-current boundary for one transactional descriptor successor. */
final class RelationalDescriptorSuccessorInput {
  private RelationalDescriptorSuccessorInput() {
  }

  static StatusCode validate(
      boolean registeredTransaction, SchemaPin current, TableDescriptor proposed) {
    if (!registeredTransaction || current == null || !current.isActive()
        || proposed == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    return current.isPublished() ? StatusCode.OK : StatusCode.CONFLICT;
  }
}
