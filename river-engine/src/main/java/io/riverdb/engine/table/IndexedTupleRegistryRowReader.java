package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleIndexRootRecord;
import io.riverdb.format.btree.TupleIndexRootRecordCodec;
import io.riverdb.storage.heap.HeapPage;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Decodes one current registry head into a validated descriptor shape. */
final class IndexedTupleRegistryRowReader {
  private final IndexedPageSet pages;
  private final IndexedVersionState versions;
  private final IndexedRowLocation location = new IndexedRowLocation();
  private final IndexedVersionRecord version = new IndexedVersionRecord();
  private final HeapRowResult row = new HeapRowResult();
  private final TupleIndexRootRecord record = new TupleIndexRootRecord();
  private final TupleShape.Result shape = new TupleShape.Result();
  private final int[] descriptors =
      new int[io.riverdb.format.btree.TupleKeyCodec.MAX_INDEX_KEY_PARTS];
  private final ByteBuffer bytes = ByteBuffer.allocate(TupleIndexRootRecordCodec.BYTES);
  private final CRC32C checksum = new CRC32C();

  IndexedTupleRegistryRowReader(IndexedPageSet pageSet, IndexedVersionState versionState) {
    pages = pageSet;
    versions = versionState;
  }

  StatusCode read(long rowId, long rowCount, long keyId) {
    StatusCode status = versions.lookup(rowId, rowCount, version);
    if (!status.isOk()) return status;
    if (version.deleted()) return StatusCode.CORRUPTION;
    status = versions.rows().locate(rowId, location);
    if (!status.isOk()) return status;
    int pageId = location.pageId();
    status = pages.pinCurrentPage(pageId);
    if (!status.isOk()) return status;
    try {
      ByteBuffer heap = pages.currentPayload(pageId);
      if (heap == null) return pages.lastStatus();
      status = HeapPage.fetch(heap, location.slot(), row);
      if (!status.isOk() || row.length() != TupleIndexRootRecordCodec.BYTES) {
        return status.isOk() ? StatusCode.CORRUPTION : status;
      }
      bytes.clear();
      status = row.copyTo(bytes);
    } finally {
      pages.unpinCurrentPage(pageId);
    }
    bytes.flip();
    if (status.isOk()) status = TupleIndexRootRecordCodec.decode(bytes, 0, record, checksum);
    if (!status.isOk()) return status;
    if (record.keyId() != keyId) {
      return StatusCode.CORRUPTION;
    }
    status = record.copyDescriptors(descriptors, 0);
    if (status.isOk()) status = TupleShape.create(
        descriptors, 0, record.descriptorCount(), shape);
    return status.isOk() && shape.value().descriptorHash() == record.descriptorHash()
        ? StatusCode.OK : status.isOk() ? StatusCode.CORRUPTION : status;
  }

  TupleIndexRootRecord record() { return record; }
  TupleShape shape() { return shape.value(); }
}
