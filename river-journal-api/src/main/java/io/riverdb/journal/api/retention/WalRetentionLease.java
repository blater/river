package io.riverdb.journal.api.retention;

import io.riverdb.base.id.JournalPosition;
import io.riverdb.journal.api.NodeIncarnation;

/** Caller-owned cancellable lease handle with a provider-authenticated token. */
public final class WalRetentionLease {
  private long providerToken;
  private NodeIncarnation nodeIncarnation = NodeIncarnation.NONE;
  private long leaseId;
  private RetentionOwnerKind ownerKind = RetentionOwnerKind.RECOVERY;
  private JournalPosition minimumRequired = JournalPosition.NONE;
  private long expiresAtNanos;
  private boolean active;

  public WalRetentionLease reset() {
    providerToken = 0;
    nodeIncarnation = NodeIncarnation.NONE;
    leaseId = 0;
    ownerKind = RetentionOwnerKind.RECOVERY;
    minimumRequired = JournalPosition.NONE;
    expiresAtNanos = 0;
    active = false;
    return this;
  }

  /** Provider-only population hook. */
  public WalRetentionLease assign(
      long token,
      NodeIncarnation node,
      long id,
      RetentionOwnerKind owner,
      JournalPosition minimum,
      long expiry) {
    providerToken = token;
    nodeIncarnation = node;
    leaseId = id;
    ownerKind = owner;
    minimumRequired = minimum;
    expiresAtNanos = expiry;
    active = true;
    return this;
  }

  /** Provider-only lifecycle hook. */
  public void complete() {
    active = false;
  }

  public long providerToken() {
    return providerToken;
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

  public long expiresAtNanos() {
    return expiresAtNanos;
  }

  public boolean isActive() {
    return active;
  }
}
