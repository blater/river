package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Group-local registry row identities layered over staged scalar mutations. */
final class IndexedTupleRegistryState {
  private final IndexedTupleRootRegistryReader reader;
  private final IndexedTupleRootRegistryWriter writer;
  private final IndexedLongChunks rowIds = new IndexedLongChunks(Integer.MAX_VALUE);
  private final IndexedIntChunks loaded = new IndexedIntChunks(Integer.MAX_VALUE);
  private final IndexedLongChunks membershipSequences =
      new IndexedLongChunks(Integer.MAX_VALUE);
  private int used;

  IndexedTupleRegistryState(IndexedTableKernel kernel, IndexedPageSet pages) {
    reader = new IndexedTupleRootRegistryReader(kernel, pages);
    writer = new IndexedTupleRootRegistryWriter(kernel, pages);
  }

  StatusCode reserve(int descriptors) {
    StatusCode status = rowIds.reserve(descriptors);
    if (status.isOk()) status = loaded.reserve(descriptors);
    return status.isOk() ? membershipSequences.reserve(descriptors) : status;
  }

  StatusCode load(IndexedRelationalMutationBuffer source, int operation) {
    int descriptor = source.suboperationDescriptorAt(operation);
    if (loaded.get(descriptor) != 0) return StatusCode.OK;
    StatusCode status = reader.load(source, operation);
    if (status.isOk()) {
      rowIds.set(descriptor, reader.rowId());
      membershipSequences.set(descriptor, reader.membershipSequence());
      loaded.set(descriptor, 1);
      if (descriptor >= used) used = descriptor + 1;
    }
    return status;
  }

  StatusCode stage(
      IndexedRelationalMutationBuffer source, int operation, long memberSequence) {
    int descriptor = source.suboperationDescriptorAt(operation);
    long prior = membershipSequences.get(descriptor);
    StatusCode status = writer.stage(
        source, operation, rowIds.get(descriptor), prior, memberSequence);
    if (status.isOk()) {
      rowIds.set(descriptor, writer.rowId());
      membershipSequences.set(descriptor,
          IndexedTupleRootRegistryWriter.resultingMembershipSequence(
              source, operation, prior, memberSequence));
    }
    return status;
  }

  void reset() {
    for (int index = 0; index < used; index++) {
      rowIds.set(index, 0);
      loaded.set(index, 0);
      membershipSequences.set(index, 0);
    }
    used = 0;
  }
}
