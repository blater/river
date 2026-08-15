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
    if (query.blockCount() < 2) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (firstBlock < 0 || firstBlock >= query.blockCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = firstBlock; index < query.blockCount(); index++) {
      SqlCommand block = query.block(index);
      StatusCode status = pipelineShape(block, index);
      if (status.isOk()) status = references.validate(index, true);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private static StatusCode pipelineShape(SqlCommand block, int index) {
    if (block.hasDisjunction() && block.hasComputedPredicate()
        || index > 0 && block.hasComputedPredicate()) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    if (block.type() == SqlCommandType.JOIN_SCAN) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    boolean admitted = block.type() == SqlCommandType.SCAN
        || block.type() == SqlCommandType.SELECT
        || block.type() == SqlCommandType.DISTINCT_SCAN
        || block.type() == SqlCommandType.COUNT
        || block.type() == SqlCommandType.COUNT_VALUE
        || block.type() == SqlCommandType.SUM
        || block.type() == SqlCommandType.AVG
        || block.type() == SqlCommandType.MIN
        || block.type() == SqlCommandType.MAX
        || block.type() == SqlCommandType.GROUP_COUNT
        || block.type() == SqlCommandType.GROUP_COUNT_VALUE
        || block.type() == SqlCommandType.GROUP_SUM
        || block.type() == SqlCommandType.GROUP_AVG
        || block.type() == SqlCommandType.GROUP_MIN
        || block.type() == SqlCommandType.GROUP_MAX;
    return admitted && !block.isSelectAll() && block.columnCount() > 0
            && (index == 0 || block.rowLimit() == Long.MAX_VALUE)
        ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private static StatusCode shapeStatus(SqlCommand block, int index) {
    if ((index > 0 && block.hasComputedPredicate())
        || block.hasDisjunction()
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
