package io.riverdb.format.btree;

/** Caller-owned decoded inline-tuple B-tree page header. */
public final class TupleBTreePageHeader {
  private int type;
  private int entryCount;
  private int pointer;
  private int keyArity;
  private int firstDescriptor;
  private int secondDescriptor;
  private int thirdDescriptor;
  private int fourthDescriptor;
  private long keySchemaId;
  private int highKeyOffset;
  private int highKeyLength;

  void set(
      int pageType,
      int count,
      int pagePointer,
      int arity,
      int first,
      int second,
      int third,
      int fourth,
      long schemaId,
      int highOffset,
      int highLength) {
    type = pageType;
    entryCount = count;
    pointer = pagePointer;
    keyArity = arity;
    firstDescriptor = first;
    secondDescriptor = second;
    thirdDescriptor = third;
    fourthDescriptor = fourth;
    keySchemaId = schemaId;
    highKeyOffset = highOffset;
    highKeyLength = highLength;
  }

  public void reset() {
    set(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
  }

  public int type() { return type; }
  public int entryCount() { return entryCount; }
  public int pointer() { return pointer; }
  public int keyArity() { return keyArity; }
  public int descriptor(int index) {
    return switch (index) {
      case 0 -> firstDescriptor;
      case 1 -> secondDescriptor;
      case 2 -> thirdDescriptor;
      case 3 -> fourthDescriptor;
      default -> 0;
    };
  }
  public long keySchemaId() { return keySchemaId; }
  public int highKeyOffset() { return highKeyOffset; }
  public int highKeyLength() { return highKeyLength; }
}
