package io.riverdb.engine.schema.cache;

import io.riverdb.engine.schema.TableDescriptor;

/** Allocation-free scans and recency maintenance for fixed cache slots. */
final class SchemaCacheSlotScan {
  private final SchemaCacheEntry[] entries;
  private long clock;

  SchemaCacheSlotScan(SchemaCacheEntry[] slots) {
    entries = slots;
  }

  SchemaCacheEntry findRetained(long tableId, long rowLayoutId) {
    SchemaCacheEntry found = null;
    for (SchemaCacheEntry entry : entries) {
      if (entry.occupied && entry.tableId == tableId && entry.rowLayoutId == rowLayoutId
          && (found == null || newer(entry, found))) found = entry;
    }
    return found;
  }

  SchemaCacheEntry findExact(
      long tableId, long schemaId, long rowLayoutId, long generation) {
    for (SchemaCacheEntry entry : entries) {
      if (entry.occupied && entry.tableId == tableId && entry.rowLayoutId == rowLayoutId
          && entry.catalogGeneration == generation
          && (schemaId == 0 || entry.schemaId == schemaId)) return entry;
    }
    return null;
  }

  boolean conflictsSuccessor(TableDescriptor descriptor) {
    for (SchemaCacheEntry entry : entries) {
      if (entry.tableId != descriptor.tableId()) continue;
      if (entry.reserved || entry.occupied
          && entry.catalogGeneration >= descriptor.catalogGeneration()) return true;
    }
    return false;
  }

  boolean hasPending(long tableId) {
    for (SchemaCacheEntry entry : entries) {
      if (entry.reserved && entry.tableId == tableId) return true;
    }
    return false;
  }

  boolean hasExact(TableDescriptor descriptor) {
    for (SchemaCacheEntry entry : entries) {
      if ((entry.occupied || entry.reserved)
          && entry.tableId == descriptor.tableId()
          && entry.schemaId == descriptor.schemaId()
          && entry.rowLayoutId == descriptor.rowLayoutId()
          && entry.catalogGeneration == descriptor.catalogGeneration()) return true;
    }
    return false;
  }

  SchemaCacheEntry freeEntry() {
    for (SchemaCacheEntry entry : entries) if (!entry.occupied && !entry.reserved) return entry;
    return null;
  }

  SchemaCacheEntry oldestUnpinned() {
    SchemaCacheEntry oldest = null;
    for (SchemaCacheEntry entry : entries) {
      if (entry.occupied && entry.pinCount == 0
          && (oldest == null || entry.sequence < oldest.sequence)) oldest = entry;
    }
    return oldest;
  }

  long nextSequence() {
    if (++clock == 0) clock = 1;
    return clock;
  }

  long charge(SchemaCacheEntry entry) {
    return entry.descriptor.byteCharge();
  }

  void clear(SchemaCacheEntry entry) {
    entry.clear();
  }

  private static boolean newer(SchemaCacheEntry candidate, SchemaCacheEntry current) {
    return candidate.catalogGeneration > current.catalogGeneration
        || (candidate.catalogGeneration == current.catalogGeneration
        && candidate.sequence > current.sequence);
  }
}
