package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.wal.WalCommitGroupCodec;
import io.riverdb.platform.file.DurableFile;

/**
 * Caller-owned carrier whose coverage is frozen from capture through release or fenced close.
 * The caller must retain the token separately across reuse; an old alias is not an old identity.
 * Presence of coverage does not imply successful local or configured durability.
 */
public final class LocalWalForceTarget {
  private LocalWal owner;
  private DurableFile file;
  private WalGeneration generation;
  private long token;
  private long startOffset;
  private long endOffset;
  private long commitSequence;
  private long recordCount;
  private int previousDigest;
  private int finalDigest;
  private long forceElapsedNanos;
  private StatusCode forceStatus;
  private boolean locallyForced;
  private boolean durabilityComplete;

  public StatusCode reset() {
    if (owner != null) return StatusCode.CONFLICT;
    generation = null;
    file = null;
    token = startOffset = endOffset = commitSequence = recordCount = 0;
    previousDigest = finalDigest = 0;
    forceElapsedNanos = 0;
    forceStatus = null;
    locallyForced = durabilityComplete = false;
    return StatusCode.OK;
  }

  public WalGeneration generation() { return generation; }
  public long token() { return token; }
  public long startOffset() { return startOffset; }
  public long endOffset() { return endOffset; }
  public long recordCount() { return recordCount; }
  public long commitSequence() { return commitSequence; }
  public int finalDigest() { return finalDigest; }
  public long forceElapsedNanos() { return forceElapsedNanos; }
  public boolean locallyForced() { return locallyForced; }
  public boolean durabilityComplete() { return durabilityComplete; }

  /** Exact append coverage; a larger forced prefix does not match the caller's retained batch. */
  public boolean matchesAppend(LocalWalGroupAppendResult append) {
    return startOffset == append.startOffset()
        && append.endOffset() <= Long.MAX_VALUE - WalCommitGroupCodec.FOOTER_BYTES
        && append.endOffset() + WalCommitGroupCodec.FOOTER_BYTES == endOffset
        && recordCount == append.recordCount();
  }

  boolean retained() { return owner != null; }

  boolean ownedBy(LocalWal wal, long expectedToken) {
    return owner == wal && token != 0 && token == expectedToken
        && generation.value() == wal.walGeneration().value();
  }

  void capture(
      LocalWal wal, DurableFile capturedFile, long identity,
      long start, long end, long records, long csn,
      int predecessorDigest, int terminalDigest) {
    owner = wal;
    file = capturedFile;
    generation = wal.walGeneration();
    token = identity;
    startOffset = start;
    endOffset = end;
    recordCount = records;
    commitSequence = csn;
    previousDigest = predecessorDigest;
    finalDigest = terminalDigest;
    forceElapsedNanos = 0;
    forceStatus = null;
    locallyForced = durabilityComplete = false;
  }

  void completeLocalForce() { locallyForced = true; }
  void completeDurability() { durabilityComplete = true; }
  void release() { owner = null; file = null; }

  DurableFile file() { return file; }
  int previousDigest() { return previousDigest; }
  StatusCode forceStatus() { return forceStatus; }

  void completeForceIo(StatusCode status, long elapsedNanos) {
    forceStatus = status == null ? StatusCode.INVARIANT_BROKEN : status;
    forceElapsedNanos = Math.max(0, elapsedNanos);
  }
}
