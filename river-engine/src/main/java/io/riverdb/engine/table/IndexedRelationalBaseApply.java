package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.format.catalog.CatalogKeyspace;
import java.nio.ByteBuffer;

/** Applies one BASE suboperation to scalar logical-row mappings. */
final class IndexedRelationalBaseApply {
  private final IndexedTableKernel kernel;
  private final IndexedRelationalScalarWriter writer;
  private final ByteBuffer row = ByteBuffer.allocate(SqlShapeLimits.MAX_STORED_ROW_BYTES);

  IndexedRelationalBaseApply(IndexedTableKernel table, IndexedPageSet pages) {
    kernel = table;
    writer = new IndexedRelationalScalarWriter(table, pages);
  }

  StatusCode apply(IndexedRelationalMutationBuffer source, int operation) {
    if (kernel.operationRowCount() != source.expectedHeapVersionAt(operation)) {
      return StatusCode.CORRUPTION;
    }
    int first = source.suboperationFirstMutationAt(operation);
    int end = first + source.suboperationMutationCountAt(operation);
    boolean scalar = source.suboperationDescriptorAt(operation)
        == IndexedRelationalMutationBuffer.SCALAR_SUBOPERATION;
    for (int mutation = first; mutation < end; mutation++) {
      int kind = source.operationAt(mutation);
      int bytes = source.payloadLengthAt(mutation);
      boolean deletion = kind == IndexedRelationalMutationBuffer.BASE_DELETE
          || kind == IndexedRelationalMutationBuffer.SCALAR_DELETE;
      if (bytes == 0 && !deletion) return StatusCode.CORRUPTION;
      row.position(0);
      row.limit(deletion ? 1 : bytes);
      if (bytes > 0) source.copyPayloadTo(mutation, row, 0);
      else row.put(0, (byte) 0);
      StatusCode status = writer.stage(
          scalar ? source.spaceAt(mutation)
              : CatalogKeyspace.relationalBaseRowSpace(
                  source.suboperationOwnerAt(operation)),
          source.logicalRowIdAt(mutation), source.previousRowIdAt(mutation), row,
          deletion);
      if (!status.isOk()) return status;
    }
    return kernel.operationRowCount() == source.resultingHeapVersionAt(operation)
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }
}
