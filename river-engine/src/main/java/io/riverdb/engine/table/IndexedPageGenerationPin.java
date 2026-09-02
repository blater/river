package io.riverdb.engine.table;

import java.nio.ByteBuffer;

/** Caller-owned authenticated borrow of one immutable committed page generation. */
final class IndexedPageGenerationPin {
  private int slot = -1;
  private int pageId;
  private long validFromCommitSequence;
  private long pageGeneration;
  private ByteBuffer payload;
  private int payloadKind;
  private long ownerKeyId;

  void set(
      int frameSlot, int id, long validFrom, long generation,
      ByteBuffer bytes, int kind, long owner) {
    slot = frameSlot;
    pageId = id;
    validFromCommitSequence = validFrom;
    pageGeneration = generation;
    payload = bytes;
    payloadKind = kind;
    ownerKeyId = owner;
  }

  void reset() {
    slot = -1;
    pageId = 0;
    validFromCommitSequence = 0;
    pageGeneration = 0;
    payload = null;
    payloadKind = 0;
    ownerKeyId = 0;
  }

  boolean active() { return slot >= 0; }
  int slot() { return slot; }
  int pageId() { return pageId; }
  long validFromCommitSequence() { return validFromCommitSequence; }
  long pageGeneration() { return pageGeneration; }
  ByteBuffer payload() { return payload; }
  int payloadKind() { return payloadKind; }
  long ownerKeyId() { return ownerKeyId; }
}
