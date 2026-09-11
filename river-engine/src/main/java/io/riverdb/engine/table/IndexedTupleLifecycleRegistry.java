package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleIndexRootRecord;
import io.riverdb.format.btree.TupleIndexRootRecordCodec;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Reads and stages tuple-root registry records for semantic lifecycle batches. */
final class IndexedTupleLifecycleRegistry {
  private final IndexedTableStore store;
  private final IndexedTableKernel kernel;
  private final IndexedRelationalScalarWriter writer;
  private final IndexedMutationTarget target = new IndexedMutationTarget();
  private final HeapRowResult row = new HeapRowResult();
  private final TupleIndexRootRecord record = new TupleIndexRootRecord();
  private final ByteBuffer bytes = ByteBuffer.allocate(TupleIndexRootRecordCodec.BYTES);
  private final int[] descriptors =
      new int[io.riverdb.format.btree.TupleKeyCodec.MAX_INDEX_KEY_PARTS];
  private final CRC32C checksum = new CRC32C();
  private long previousRowId;

  IndexedTupleLifecycleRegistry(
      IndexedTableStore table, IndexedTableKernel tableKernel, IndexedPageSet pages) {
    store = table;
    kernel = tableKernel;
    writer = new IndexedRelationalScalarWriter(tableKernel, pages);
  }

  StatusCode loadAbsent(IndexedTupleIndexLifecycleBatch batch, int index) {
    long keyId = batch.keyIdAt(index);
    StatusCode status = store.fetchByKey(CatalogKeyspace.INDEX_ROOT_SPACE, keyId, row);
    if (status.isOk()) return StatusCode.CORRUPTION;
    if (status != StatusCode.CONFLICT) return pressure(status) ? status : StatusCode.CORRUPTION;
    status = kernel.prepareInsert(
        store.lastCommitSequence, CatalogKeyspace.INDEX_ROOT_SPACE, keyId, target);
    if (!status.isOk()) return status;
    previousRowId = target.rowId();
    return previousRowId == 0 ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  StatusCode loadBuilding(IndexedTupleIndexLifecycleBatch batch, int index) {
    return loadExisting(batch, index, IndexedTupleLifecycleRecordMatch.BUILDING);
  }

  StatusCode loadDroppable(IndexedTupleIndexLifecycleBatch batch, int index) {
    return loadExisting(batch, index, IndexedTupleLifecycleRecordMatch.DROPPABLE);
  }

  StatusCode loadDropping(IndexedTupleIndexLifecycleBatch batch, int index) {
    return loadExisting(batch, index, IndexedTupleLifecycleRecordMatch.DROPPING);
  }

  StatusCode stageBuilding(
      IndexedTupleIndexLifecycleBatch batch, int index, int root) {
    return stage(batch, index, TupleIndexRootRecordCodec.STATE_BUILDING,
        root, batch.privateOwnerAt(index), 1, 0);
  }

  StatusCode stageReady(IndexedTupleIndexLifecycleBatch batch, int index) {
    return next(batch, index, TupleIndexRootRecordCodec.STATE_READY,
        record.rootPageId(), 0, 0);
  }

  StatusCode stageBuildingProgress(IndexedTupleIndexLifecycleBatch batch, int index) {
    return next(batch, index, TupleIndexRootRecordCodec.STATE_BUILDING,
        record.rootPageId(), batch.privateOwnerAt(index), 0);
  }

  StatusCode stageDropping(IndexedTupleIndexLifecycleBatch batch, int index) {
    return next(batch, index, TupleIndexRootRecordCodec.STATE_DROPPING,
        0, batch.privateOwnerAt(index), 4);
  }

  StatusCode stageReclaim(
      IndexedTupleIndexLifecycleBatch batch, int index, int cleanupCursor) {
    return next(batch, index, TupleIndexRootRecordCodec.STATE_DROPPING,
        0, batch.privateOwnerAt(index), cleanupCursor);
  }

  StatusCode stageAbsent(IndexedTupleIndexLifecycleBatch batch, int index) {
    return next(batch, index, TupleIndexRootRecordCodec.STATE_ABSENT, 0, 0, 0);
  }

  int rootPageId() { return record.rootPageId(); }
  int state() { return record.state(); }
  int cleanupCursor() { return record.cleanupCursor(); }
  long generation() { return record.generation(); }
  long privateOwner() { return record.privateOwner(); }

  private StatusCode loadExisting(
      IndexedTupleIndexLifecycleBatch batch, int index, int expected) {
    long keyId = batch.keyIdAt(index);
    StatusCode status = store.fetchByKey(CatalogKeyspace.INDEX_ROOT_SPACE, keyId, row);
    if (!status.isOk()) return pressure(status) ? status : StatusCode.CORRUPTION;
    bytes.clear();
    status = row.copyTo(bytes);
    bytes.flip();
    if (status.isOk()) status = TupleIndexRootRecordCodec.decode(bytes, 0, record, checksum);
    if (status.isOk()) status = kernel.prepareMutation(
        store.lastCommitSequence, CatalogKeyspace.INDEX_ROOT_SPACE, keyId, target);
    if (!status.isOk()) return pressure(status) ? status : StatusCode.CORRUPTION;
    previousRowId = target.rowId();
    return IndexedTupleLifecycleRecordMatch.matches(record, batch, index, expected)
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private StatusCode next(
      IndexedTupleIndexLifecycleBatch batch, int index,
      int state, int root, long privateOwner, int cleanupCursor) {
    return record.generation() == Long.MAX_VALUE
        ? StatusCode.RESOURCE_EXHAUSTED
        : stage(batch, index, state, root, privateOwner,
            record.generation() + 1, cleanupCursor);
  }

  private StatusCode stage(
      IndexedTupleIndexLifecycleBatch batch, int index,
      int state, int root, long privateOwner, long generation, int cleanupCursor) {
    TupleShape shape = batch.shapeAt(index);
    int count = shape.partCount();
    StatusCode status = shape.copyDescriptors(descriptors, 0);
    bytes.clear();
    if (status.isOk()) status = TupleIndexRootRecordCodec.encode(
        bytes, 0, state, root, batch.keyIdAt(index), batch.ownerAt(index),
        batch.schemaIdAt(index), shape.descriptorHash(), privateOwner, generation,
        cleanupCursor, descriptors, 0, count, checksum);
    bytes.position(0).limit(TupleIndexRootRecordCodec.BYTES);
    return status.isOk() ? writer.stage(
        CatalogKeyspace.INDEX_ROOT_SPACE, batch.keyIdAt(index),
        previousRowId, bytes, false) : status;
  }

  private static boolean pressure(StatusCode status) {
    return status == StatusCode.RESOURCE_EXHAUSTED || status == StatusCode.IO_FAILURE;
  }
}
