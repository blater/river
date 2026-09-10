package io.riverdb.format.btree;

import java.nio.ByteBuffer;

/**
 * Reusable leading-prefix view over one borrowed, already validated encoded tuple.
 *
 * The caller admits the prefix against the tree shape before opening this view. Tuple field
 * encodings are prefix-free: fixed-width values have fixed widths and VARCHAR values terminate
 * with a zero scalar that cannot encode a valid character. Therefore matching the complete user
 * payload of this shorter tuple is equivalent to matching its leading fields.
 */
public final class TupleKeyPrefix {
  private ByteBuffer source;
  private int offset;
  private int prefixUserLength;
  private int headerBytes;
  private int fullHeaderBytes;

  /**
   * Binds this view to a caller-owned encoded user tuple already admitted against the tree shape.
   * The source must remain unchanged and borrowed for the view's lifetime. Callers own admission.
   */
  public void prepare(
      ByteBuffer source, int offset, int length, int prefixParts, int fullParts) {
    clear();
    int encodedHeaderBytes = TupleKeyCodec.headerBytes(prefixParts);
    this.source = source;
    this.offset = offset;
    this.prefixUserLength = length - encodedHeaderBytes;
    this.headerBytes = encodedHeaderBytes;
    this.fullHeaderBytes = TupleKeyCodec.headerBytes(fullParts);
  }

  /** Compares one already admitted physical key with this prefix. */
  public int comparePhysical(ByteBuffer key, int keyOffset, int keyLength) {
    int keyUserLength = keyLength - TupleKeyCodec.LOGICAL_ROW_ID_BYTES - fullHeaderBytes;
    int shared = Math.min(keyUserLength, prefixUserLength);
    int comparison = TupleKeyCodec.compare(
        key, keyOffset + fullHeaderBytes, shared,
        source, offset + headerBytes, shared);
    if (comparison != 0) return comparison;
    return keyUserLength < prefixUserLength ? -1 : 0;
  }

  public void clear() {
    source = null;
    offset = 0;
    prefixUserLength = 0;
    headerBytes = 0;
    fullHeaderBytes = 0;
  }
}
