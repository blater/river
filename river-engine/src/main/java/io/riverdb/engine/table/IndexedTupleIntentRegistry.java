package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleIndexRootRecord;
import io.riverdb.format.btree.TupleIndexRootRecordCodec;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Reads one tuple root and stages its next BUILDING or READY generation. */
final class IndexedTupleIntentRegistry {
  private final IndexedTableStore store;
  private final IndexedTableKernel kernel;
  private final IndexedRelationalScalarWriter writer;
  private final IndexedRelationalScalarLookup lookup;
  private final IndexedMutationTarget target = new IndexedMutationTarget();
  private final HeapRowResult row = new HeapRowResult();
  private final TupleIndexRootRecord record = new TupleIndexRootRecord();
  private final ByteBuffer bytes = ByteBuffer.allocate(TupleIndexRootRecordCodec.BYTES);
  private final int[] descriptors =
      new int[io.riverdb.format.btree.TupleKeyCodec.MAX_INDEX_KEY_PARTS];
  private final CRC32C checksum = new CRC32C();
  private long previousRowId;

  IndexedTupleIntentRegistry(
      IndexedTableStore table, IndexedTableKernel tableKernel, IndexedPageSet pages) {
    store = table;
    kernel = tableKernel;
    writer = new IndexedRelationalScalarWriter(tableKernel, pages);
    lookup = new IndexedRelationalScalarLookup(tableKernel, pages);
  }

  StatusCode load(IndexedTupleIntentJournal journal, int descriptor) {
    long keyId = journal.keyIdAt(descriptor);
    StatusCode status = lookup.find(CatalogKeyspace.INDEX_ROOT_SPACE, keyId);
    if (status.isOk()) status = kernel.fetchOperationRow(lookup.rowId(), row);
    if (!status.isOk()) return pressure(status) ? status : StatusCode.CORRUPTION;
    bytes.clear();
    status = row.copyTo(bytes);
    bytes.flip();
    if (status.isOk()) status = TupleIndexRootRecordCodec.decode(bytes, 0, record, checksum);
    if (!status.isOk()) return pressure(status) ? status : StatusCode.CORRUPTION;
    previousRowId = lookup.rowId();
    return matches(journal, descriptor) ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  StatusCode loadBuilding(IndexedTupleIndexLifecycleBatch batch, int index) {
    StatusCode status = loadRecord(batch.keyIdAt(index));
    if (!status.isOk()) return status;
    status = kernel.prepareMutation(
        store.lastCommitSequence, CatalogKeyspace.INDEX_ROOT_SPACE,
        batch.keyIdAt(index), target);
    if (!status.isOk()) return pressure(status) ? status : StatusCode.CORRUPTION;
    previousRowId = target.rowId();
    return matchesBuilding(batch, index) ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  StatusCode stage(int resultingRoot, boolean building, long privateOwner) {
    if (record.generation() == Long.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    int count = record.descriptorCount();
    StatusCode status = record.copyDescriptors(descriptors, 0);
    bytes.clear();
    if (status.isOk()) status = TupleIndexRootRecordCodec.encode(
        bytes, 0, building ? TupleIndexRootRecordCodec.STATE_BUILDING
            : TupleIndexRootRecordCodec.STATE_READY, resultingRoot,
        record.keyId(), record.ownerObjectId(), record.schemaId(),
        record.descriptorHash(), building ? privateOwner : 0, record.generation() + 1,
        descriptors, 0, count, checksum);
    bytes.position(0);
    bytes.limit(TupleIndexRootRecordCodec.BYTES);
    return status.isOk() ? writer.stage(
        CatalogKeyspace.INDEX_ROOT_SPACE, record.keyId(), previousRowId, bytes, false) : status;
  }

  int rootPageId() { return record.rootPageId(); }
  long generation() { return record.generation(); }

  private StatusCode loadRecord(long keyId) {
    StatusCode status = store.fetchByKey(CatalogKeyspace.INDEX_ROOT_SPACE, keyId, row);
    if (!status.isOk()) return pressure(status) ? status : StatusCode.CORRUPTION;
    bytes.clear();
    status = row.copyTo(bytes);
    bytes.flip();
    status = status.isOk()
        ? TupleIndexRootRecordCodec.decode(bytes, 0, record, checksum) : status;
    return status;
  }

  private boolean matches(IndexedTupleIntentJournal journal, int descriptor) {
    TupleShape shape = journal.shapeAt(descriptor);
    if (record.state() != TupleIndexRootRecordCodec.STATE_READY
        || record.rootPageId() <= 0 || record.privateOwner() != 0
        || record.cleanupCursor() != 0
        || record.keyId() != journal.keyIdAt(descriptor)
        || record.ownerObjectId() != journal.ownerAt(descriptor)
        || record.schemaId() != journal.schemaIdAt(descriptor)
        || record.descriptorHash() != journal.hashAt(descriptor)
        || record.descriptorCount() != shape.partCount()) return false;
    for (int part = 0; part < record.descriptorCount(); part++) {
      if (record.descriptorAt(part) != shape.descriptorAt(part)) return false;
    }
    return true;
  }

  private boolean matchesBuilding(IndexedTupleIndexLifecycleBatch batch, int index) {
    TupleShape shape = batch.shapeAt(index);
    if (record.state() != TupleIndexRootRecordCodec.STATE_BUILDING
        || record.rootPageId() <= 0 || record.cleanupCursor() != 0
        || record.privateOwner() != batch.privateOwnerAt(index)
        || record.keyId() != batch.keyIdAt(index)
        || record.ownerObjectId() != batch.ownerAt(index)
        || record.schemaId() != batch.schemaIdAt(index)
        || record.descriptorHash() != shape.descriptorHash()
        || record.descriptorCount() != shape.partCount()) return false;
    for (int part = 0; part < record.descriptorCount(); part++) {
      if (record.descriptorAt(part) != shape.descriptorAt(part)) return false;
    }
    return true;
  }

  private static boolean pressure(StatusCode status) {
    return status == StatusCode.RESOURCE_EXHAUSTED || status == StatusCode.IO_FAILURE;
  }
}
