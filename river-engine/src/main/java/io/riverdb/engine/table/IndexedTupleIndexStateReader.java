package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleIndexRootRecord;
import io.riverdb.format.btree.TupleIndexRootRecordCodec;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Decodes one registry row through the owning transaction's snapshot. */
final class IndexedTupleIndexStateReader {
  private final HeapRowResult row = new HeapRowResult();
  private final ByteBuffer bytes = ByteBuffer.allocate(TupleIndexRootRecordCodec.BYTES);
  private final TupleIndexRootRecord record = new TupleIndexRootRecord();
  private final CRC32C checksum = new CRC32C();

  StatusCode read(
      IndexedTransactionSession session, long keyId, IndexedTupleIndexState result) {
    if (result == null || !CatalogKeyspace.validKeyId(keyId)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = session.fetchByKey(CatalogKeyspace.INDEX_ROOT_SPACE, keyId, row);
    if (!status.isOk()) return status;
    bytes.clear();
    status = row.copyTo(bytes);
    bytes.flip();
    if (status.isOk()) status = TupleIndexRootRecordCodec.decode(bytes, 0, record, checksum);
    if (!status.isOk()) return status;
    if (record.keyId() != keyId) return StatusCode.CORRUPTION;
    result.set(record.state(), record.rootPageId(), record.cleanupCursor(), record.keyId(),
        record.ownerObjectId(), record.schemaId(), record.descriptorHash(),
        record.privateOwner(), record.generation(), record);
    return StatusCode.OK;
  }
}
