package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Immutable actual-count physical query-block pipeline. */
final class SqlTemplateQueryBlocks {
  private final SqlStatementTemplate[] blocks;
  private final int sourcePlanDepth;
  private final boolean blockPipeline;
  private final boolean explain;
  private final boolean analyze;

  SqlTemplateQueryBlocks(SqlQuery source) {
    int count = source == null ? 0 : source.blockCount();
    blocks = new SqlStatementTemplate[count];
    for (int block = 0; block < count; block++) {
      blocks[block] = new SqlStatementTemplate(source.block(block), null);
    }
    sourcePlanDepth = source == null ? 0 : source.sourcePlanDepth();
    blockPipeline = source != null && source.isBlockPipeline();
    explain = source != null && source.isExplain();
    analyze = source != null && source.isAnalyze();
  }

  StatusCode restore(SqlQuery target) {
    for (SqlStatementTemplate block : blocks) {
      SqlCommand command = target.nextBlock();
      if (command == null) return StatusCode.QUERY_TOO_COMPLEX;
      StatusCode status = block.restoreCommand(command);
      if (!status.isOk()) return status;
    }
    target.setSourceMetadata(sourcePlanDepth, explain, analyze);
    if (blockPipeline) target.markBlockPipeline();
    return StatusCode.OK;
  }

  int parameterMaximum() {
    int maximum = -1;
    for (SqlStatementTemplate block : blocks) {
      maximum = Math.max(maximum, block.parameterMaximum());
    }
    return maximum;
  }

  long byteCharge() {
    long bytes = SqlTemplateRetainedSize.add(48L, SqlTemplateRetainedSize.array(
        blocks.length, SqlTemplateRetainedSize.REFERENCE_BYTES));
    for (SqlStatementTemplate block : blocks) {
      bytes = SqlTemplateRetainedSize.add(bytes, block.byteCharge());
    }
    return bytes;
  }
}
