package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleIndexRootRecordCodec;
import io.riverdb.format.catalog.CatalogKeyspace;
import java.nio.ByteBuffer;

/** Stages one resulting tuple-root registry record and scalar head. */
final class IndexedTupleRootRegistryWriter {
  private final IndexedRelationalScalarWriter scalar;
  private final int[] descriptors =
      new int[io.riverdb.format.btree.TupleKeyCodec.MAX_INDEX_KEY_PARTS];
  private final ByteBuffer bytes = ByteBuffer.allocate(TupleIndexRootRecordCodec.BYTES);

  IndexedTupleRootRegistryWriter(IndexedTableKernel table, IndexedPageSet pages) {
    scalar = new IndexedRelationalScalarWriter(table, pages);
  }

  StatusCode stage(
      IndexedRelationalMutationBuffer source, int operation, long previousRegistryRowId,
      long priorMembershipSequence, long memberSequence) {
    int descriptor = source.suboperationDescriptorAt(operation);
    int descriptorCount = source.descriptorPartCountAt(descriptor);
    for (int index = 0; index < descriptorCount; index++) {
      descriptors[index] = source.descriptorPartAt(descriptor, index);
    }
    bytes.clear();
    long membershipSequence = resultingMembershipSequence(
        source, operation, priorMembershipSequence, memberSequence);
    StatusCode status = TupleIndexRootRecordCodec.encode(
        bytes, 0, source.resultingRegistryStateAt(operation),
        source.resultingTupleRootAt(operation), source.keyIdAt(descriptor),
        source.descriptorOwnerObjectIdAt(descriptor), source.schemaIdAt(descriptor),
        source.descriptorHashAt(descriptor), source.resultingPrivateOwnerAt(operation),
        source.resultingGenerationAt(operation), membershipSequence,
        source.resultingCleanupCursorAt(operation), descriptors, 0, descriptorCount);
    bytes.position(0);
    bytes.limit(TupleIndexRootRecordCodec.BYTES);
    return status.isOk() ? scalar.stage(
        CatalogKeyspace.INDEX_ROOT_SPACE, source.keyIdAt(descriptor),
        previousRegistryRowId, bytes, false) : status;
  }

  long rowId() { return scalar.rowId(); }

  static long resultingMembershipSequence(
      IndexedRelationalMutationBuffer source, int operation,
      long prior, long memberSequence) {
    if (source.expectedRegistryStateAt(operation)
        != source.resultingRegistryStateAt(operation)) return memberSequence;
    int first = source.suboperationFirstMutationAt(operation);
    int end = first + source.suboperationMutationCountAt(operation);
    for (int mutation = first; mutation < end; mutation++) {
      int kind = source.operationAt(mutation);
      if (kind == IndexedRelationalMutationBuffer.TUPLE_INSERT
          || kind == IndexedRelationalMutationBuffer.TUPLE_DELETE) return memberSequence;
    }
    return prior;
  }
}
