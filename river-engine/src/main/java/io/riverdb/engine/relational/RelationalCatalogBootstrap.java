package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.ByteBuffer;

/** Creation and validation of the catalog allocation record. */
final class RelationalCatalogBootstrap {
  private final HeapRowResult row = new HeapRowResult();
  private final ByteBuffer scratch = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
  private final ByteBuffer output = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
  private final CatalogSequenceCodec.IntResult nextTableId =
      new CatalogSequenceCodec.IntResult();

  StatusCode initialize(
      RelationalSession session, RelationalInternalSessionOwner sessions) {
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) {
      CatalogSequenceCodec.encodeAllocation(output, 1);
      status = session.indexedSession().insert(
          RelationalKey.CATALOG_SEQUENCE_SPACE, 0, output);
    }
    return sessions.finish(session, outcome, status);
  }

  StatusCode validate(
      RelationalSession session, RelationalInternalSessionOwner sessions) {
    if (session == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.begin(IsolationLevel.REPEATABLE_READ);
    if (status.isOk()) {
      status = session.indexedSession().fetchByKey(
          RelationalKey.CATALOG_SEQUENCE_SPACE, 0, row);
    }
    if (status.isOk()) {
      status = CatalogSequenceCodec.decodeAllocation(row, scratch, nextTableId);
    }
    return sessions.finish(session, outcome, status, false);
  }
}
