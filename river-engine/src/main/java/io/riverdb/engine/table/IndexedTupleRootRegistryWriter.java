package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleIndexRootRecordCodec;
import io.riverdb.format.catalog.CatalogKeyspace;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Stages one resulting tuple-root registry record and scalar head. */
final class IndexedTupleRootRegistryWriter {
  private final IndexedRelationalScalarWriter scalar;
  private final int[] descriptors =
      new int[io.riverdb.format.btree.TupleKeyCodec.MAX_INDEX_KEY_PARTS];
  private final ByteBuffer bytes = ByteBuffer.allocate(TupleIndexRootRecordCodec.BYTES);
  private final CRC32C checksum = new CRC32C();

  IndexedTupleRootRegistryWriter(IndexedTableKernel table, IndexedPageSet pages) {
    scalar = new IndexedRelationalScalarWriter(table, pages);
  }

  StatusCode stage(
      IndexedRelationalMutationBuffer source, int operation, long previousRegistryRowId) {
    int descriptor = source.suboperationDescriptorAt(operation);
    int descriptorCount = source.descriptorPartCountAt(descriptor);
    for (int index = 0; index < descriptorCount; index++) {
      descriptors[index] = source.descriptorPartAt(descriptor, index);
    }
    bytes.clear();
    StatusCode status = TupleIndexRootRecordCodec.encode(
        bytes, 0, source.resultingRegistryStateAt(operation),
        source.resultingTupleRootAt(operation), source.keyIdAt(descriptor),
        source.descriptorOwnerObjectIdAt(descriptor), source.schemaIdAt(descriptor),
        source.descriptorHashAt(descriptor), source.resultingPrivateOwnerAt(operation),
        source.resultingGenerationAt(operation), source.resultingCleanupCursorAt(operation),
        descriptors, 0, descriptorCount, checksum);
    bytes.position(0);
    bytes.limit(TupleIndexRootRecordCodec.BYTES);
    return status.isOk() ? scalar.stage(
        CatalogKeyspace.INDEX_ROOT_SPACE, source.keyIdAt(descriptor),
        previousRegistryRowId, bytes, false) : status;
  }

  long rowId() { return scalar.rowId(); }
}
