package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.btree.BTreeRootPage;
import java.nio.ByteBuffer;

/** Stages pending scalar rows and appends their exact grouped-WAL evidence. */
final class IndexedHybridScalarCompiler {
  private final IndexedTableKernel kernel;
  private final IndexedPageSet pages;
  private final IndexedRelationalScalarWriter scalar;
  private final ByteBuffer row = ByteBuffer.allocate(
      io.riverdb.base.sql.SqlShapeLimits.MAX_STORED_ROW_BYTES);

  IndexedHybridScalarCompiler(IndexedTableKernel table, IndexedPageSet pageSet) {
    kernel = table;
    pages = pageSet;
    scalar = new IndexedRelationalScalarWriter(table, pageSet);
  }

  StatusCode compile(PendingMutationBuffer pending, IndexedRelationalMutation mutation) {
    ByteBuffer expected = metadata();
    if (expected == null) return StatusCode.CORRUPTION;
    int expectedRoot = BTreeRootPage.rootPageId(expected);
    int expectedNext = BTreeRootPage.nextPageId(expected);
    long expectedHeap = kernel.operationRowCount();
    StatusCode status = stage(pending);
    ByteBuffer resulting = status.isOk() ? metadata() : null;
    if (status.isOk() && resulting == null) status = StatusCode.CORRUPTION;
    if (!status.isOk()) return status;
    status = mutation.appendSuboperation(
        0, IndexedRelationalMutation.SCALAR_SUBOPERATION, 0, pending.count(),
        0, 0, expectedRoot, BTreeRootPage.rootPageId(resulting),
        expectedNext, BTreeRootPage.nextPageId(resulting), 0, 0,
        expectedHeap, kernel.operationRowCount(),
        IndexedRelationalMutation.REGISTRY_ABSENT,
        IndexedRelationalMutation.REGISTRY_ABSENT, 0, 0);
    return status.isOk() ? append(pending, mutation) : status;
  }

  int payloadBytes(PendingMutationBuffer pending) {
    int bytes = 0;
    for (int index = 0; index < pending.count(); index++) {
      if (pending.operationAt(index) == IndexedWalCodec.MUTATION_DELETE) continue;
      int length = pending.rowLengthAt(index);
      if (length > Integer.MAX_VALUE - bytes) return -1;
      bytes += length;
    }
    return bytes;
  }

  private StatusCode stage(PendingMutationBuffer pending) {
    for (int index = 0; index < pending.count(); index++) {
      int operation = pending.operationAt(index);
      boolean deleted = operation == IndexedWalCodec.MUTATION_DELETE;
      loadRow(pending, index, deleted);
      StatusCode status = scalar.stage(
          pending.spaceAt(index), pending.keyAt(index), pending.previousRowIdAt(index),
          row, deleted);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private StatusCode append(
      PendingMutationBuffer pending, IndexedRelationalMutation mutation) {
    for (int index = 0; index < pending.count(); index++) {
      int operation = pending.operationAt(index);
      boolean deleted = operation == IndexedWalCodec.MUTATION_DELETE;
      if (!deleted) loadRow(pending, index, false);
      StatusCode status = mutation.appendScalar(
          0, physicalOperation(operation, pending.previousRowIdAt(index)),
          pending.spaceAt(index), pending.keyAt(index),
          pending.previousRowIdAt(index), deleted ? null : row, 0,
          deleted ? 0 : pending.rowLengthAt(index));
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private void loadRow(PendingMutationBuffer pending, int index, boolean deleted) {
    int bytes = deleted ? 1 : pending.rowLengthAt(index);
    row.clear();
    row.limit(bytes);
    if (deleted) row.put(0, (byte) 0);
    else pending.copyRowTo(index, row, 0);
  }

  private ByteBuffer metadata() {
    ByteBuffer metadata = pages.operationPayload(IndexedTableKernel.ROOT_META_PAGE_ID);
    return metadata != null && BTreeRootPage.validate(metadata).isOk() ? metadata : null;
  }

  private static int physicalOperation(int value, long previousRowId) {
    return value == IndexedWalCodec.MUTATION_INSERT
        ? previousRowId == 0
            ? IndexedRelationalMutation.SCALAR_INSERT
            : IndexedRelationalMutation.SCALAR_UPDATE
        : value == IndexedWalCodec.MUTATION_UPDATE
            ? IndexedRelationalMutation.SCALAR_UPDATE
            : IndexedRelationalMutation.SCALAR_DELETE;
  }
}
