package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.catalog.CatalogObjectHeadCodec;

/** Rollback-aware schema-publication markers for descriptor table drops. */
final class RelationalDescriptorDropPublications {
  static final int[] MUTATION_ROW_LENGTHS = {CatalogObjectHeadCodec.BYTES, 1};
  private final int[] mutationStarts =
      new int[RelationalPreparedDescriptorEntries.MAXIMUM];
  private int count;

  StatusCode prepare(boolean published) {
    return published && count >= mutationStarts.length
        ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.OK;
  }

  void record(int mutationStart, boolean published) {
    if (!published) return;
    mutationStarts[count++] = mutationStart;
  }

  void rollbackTo(int pendingMutations) {
    while (count > 0 && pendingMutations <= mutationStarts[count - 1]) {
      mutationStarts[--count] = 0;
    }
  }

  boolean active() { return count > 0; }

  void reset() {
    while (count > 0) mutationStarts[--count] = 0;
  }
}
