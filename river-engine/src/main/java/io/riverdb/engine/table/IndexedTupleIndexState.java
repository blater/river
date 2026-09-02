package io.riverdb.engine.table;

/** Caller-owned durable tuple-index lifecycle state used by restart cleanup. */
public final class IndexedTupleIndexState {
  private int state;
  private int rootPageId;
  private int cleanupCursor;
  private long keyId;
  private long ownerObjectId;
  private long schemaId;
  private long descriptorHash;
  private long privateOwner;
  private long generation;
  private final int[] descriptors =
      new int[io.riverdb.format.btree.TupleKeyCodec.MAX_INDEX_KEY_PARTS];
  private int descriptorCount;

  public void reset() { set(0, 0, 0, 0, 0, 0, 0, 0, 0, null); }
  public int state() { return state; }
  public int rootPageId() { return rootPageId; }
  public int cleanupCursor() { return cleanupCursor; }
  public long keyId() { return keyId; }
  public long ownerObjectId() { return ownerObjectId; }
  public long schemaId() { return schemaId; }
  public long descriptorHash() { return descriptorHash; }
  public long privateOwner() { return privateOwner; }
  public long generation() { return generation; }
  public int descriptorCount() { return descriptorCount; }
  public int descriptorAt(int index) {
    return index >= 0 && index < descriptorCount ? descriptors[index] : 0;
  }

  void set(
      int valueState, int root, int cursor, long key, long owner,
      long schema, long hash, long buildOwner, long valueGeneration,
      io.riverdb.format.btree.TupleIndexRootRecord record) {
    state = valueState;
    rootPageId = root;
    cleanupCursor = cursor;
    keyId = key;
    ownerObjectId = owner;
    schemaId = schema;
    descriptorHash = hash;
    privateOwner = buildOwner;
    generation = valueGeneration;
    descriptorCount = record == null ? 0 : record.descriptorCount();
    for (int index = 0; index < descriptors.length; index++) {
      descriptors[index] = index < descriptorCount ? record.descriptorAt(index) : 0;
    }
  }
}
