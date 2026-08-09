package io.riverdb.journal.api.durability;

import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.journal.api.NodeIncarnation;

/**
 * Caller-owned request naming an exact inclusive logical prefix and absolute monotonic deadline.
 * A deadline of zero explicitly means no timeout.
 */
public final class DurabilityWaitRequest {
  private DatabaseIncarnation databaseIncarnation = DatabaseIncarnation.NONE;
  private NodeIncarnation nodeIncarnation = NodeIncarnation.NONE;
  private long journalGeneration;
  private long requiredSequence;
  private DurabilityRequirement requirement = DurabilityRequirement.LOCAL_DURABLE;
  private long deadlineNanos;

  public DurabilityWaitRequest set(
      DatabaseIncarnation database,
      NodeIncarnation node,
      long generation,
      long sequence,
      DurabilityRequirement requestedDurability,
      long absoluteDeadlineNanos) {
    databaseIncarnation = database;
    nodeIncarnation = node;
    journalGeneration = generation;
    requiredSequence = sequence;
    requirement = requestedDurability;
    deadlineNanos = absoluteDeadlineNanos;
    return this;
  }

  public DatabaseIncarnation databaseIncarnation() {
    return databaseIncarnation;
  }

  public NodeIncarnation nodeIncarnation() {
    return nodeIncarnation;
  }

  public long journalGeneration() {
    return journalGeneration;
  }

  public long requiredSequence() {
    return requiredSequence;
  }

  public DurabilityRequirement requirement() {
    return requirement;
  }

  public long deadlineNanos() {
    return deadlineNanos;
  }
}
