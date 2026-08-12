package io.riverdb.journal.api.retention;

import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.JournalPosition;
import io.riverdb.journal.api.NodeIncarnation;

/** Caller-owned bounded request to acquire or renew a semantic WAL pin. */
public final class WalRetentionLeaseRequest {
  private DatabaseIncarnation databaseIncarnation = DatabaseIncarnation.NONE;
  private NodeIncarnation nodeIncarnation = NodeIncarnation.NONE;
  private long leaseId;
  private RetentionOwnerKind ownerKind = RetentionOwnerKind.RECOVERY;
  private JournalPosition minimumRequired = JournalPosition.NONE;
  private long nowNanos;
  private long expiresAtNanos;

  public WalRetentionLeaseRequest set(
      DatabaseIncarnation database,
      NodeIncarnation node,
      long id,
      RetentionOwnerKind owner,
      JournalPosition minimum,
      long now,
      long expiresAt) {
    databaseIncarnation = database;
    nodeIncarnation = node;
    leaseId = id;
    ownerKind = owner;
    minimumRequired = minimum;
    nowNanos = now;
    expiresAtNanos = expiresAt;
    return this;
  }

  public DatabaseIncarnation databaseIncarnation() {
    return databaseIncarnation;
  }

  public NodeIncarnation nodeIncarnation() {
    return nodeIncarnation;
  }

  public long leaseId() {
    return leaseId;
  }

  public RetentionOwnerKind ownerKind() {
    return ownerKind;
  }

  public JournalPosition minimumRequired() {
    return minimumRequired;
  }

  public long nowNanos() {
    return nowNanos;
  }

  public long expiresAtNanos() {
    return expiresAtNanos;
  }
}
