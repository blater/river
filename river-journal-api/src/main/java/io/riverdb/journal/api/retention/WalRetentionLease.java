package io.riverdb.journal.api.retention;

import io.riverdb.base.id.JournalPosition;
import io.riverdb.journal.api.NodeIncarnation;

/** Caller-owned cancellable lease handle with a provider-authenticated token. */
public final class WalRetentionLease {
  private long ownerHigh;
  private long ownerLow;
  private long providerToken;
  private NodeIncarnation nodeIncarnation = NodeIncarnation.NONE;
  private long leaseId;
  private RetentionOwnerKind ownerKind = RetentionOwnerKind.RECOVERY;
  private JournalPosition minimumRequired = JournalPosition.NONE;
  private long expiresAtNanos;
  private boolean active;

  public boolean isOwnedBy(long providerHigh, long providerLow) {
    return ownerHigh == providerHigh && ownerLow == providerLow;
  }

  public io.riverdb.base.error.StatusCode reset() {
    if (active) {
      return io.riverdb.base.error.StatusCode.CONFLICT;
    }
    ownerHigh = 0;
    ownerLow = 0;
    providerToken = 0;
    nodeIncarnation = NodeIncarnation.NONE;
    leaseId = 0;
    ownerKind = RetentionOwnerKind.RECOVERY;
    minimumRequired = JournalPosition.NONE;
    expiresAtNanos = 0;
    active = false;
    return io.riverdb.base.error.StatusCode.OK;
  }

  /** Provider-only population hook. */
  public io.riverdb.base.error.StatusCode claim(
      long providerHigh,
      long providerLow,
      long token,
      NodeIncarnation node,
      long id,
      RetentionOwnerKind owner,
      JournalPosition minimum,
      long expiry) {
    if (active || (providerHigh == 0 && providerLow == 0)) {
      return io.riverdb.base.error.StatusCode.CONFLICT;
    }
    ownerHigh = providerHigh;
    ownerLow = providerLow;
    providerToken = token;
    nodeIncarnation = node;
    leaseId = id;
    ownerKind = owner;
    minimumRequired = minimum;
    expiresAtNanos = expiry;
    active = true;
    return io.riverdb.base.error.StatusCode.OK;
  }

  /** Provider-only lifecycle hook. */
  public io.riverdb.base.error.StatusCode complete(long providerHigh, long providerLow) {
    if (!active || !isOwnedBy(providerHigh, providerLow)) {
      return io.riverdb.base.error.StatusCode.CONFLICT;
    }
    active = false;
    return io.riverdb.base.error.StatusCode.OK;
  }

  /** Authenticated in-place renewal of an already active provider capability. */
  public io.riverdb.base.error.StatusCode renew(
      long providerHigh,
      long providerLow,
      JournalPosition minimum,
      long expiry) {
    if (!active || !isOwnedBy(providerHigh, providerLow)) {
      return io.riverdb.base.error.StatusCode.CONFLICT;
    }
    minimumRequired = minimum;
    expiresAtNanos = expiry;
    return io.riverdb.base.error.StatusCode.OK;
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
