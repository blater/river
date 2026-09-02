package io.riverdb.format.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;

/** Compact slotted B-tree payload with inline physical tuple keys. */
public final class TupleBTreePageCodec {
  public static final int VERSION = 3;
  public static final int TYPE_LEAF = 1;
  public static final int TYPE_INTERNAL = 2;
  public static final int HEADER_BYTES = 72;
  public static final int SLOT_BYTES = 12;
  public static final int MAXIMUM_SLOTS =
      (PageCodec.MAX_PAYLOAD_BYTES - HEADER_BYTES) / SLOT_BYTES;
  static final long MAGIC = 0x5249565455425450L; // RIVTUBTP

  private TupleBTreePageCodec() { }

  public static StatusCode initialize(
      ByteBuffer target, int start, int type, int pointer,
      TupleShape shape, long keySchemaId,
      ByteBuffer highKey, int highKeyOffset, int highKeyLength) {
    return TupleBTreePageInitialize.initialize(
        target, start, type, pointer, shape, keySchemaId,
        highKey, highKeyOffset, highKeyLength);
  }

  public static StatusCode initializeLeaf(
      ByteBuffer target, int start, int leftSibling, int rightSibling,
      TupleShape shape, long keySchemaId,
      ByteBuffer highKey, int highKeyOffset, int highKeyLength) {
    return TupleBTreePageInitialize.initializeLeaf(
        target, start, leftSibling, rightSibling, shape, keySchemaId,
        highKey, highKeyOffset, highKeyLength);
  }

  public static StatusCode replaceLeftSibling(
      ByteBuffer page, int start, int expectedPageId, int replacementPageId) {
    return TupleBTreePageLinks.replaceLeft(page, start, expectedPageId, replacementPageId);
  }

  public static StatusCode appendLeaf(
      ByteBuffer page, int start, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength) {
    return TupleBTreePageAppend.append(
        page, start, shape, TYPE_LEAF, key, keyOffset, keyLength, 0);
  }

  public static StatusCode appendInternal(
      ByteBuffer page, int start, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength, int rightChildPageId) {
    return TupleBTreePageAppend.append(
        page, start, shape, TYPE_INTERNAL,
        key, keyOffset, keyLength, rightChildPageId);
  }

  public static StatusCode readLeaf(
      ByteBuffer source, int start, TupleBTreePageHeader header,
      int index, TupleBTreeLeafEntry result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!TupleBTreePageBytes.validRead(source, start, header, index, TYPE_LEAF)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int slot = start + HEADER_BYTES + index * SLOT_BYTES;
    int keyOffset = FormatBytes.getInt(source, slot);
    int keyLength = FormatBytes.getInt(source, slot + 4);
    result.set(keyOffset, keyLength,
        TupleKeyCodec.logicalRowId(source, start + keyOffset, keyLength));
    return StatusCode.OK;
  }

  public static StatusCode readInternal(
      ByteBuffer source, int start, TupleBTreePageHeader header,
      int index, TupleBTreeInternalEntry result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!TupleBTreePageBytes.validRead(source, start, header, index, TYPE_INTERNAL)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int slot = start + HEADER_BYTES + index * SLOT_BYTES;
    result.set(FormatBytes.getInt(source, slot), FormatBytes.getInt(source, slot + 4),
        FormatBytes.getInt(source, slot + 8));
    return StatusCode.OK;
  }

  public static StatusCode validate(
      ByteBuffer source, int start, long expectedSchemaId,
      TupleShape expectedShape, TupleBTreePageHeader result) {
    return TupleBTreePageValidation.validate(
        source, start, expectedSchemaId, expectedShape, result);
  }

  /**
   * Reads the fixed header of a page whose complete validation is already
   * authenticated by its owning page-generation capability.
   *
   * <p>This deliberately does not validate the page magic, slots, keys, or
   * schema. Callers must use {@link #validate} when admitting persisted,
   * external, WAL, or newly mutated bytes. The method exists so an unchanged
   * immutable frame can reuse that validation while still refreshing a
   * caller-owned header view without allocating.
   */
  public static StatusCode readValidatedHeader(
      ByteBuffer source, int start, TupleBTreePageHeader result) {
    return TupleBTreePageValidation.readHeader(source, start, result);
  }

  /** Validates self-describing page structure before a catalog descriptor is available. */
  public static StatusCode validateEnvelope(
      ByteBuffer source, int start, TupleBTreePageHeader result) {
    return TupleBTreeEnvelopeValidation.validate(source, start, result);
  }
}
