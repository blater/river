package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleIndexRootRecord;
import io.riverdb.format.btree.TupleIndexRootRecordCodec;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Validates one expected tuple-root registry CAS operand. */
final class IndexedTupleRootRegistryReader {
  private final IndexedTableKernel kernel;
  private final IndexedRelationalScalarLookup lookup;
  private final HeapRowResult row = new HeapRowResult();
  private final IndexedRowPin pin = new IndexedRowPin();
  private final TupleIndexRootRecord record = new TupleIndexRootRecord();
  private final ByteBuffer bytes = ByteBuffer.allocate(TupleIndexRootRecordCodec.BYTES);
  private final CRC32C checksum = new CRC32C();
  private long rowId;

  IndexedTupleRootRegistryReader(IndexedTableKernel table, IndexedPageSet pages) {
    kernel = table;
    lookup = new IndexedRelationalScalarLookup(table, pages);
  }

  StatusCode load(IndexedRelationalMutationBuffer source, int operation) {
    int descriptor = source.suboperationDescriptorAt(operation);
    StatusCode status = lookup.find(CatalogKeyspace.INDEX_ROOT_SPACE,
        source.keyIdAt(descriptor));
    if (status == StatusCode.RESOURCE_EXHAUSTED || status == StatusCode.IO_FAILURE) {
      return status;
    }
    if (source.expectedRegistryStateAt(operation) == 0
        && status == StatusCode.CONFLICT) {
      rowId = 0;
      return source.expectedGenerationAt(operation) == 0
          ? StatusCode.OK : StatusCode.CORRUPTION;
    }
    if (!status.isOk()) return StatusCode.CORRUPTION;
    rowId = lookup.rowId();
    status = kernel.pinRow(rowId, row, pin);
    bytes.clear();
    if (status.isOk()) status = row.copyTo(bytes);
    if (pin.attached()) {
      StatusCode releaseStatus = kernel.releaseRow(pin);
      if (status.isOk()) status = releaseStatus;
    }
    bytes.flip();
    if (status.isOk()) status = TupleIndexRootRecordCodec.decode(bytes, 0, record, checksum);
    if (!status.isOk()) return status;
    return matches(source, operation, descriptor) ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private boolean matches(IndexedRelationalMutationBuffer source, int operation, int descriptor) {
    return record.state() == source.expectedRegistryStateAt(operation)
        && record.rootPageId() == source.expectedTupleRootAt(operation)
        && record.keyId() == source.keyIdAt(descriptor)
        && record.ownerObjectId() == source.descriptorOwnerObjectIdAt(descriptor)
        && record.schemaId() == source.schemaIdAt(descriptor)
        && record.descriptorHash() == source.descriptorHashAt(descriptor)
        && sameDescriptors(source, descriptor)
        && record.privateOwner() == source.expectedPrivateOwnerAt(operation)
        && record.generation() == source.expectedGenerationAt(operation)
        && record.cleanupCursor() == source.expectedCleanupCursorAt(operation);
  }

  private boolean sameDescriptors(IndexedRelationalMutationBuffer source, int descriptor) {
    int count = source.descriptorPartCountAt(descriptor);
    if (record.descriptorCount() != count) return false;
    for (int index = 0; index < count; index++) {
      if (record.descriptorAt(index) != source.descriptorPartAt(descriptor, index)) return false;
    }
    return true;
  }

  long rowId() { return rowId; }
}
