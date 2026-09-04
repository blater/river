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
  private int freeEnd;
  private int highKeyOffset;
  private int highKeyLength;
  private TupleBTreePageValidationProof validationProof;
  private long validationVersion;

  void set(
      int pageType,
      int count,
      int pagePointer,
      int pageLeftSibling,
      int arity,
      long hash,
      long schemaId,
      int pageFreeEnd,
      int highOffset,
      int highLength) {
    invalidateValidation();
    type = pageType;
    entryCount = count;
    pointer = pagePointer;
    leftSibling = pageLeftSibling;
    keyArity = arity;
    descriptorHash = hash;
    keySchemaId = schemaId;
    freeEnd = pageFreeEnd;
    highKeyOffset = highOffset;
    highKeyLength = highLength;
  }

  void bindValidation(TupleBTreePageValidationProof proof) {
    validationProof = proof;
    validationVersion = proof == null ? 0 : proof.version();
  }

  void invalidateValidation() {
    validationProof = null;
    validationVersion = 0;
  }

  boolean validates(java.nio.ByteBuffer page, int start, int expectedType) {
    return validationProof != null && validationProof.version() == validationVersion
        && validationProof.matches(
            page, start, keySchemaId, descriptorHash, expectedType);
  }

  public void reset() {
    set(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
  }

  public int type() { return type; }
  public int entryCount() { return entryCount; }
  public int firstChildPageId() { return type == TupleBTreePageCodec.TYPE_INTERNAL ? pointer : 0; }
  public int leftSiblingPageId() { return type == TupleBTreePageCodec.TYPE_LEAF ? leftSibling : 0; }
  public int rightSiblingPageId() { return type == TupleBTreePageCodec.TYPE_LEAF ? pointer : 0; }
  public int keyArity() { return keyArity; }
  public long descriptorHash() { return descriptorHash; }
  public long keySchemaId() { return keySchemaId; }
  public int freeEnd() { return freeEnd; }
  public int highKeyOffset() { return highKeyOffset; }
  public int highKeyLength() { return highKeyLength; }
}
