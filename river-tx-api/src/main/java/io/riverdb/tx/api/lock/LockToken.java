package io.riverdb.tx.api.lock;

import io.riverdb.base.error.StatusCode;

/** Caller-owned authenticated proof of one acquired logical lock. */
public final class LockToken {
  private Object providerAuthority;
  private long providerGeneration;
  private long capabilityToken;
  private long holdingGeneration;
  private long transactionId;
  private long transactionGeneration;
  private long referenceGeneration;
  private long slot = -1;
  private boolean active;

  public StatusCode reset() {
    if (active) {
      return StatusCode.CONFLICT;
    }
    providerAuthority = null;
    providerGeneration = 0;
    capabilityToken = 0;
    holdingGeneration = 0;
    transactionId = 0;
    transactionGeneration = 0;
    referenceGeneration = 0;
    slot = -1;
    return StatusCode.OK;
  }

  /** Authenticated provider population hook; owner secrets are never exposed by accessors. */
  public StatusCode claim(
      Object authority,
      long generation,
      long token,
      long holdingGen,
      long id,
      long transactionGen,
      long referenceGen,
      long assignedSlot) {
    if (active || authority == null) {
      return StatusCode.CONFLICT;
    }
    providerAuthority = authority;
    providerGeneration = generation;
    capabilityToken = token;
    holdingGeneration = holdingGen;
    transactionId = id;
    transactionGeneration = transactionGen;
    referenceGeneration = referenceGen;
    slot = assignedSlot;
    active = true;
    return StatusCode.OK;
  }

  /** Authenticated provider lifecycle hook. */
  public StatusCode complete(Object authority) {
    if (!active || !isOwnedBy(authority)) {
      return StatusCode.CONFLICT;
    }
    active = false;
    return StatusCode.OK;
  }

  public boolean isOwnedBy(Object authority) {
    return providerAuthority != null && providerAuthority == authority;
  }

  public long providerGeneration() {
    return providerGeneration;
  }

  public long capabilityToken() {
    return capabilityToken;
  }

  public long holdingGeneration() { return holdingGeneration; }

  public long transactionId() {
    return transactionId;
  }

  public long transactionGeneration() { return transactionGeneration; }
  public long referenceGeneration() { return referenceGeneration; }

  public long slot() {
    return slot;
  }

  public boolean isActive() {
    return active;
  }
}
