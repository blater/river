package io.riverdb.format.btree;

import io.riverdb.base.error.StatusCode;

/** Caller-owned decoded lower-store tuple-index root record. */
public final class TupleIndexRootRecord {
  private final int[] descriptors = new int[TupleKeyCodec.MAX_INDEX_KEY_PARTS];
  private int state;
  private int rootPageId;
  private long keyId;
  private long ownerObjectId;
  private long schemaId;
  private long descriptorHash;
  private long privateOwner;
  private long generation;
  private int cleanupCursor;
  private int descriptorCount;

  void set(int valueState, int root, long key, long object,
      long schema, long hash, long owner, long valueGeneration,
      int cursor, int count) {
    state = valueState;
    rootPageId = root;
    keyId = key;
    ownerObjectId = object;
    schemaId = schema;
    descriptorHash = hash;
    privateOwner = owner;
    generation = valueGeneration;
    cleanupCursor = cursor;
    descriptorCount = count;
    for (int index = count; index < descriptors.length; index++) descriptors[index] = 0;
  }

  void setDescriptorAt(int index, int descriptor) { descriptors[index] = descriptor; }

  public void reset() {
    state = 0;
    rootPageId = 0;
    keyId = 0;
    ownerObjectId = 0;
    schemaId = 0;
    descriptorHash = 0;
    privateOwner = 0;
    generation = 0;
    cleanupCursor = 0;
    descriptorCount = 0;
    for (int index = 0; index < descriptors.length; index++) descriptors[index] = 0;
  }
  public int state() { return state; }
  public int rootPageId() { return rootPageId; }
  public long keyId() { return keyId; }
  public long ownerObjectId() { return ownerObjectId; }
  public long schemaId() { return schemaId; }
  public long descriptorHash() { return descriptorHash; }
  public long privateOwner() { return privateOwner; }
  public long generation() { return generation; }
  public int cleanupCursor() { return cleanupCursor; }
  public int descriptorCount() { return descriptorCount; }
  public int descriptorAt(int index) {
    return index >= 0 && index < descriptorCount ? descriptors[index] : 0;
  }
  public StatusCode copyDescriptors(int[] destination, int offset) {
    if (destination == null || offset < 0
        || offset > destination.length - descriptorCount) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    System.arraycopy(descriptors, 0, destination, offset, descriptorCount);
    return StatusCode.OK;
  }
}
