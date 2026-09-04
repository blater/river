package io.riverdb.format.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;

/** Complete allocation-free validation of one variable-key B-tree payload. */
final class TupleBTreePageValidation {
  private TupleBTreePageValidation() { }

  static StatusCode readHeader(
      ByteBuffer source, int start, TupleBTreePageHeader result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!TupleBTreePageBytes.validPayload(source, start, false)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.set(
        FormatBytes.getInt(source, start + 12),
        FormatBytes.getInt(source, start + 16),
        FormatBytes.getInt(source, start + 24),
        FormatBytes.getInt(source, start + 64),
        FormatBytes.getInt(source, start + 44),
        FormatBytes.getLong(source, start + 48),
        FormatBytes.getLong(source, start + 56),
        FormatBytes.getInt(source, start + 32),
        FormatBytes.getInt(source, start + 36),
        FormatBytes.getInt(source, start + 40));
    return StatusCode.OK;
  }

  static StatusCode validate(
      ByteBuffer source, int start, long expectedSchemaId,
      TupleShape expectedShape, TupleBTreePageHeader result,
      TupleBTreePageValidationProof proof) {
    if (proof != null) proof.reset();
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!TupleBTreePageBytes.validPayload(source, start, false) || expectedShape == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int type = FormatBytes.getInt(source, start + 12);
    int count = FormatBytes.getInt(source, start + 16);
    int pointer = FormatBytes.getInt(source, start + 24);
    int leftSibling = FormatBytes.getInt(source, start + 64);
    int freeStart = FormatBytes.getInt(source, start + 28);
    int freeEnd = FormatBytes.getInt(source, start + 32);
    int highOffset = FormatBytes.getInt(source, start + 36);
    int highLength = FormatBytes.getInt(source, start + 40);
    int arity = FormatBytes.getInt(source, start + 44);
    long descriptorHash = FormatBytes.getLong(source, start + 48);
    long schemaId = FormatBytes.getLong(source, start + 56);
    if (!TupleBTreeHeaderValidation.valid(
        source, start, type, count, pointer, leftSibling, freeStart, freeEnd,
        highOffset, highLength, arity, descriptorHash, schemaId,
        expectedSchemaId, expectedShape)) return StatusCode.CORRUPTION;
    int cursor = PageCodec.MAX_PAYLOAD_BYTES;
    if (highLength > 0) {
      cursor -= highLength;
      if (highOffset != cursor || !TupleKeyCodec.matchesPhysicalIndexKey(
          source, start + highOffset, highLength, expectedShape)) {
        return StatusCode.CORRUPTION;
      }
    }
    int previousOffset = 0;
    int previousLength = 0;
    for (int index = 0; index < count; index++) {
      int slot = start + TupleBTreePageCodec.HEADER_BYTES
          + index * TupleBTreePageCodec.SLOT_BYTES;
      int keyOffset = FormatBytes.getInt(source, slot);
      int keyLength = FormatBytes.getInt(source, slot + 4);
      cursor -= keyLength;
      if (!TupleBTreeEntryValidation.valid(
          source, start, slot, type, keyOffset, keyLength, cursor, freeStart,
          highOffset, highLength, previousOffset, previousLength, index, expectedShape)) {
        return StatusCode.CORRUPTION;
      }
      previousOffset = keyOffset;
      previousLength = keyLength;
    }
    if (freeEnd != cursor || !TupleBTreePageBytes.zeroRange(
        source, start + freeStart, start + freeEnd)) return StatusCode.CORRUPTION;
    result.set(
        type, count, pointer, leftSibling, arity, descriptorHash,
        schemaId, freeEnd, highOffset, highLength);
    if (proof != null) {
      StatusCode proofStatus = proof.bind(source, start, schemaId, descriptorHash, type);
      if (!proofStatus.isOk()) {
        result.reset();
        return proofStatus;
      }
      result.bindValidation(proof);
    }
    return StatusCode.OK;
  }
}
