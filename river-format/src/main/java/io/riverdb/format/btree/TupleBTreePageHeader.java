package io.riverdb.format.btree;

/** Caller-owned decoded variable-key B-tree page header. */
public final class TupleBTreePageHeader {
  private int type;
  private int entryCount;
  private int pointer;
  private int leftSibling;
  private int keyArity;
  private long descriptorHash;
  private long keySchemaId;
  private int highKeyOffset;
  private int highKeyLength;

  void set(
      int pageType,
      int count,
      int pagePointer,
      int pageLeftSibling,
      int arity,
      long hash,
      long schemaId,
      int highOffset,
      int highLength) {
    type = pageType;
    entryCount = count;
    pointer = pagePointer;
    leftSibling = pageLeftSibling;
    keyArity = arity;
    descriptorHash = hash;
    keySchemaId = schemaId;
    highKeyOffset = highOffset;
    highKeyLength = highLength;
  }

  public void reset() {
    set(0, 0, 0, 0, 0, 0, 0, 0, 0);
  }

  public int type() { return type; }
  public int entryCount() { return entryCount; }
  public int firstChildPageId() { return type == TupleBTreePageCodec.TYPE_INTERNAL ? pointer : 0; }
  public int leftSiblingPageId() { return type == TupleBTreePageCodec.TYPE_LEAF ? leftSibling : 0; }
  public int rightSiblingPageId() { return type == TupleBTreePageCodec.TYPE_LEAF ? pointer : 0; }
  public int keyArity() { return keyArity; }
  public long descriptorHash() { return descriptorHash; }
  public long keySchemaId() { return keySchemaId; }
  public int highKeyOffset() { return highKeyOffset; }
  public int highKeyLength() { return highKeyLength; }
}
