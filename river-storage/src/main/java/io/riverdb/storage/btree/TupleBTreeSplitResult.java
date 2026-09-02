package io.riverdb.storage.btree;

import java.nio.ByteBuffer;

/** Caller-owned split result borrowing its separator until the source buffer is reused. */
public final class TupleBTreeSplitResult {
  private ByteBuffer separatorSource;
  private int separatorOffset;
  private int separatorLength;
  private int leftCount;
  private int rightCount;

  void set(ByteBuffer source, int offset, int length, int left, int right) {
    separatorSource = source;
    separatorOffset = offset;
    separatorLength = length;
    leftCount = left;
    rightCount = right;
  }

  public void reset() { set(null, 0, 0, 0, 0); }
  public ByteBuffer separatorSource() { return separatorSource; }
  public int separatorOffset() { return separatorOffset; }
  public int separatorLength() { return separatorLength; }
  public int leftCount() { return leftCount; }
  public int rightCount() { return rightCount; }
}
