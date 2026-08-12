package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Caller-owned position for one ordered snapshot scan. */
public final class IndexedScanCursor {
  private final IndexedScanResult committedLookahead = new IndexedScanResult();
  private IndexedTable owner;
  private IndexedTransactionSession sessionOwner;
  private long visibleCommitSequence;
  private long lowerKey;
  private long upperKey;
  private int leafPageId;
  private int entryIndex;
  private long lastReturnedKey;
  private boolean hasCommittedLookahead;
  private boolean committedExhausted;
  private boolean hasLastReturnedKey;
  private boolean active;

  public StatusCode reset() {
    if (active) {
      return StatusCode.CONFLICT;
    }
    owner = null;
    sessionOwner = null;
    visibleCommitSequence = 0;
    lowerKey = 0;
    upperKey = 0;
    leafPageId = 0;
    entryIndex = 0;
    lastReturnedKey = 0;
    hasCommittedLookahead = false;
    committedExhausted = false;
    hasLastReturnedKey = false;
    committedLookahead.reset();
    return StatusCode.OK;
  }

  StatusCode claim(
      IndexedTable table,
      long visible,
      long lower,
      long upper,
      int firstLeafPageId) {
    if (active) {
      return StatusCode.CONFLICT;
    }
    owner = table;
    visibleCommitSequence = visible;
    lowerKey = lower;
    upperKey = upper;
    leafPageId = firstLeafPageId;
    entryIndex = 0;
    active = true;
    return StatusCode.OK;
  }

  StatusCode attach(IndexedTransactionSession session) {
    if (!active || sessionOwner != null) {
      return StatusCode.CONFLICT;
    }
    sessionOwner = session;
    return StatusCode.OK;
  }

  boolean isSessionOwnedBy(IndexedTransactionSession session) {
    return active && sessionOwner == session;
  }

  boolean isOwnedBy(IndexedTable table) {
    return active && owner == table;
  }

  long visibleCommitSequence() {
    return visibleCommitSequence;
  }

  long lowerKey() {
    return lowerKey;
  }

  long upperKey() {
    return upperKey;
  }

  int leafPageId() {
    return leafPageId;
  }

  int entryIndex() {
    return entryIndex;
  }

  void advanceEntry() {
    entryIndex++;
  }

  void advanceLeaf(int nextLeafPageId) {
    leafPageId = nextLeafPageId;
    entryIndex = 0;
  }

  void complete() {
    active = false;
  }

  IndexedScanResult committedLookahead() {
    return committedLookahead;
  }

  boolean hasCommittedLookahead() {
    return hasCommittedLookahead;
  }

  void setCommittedLookahead(boolean available) {
    hasCommittedLookahead = available;
  }

  boolean committedExhausted() {
    return committedExhausted;
  }

  void setCommittedExhausted() {
    committedExhausted = true;
  }

  boolean afterLastReturned(long key) {
    return !hasLastReturnedKey || key > lastReturnedKey;
  }

  void returned(long key) {
    lastReturnedKey = key;
    hasLastReturnedKey = true;
  }

  public boolean isActive() {
    return active;
  }
}
