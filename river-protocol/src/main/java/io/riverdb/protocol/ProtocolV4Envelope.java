package io.riverdb.protocol;

/** Caller-owned decoded protocol v4 envelope metadata. */
public final class ProtocolV4Envelope {
  private int kind;
  private int totalBytes;
  private int elementCount;
  private int prefixBytes;
  private long firstMask;
  private long secondMask;
  private long thirdMask;
  private long fourthMask;

  void set(
      int envelopeKind,
      int bytes,
      int elements,
      int prefix,
      long first,
      long second,
      long third,
      long fourth) {
    kind = envelopeKind;
    totalBytes = bytes;
    elementCount = elements;
    prefixBytes = prefix;
    firstMask = first;
    secondMask = second;
    thirdMask = third;
    fourthMask = fourth;
  }

  public void reset() {
    set(0, 0, 0, 0, 0, 0, 0, 0);
  }

  public int kind() { return kind; }
  public int totalBytes() { return totalBytes; }
  public int elementCount() { return elementCount; }
  public int prefixBytes() { return prefixBytes; }
  public long maskWord(int word) {
    return switch (word) {
      case 0 -> firstMask;
      case 1 -> secondMask;
      case 2 -> thirdMask;
      case 3 -> fourthMask;
      default -> 0;
    };
  }
}
