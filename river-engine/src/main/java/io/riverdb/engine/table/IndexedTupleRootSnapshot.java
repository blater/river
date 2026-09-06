package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleIndexRootRecord;
import io.riverdb.format.btree.TupleIndexRootRecordCodec;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Reusable decoded view of one durable tuple-index root record. */
final class IndexedTupleRootSnapshot {
  private final IndexedTableKernel kernel;
  private final IndexedVersionedRowResult version = new IndexedVersionedRowResult();
  private final HeapRowResult row = new HeapRowResult();
  private final TupleIndexRootRecord record = new TupleIndexRootRecord();
  private final ByteBuffer bytes = ByteBuffer.allocate(TupleIndexRootRecordCodec.BYTES);
  private final CRC32C checksum = new CRC32C();

  IndexedTupleRootSnapshot(IndexedTableKernel table) {
    kernel = table;
  }

  StatusCode load(long visible, long keyId) {
    StatusCode status = kernel.fetchVersionedByKeyAt(
        visible, CatalogKeyspace.INDEX_ROOT_SPACE, keyId, row, version);
    if (!status.isOk()) return pressure(status) ? status : StatusCode.CORRUPTION;
    bytes.clear();
    status = row.copyTo(bytes);
    bytes.flip();
    if (status.isOk()) {
      status = TupleIndexRootRecordCodec.decode(bytes, 0, record, checksum);
    }
    return status.isOk() || pressure(status) ? status : StatusCode.CORRUPTION;
  }

  boolean matches(long owner, long keyId, long schemaId, TupleShape shape) {
    if (record.state() != TupleIndexRootRecordCodec.STATE_READY
        || record.rootPageId() <= 0 || record.privateOwner() != 0
        || record.cleanupCursor() != 0 || record.ownerObjectId() != owner
        || record.keyId() != keyId || record.schemaId() != schemaId
        || record.descriptorHash() != shape.descriptorHash()
        || record.descriptorCount() != shape.partCount()) return false;
    for (int part = 0; part < record.descriptorCount(); part++) {
      if (record.descriptorAt(part) != shape.descriptorAt(part)) return false;
    }
    return true;
  }

  boolean matchesBuilding(
      long owner, long keyId, long schemaId, long privateOwner, TupleShape shape) {
    if (record.state() != TupleIndexRootRecordCodec.STATE_BUILDING
        || record.rootPageId() <= 0 || record.privateOwner() != privateOwner
        || privateOwner <= 0 || record.cleanupCursor() != 0
        || record.ownerObjectId() != owner || record.keyId() != keyId
        || record.schemaId() != schemaId
        || record.descriptorHash() != shape.descriptorHash()
        || record.descriptorCount() != shape.partCount()) return false;
    for (int part = 0; part < record.descriptorCount(); part++) {
      if (record.descriptorAt(part) != shape.descriptorAt(part)) return false;
    }
    return true;
  }

  // Every tuple mutation versions its registry row, covering entries and negative decisions.
  long observedCommitSequence() { return version.observedCommitSequence(); }

  long generation() { return record.generation(); }

  int rootPageId() { return record.rootPageId(); }

  private static boolean pressure(StatusCode status) {
    return status == StatusCode.RESOURCE_EXHAUSTED || status == StatusCode.IO_FAILURE;
  }
}
