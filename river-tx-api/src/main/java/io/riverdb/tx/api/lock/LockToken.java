package io.riverdb.tx.api.lock;

import io.riverdb.base.error.StatusCode;

/** Caller-owned authenticated proof of one acquired logical lock. */
public final class LockToken {
  private long ownerHigh;
  private long ownerLow;
  private long providerGeneration;
  private long capabilityToken;
  private long transactionId;
  private int slot = -1;
  private boolean active;

  public StatusCode reset() {
    if (active) {
      return StatusCode.CONFLICT;
    }
    ownerHigh = 0;
    ownerLow = 0;
    providerGeneration = 0;
    capabilityToken = 0;
    transactionId = 0;
    slot = -1;
    return StatusCode.OK;
  }

  /** Authenticated provider population hook; owner secrets are never exposed by accessors. */
  public StatusCode claim(
      long providerHigh,
      long providerLow,
      long generation,
      long token,
      long id,
      int assignedSlot) {
    if (active || (providerHigh == 0 && providerLow == 0)) {
      return StatusCode.CONFLICT;
    }
    ownerHigh = providerHigh;
    ownerLow = providerLow;
    providerGeneration = generation;
    capabilityToken = token;
    transactionId = id;
    slot = assignedSlot;
    active = true;
    return StatusCode.OK;
  }

  /** Authenticated provider lifecycle hook. */
  public StatusCode complete(long providerHigh, long providerLow) {
    if (!active || !isOwnedBy(providerHigh, providerLow)) {
      return StatusCode.CONFLICT;
    }
    active = false;
    return StatusCode.OK;
  }

  public boolean isOwnedBy(long providerHigh, long providerLow) {
    return ownerHigh == providerHigh && ownerLow == providerLow;
  }

  public long providerGeneration() {
    return providerGeneration;
  }

  public long capabilityToken() {
    return capabilityToken;
  }

  public long transactionId() {
    return transactionId;
  }

  public int slot() {
    return slot;
  }

  public boolean isActive() {
    return active;
  }
}
