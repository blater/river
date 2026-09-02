package io.riverdb.engine.checkpoint;

/**
 * Rewindable, ascending source of committed logical-row allocation floors.
 *
 * <p>The owner must not mutate the source between {@link #rewind()} and exhaustion. Object IDs
 * and next-exclusive floors are positive. {@link #nextObjectId()} returns {@code -1} at end; after
 * each successful call {@link #nextExclusive()} describes that object.
 */
public interface CheckpointLogicalRowIdSource {
  int floorCount();

  void rewind();

  long nextObjectId();

  long nextExclusive();
}
