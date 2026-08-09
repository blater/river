package io.riverdb.journal.api;

import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.journal.api.durability.DurabilityRequirement;

/** Caller-owned reusable input for a bounded journal reservation. */
public final class JournalReserveRequest {
  private DatabaseIncarnation databaseIncarnation = DatabaseIncarnation.NONE;
  private NodeIncarnation nodeIncarnation = NodeIncarnation.NONE;
  private long requestIdHigh;
  private long requestIdLow;
  private long idempotencyKeyHigh;
  private long idempotencyKeyLow;
  private DurabilityRequirement durabilityRequirement = DurabilityRequirement.LOCAL_DURABLE;
  private int payloadBytes;

  public JournalReserveRequest set(
      DatabaseIncarnation database,
      NodeIncarnation node,
      long requestHigh,
      long requestLow,
      long idempotencyHigh,
      long idempotencyLow,
      DurabilityRequirement durability,
      int bytes) {
    databaseIncarnation = database;
    nodeIncarnation = node;
    requestIdHigh = requestHigh;
    requestIdLow = requestLow;
    idempotencyKeyHigh = idempotencyHigh;
    idempotencyKeyLow = idempotencyLow;
    durabilityRequirement = durability;
    payloadBytes = bytes;
    return this;
  }

  public DatabaseIncarnation databaseIncarnation() {
    return databaseIncarnation;
  }

  public NodeIncarnation nodeIncarnation() {
    return nodeIncarnation;
  }

  public long requestIdHigh() {
    return requestIdHigh;
  }

  public long requestIdLow() {
    return requestIdLow;
  }

  public long idempotencyKeyHigh() {
    return idempotencyKeyHigh;
  }

  public long idempotencyKeyLow() {
    return idempotencyKeyLow;
  }

  public DurabilityRequirement durabilityRequirement() {
    return durabilityRequirement;
  }

  public int payloadBytes() {
    return payloadBytes;
  }
}
