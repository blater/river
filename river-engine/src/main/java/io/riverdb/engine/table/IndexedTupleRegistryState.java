package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Group-local registry row identities layered over staged scalar mutations. */
final class IndexedTupleRegistryState {
  private final IndexedTupleRootRegistryReader reader;
  private final IndexedTupleRootRegistryWriter writer;
  private final IndexedLongChunks rowIds = new IndexedLongChunks(Integer.MAX_VALUE);
  private final IndexedIntChunks loaded = new IndexedIntChunks(Integer.MAX_VALUE);
  private int used;

  IndexedTupleRegistryState(IndexedTableKernel kernel, IndexedPageSet pages) {
    reader = new IndexedTupleRootRegistryReader(kernel, pages);
    writer = new IndexedTupleRootRegistryWriter(kernel, pages);
  }

  StatusCode reserve(int descriptors) {
    StatusCode status = rowIds.reserve(descriptors);
    return status.isOk() ? loaded.reserve(descriptors) : status;
  }

  StatusCode load(IndexedRelationalMutationBuffer source, int operation) {
    int descriptor = source.suboperationDescriptorAt(operation);
    if (loaded.get(descriptor) != 0) return StatusCode.OK;
    StatusCode status = reader.load(source, operation);
    if (status.isOk()) {
      rowIds.set(descriptor, reader.rowId());
      loaded.set(descriptor, 1);
      if (descriptor >= used) used = descriptor + 1;
    }
    return status;
  }

  StatusCode stage(IndexedRelationalMutationBuffer source, int operation) {
    int descriptor = source.suboperationDescriptorAt(operation);
    StatusCode status = writer.stage(source, operation, rowIds.get(descriptor));
    if (status.isOk()) rowIds.set(descriptor, writer.rowId());
    return status;
  }

  void reset() {
    for (int index = 0; index < used; index++) {
      rowIds.set(index, 0);
      loaded.set(index, 0);
    }
    used = 0;
  }
}
