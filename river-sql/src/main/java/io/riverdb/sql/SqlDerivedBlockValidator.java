package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Applies the bounded, projection-only derived-block admission policy. */
final class SqlDerivedBlockValidator {
  private final SqlQuery query;
  private final SqlDerivedReferenceValidator references;

  SqlDerivedBlockValidator(SqlQuery ownedQuery) {
    query = ownedQuery;
    references = new SqlDerivedReferenceValidator(ownedQuery);
  }

  StatusCode validate(boolean allowUnusedComputed) {
    if (query.blockCount() < 2) return StatusCode.INVALID_EXTERNAL_INPUT;
    for (int index = 0; index < query.blockCount(); index++) {
      StatusCode status = shapeStatus(query.block(index), index);
      if (status.isOk()) status = references.validate(index, allowUnusedComputed);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  StatusCode validatePipeline() {
    return validatePipeline(0);
  }

  StatusCode validatePipeline(int firstBlock) {
    return SqlDerivedPipelinePolicy.validate(query, references, firstBlock);
  }

  private static StatusCode shapeStatus(SqlCommand block, int index) {
    if ((index > 0 && block.isOrdered())
        || unsupported(block)) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    return (block.type() == SqlCommandType.SCAN || block.type() == SqlCommandType.SELECT)
            && !block.isSelectAll()
            && block.columnCount() > 0
            && (index == 0 || block.rowLimit() == Long.MAX_VALUE)
        ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private static boolean unsupported(SqlCommand block) {
    return switch (block.type()) {
      case JOIN_SCAN, DISTINCT_SCAN,
          GROUP_COUNT, GROUP_COUNT_VALUE, GROUP_SUM, GROUP_AVG, GROUP_MIN, GROUP_MAX,
          COUNT, COUNT_VALUE, SUM, AVG, MIN, MAX -> true;
      default -> false;
    };
  }
}
