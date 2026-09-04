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

  /**
   * Fully validates one exact mutable leaf and binds a single-use mutation capability to it.
   * The caller must retain exclusive ownership and leave the bytes unchanged until mutation.
   */
  public static StatusCode prepareLeafMutation(
      ByteBuffer page, int start, long schemaId, TupleShape shape,
      TupleBTreePageHeader header, TupleBTreePageValidationProof proof,
      TupleBTreePageMutationCapability capability) {
    if (capability == null || header == null || proof == null || shape == null) {
      if (capability != null) capability.reset();
      if (proof != null) proof.reset();
      if (header != null) header.reset();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    capability.reset();
    StatusCode status = validateForRead(
        page, start, schemaId, shape, header, proof);
    if (!status.isOk()) return status;
    if (header.type() != TYPE_LEAF || page.isReadOnly()) {
      proof.reset();
      header.reset();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!proof.canRebind()) {
      proof.reset();
      header.reset();
      return StatusCode.FENCED;
    }
    capability.bind(page, start, schemaId, shape, header, proof);
    return StatusCode.OK;
  }

  /**
   * Binds mutation to an exact writable generation whose unchanged bytes were authenticated by
   * its owner. This performs fixed-header checks only; untrusted bytes must use
   * {@link #prepareLeafMutation}.
   */
  public static StatusCode prepareAuthenticatedLeafMutation(
      ByteBuffer page, int start, long schemaId, TupleShape shape,
      TupleBTreePageHeader header, TupleBTreePageValidationProof proof,
      TupleBTreePageMutationCapability capability) {
    if (capability == null || header == null || proof == null || shape == null || !proof.matches(
        page, start, schemaId, shape.descriptorHash(), TYPE_LEAF)) {
      if (capability != null) capability.reset();
      if (proof != null) proof.reset();
      if (header != null) header.reset();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    capability.reset();
    StatusCode status = readValidatedHeader(page, start, proof, header);
    if (!status.isOk()) return status;
    if (page.isReadOnly() || shape == null || header.type() != TYPE_LEAF
        || header.keySchemaId() != schemaId
        || header.keyArity() != shape.partCount()
        || header.descriptorHash() != shape.descriptorHash()) {
      proof.reset();
      header.reset();
      return StatusCode.INVARIANT_BROKEN;
    }
    if (!proof.canRebind()) {
      proof.reset();
      header.reset();
      return StatusCode.FENCED;
    }
    capability.bind(page, start, schemaId, shape, header, proof);
    return StatusCode.OK;
  }

  /** Mutates the exact leaf bound by {@link #prepareLeafMutation}. */
  public static StatusCode insertPreparedLeaf(
      ByteBuffer page, int start, long schemaId, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength, int insertion,
      TupleBTreePageMutationCapability capability) {
    return TupleBTreePageMutation.insertLeaf(
        page, start, schemaId, shape,
        key, keyOffset, keyLength, insertion, capability);
  }

  /** Mutates the exact leaf bound by {@link #prepareLeafMutation}. */
  public static StatusCode deletePreparedLeaf(
      ByteBuffer page, int start, long schemaId, TupleShape shape, int deletion,
      TupleBTreePageMutationCapability capability) {
    return TupleBTreePageMutation.deleteLeaf(
        page, start, schemaId, shape, deletion, capability);
  }

  public static StatusCode appendInternal(
      ByteBuffer page, int start, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength, int rightChildPageId) {
    return TupleBTreePageAppend.append(
        page, start, shape, TYPE_INTERNAL,
        key, keyOffset, keyLength, rightChildPageId);
  }

  /** Reads one leaf entry using the exact page/header pair admitted by complete validation. */
  public static StatusCode readValidatedLeaf(
      ByteBuffer source, int start, TupleBTreePageHeader header,
      int index, TupleBTreeLeafEntry result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!TupleBTreePageBytes.validValidatedRead(
        source, start, header, index, TYPE_LEAF)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int slot = start + HEADER_BYTES + index * SLOT_BYTES;
    int keyOffset = FormatBytes.getInt(source, slot);
    int keyLength = FormatBytes.getInt(source, slot + 4);
    result.set(keyOffset, keyLength,
        TupleKeyCodec.validatedLogicalRowId(source, start + keyOffset, keyLength));
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
        source, start, expectedSchemaId, expectedShape, result, null);
  }

  /**
   * Completely validates a page and issues a revocable proof for validated reads.
   * The byte owner must reset the proof before changing or reusing the buffer.
   */
  public static StatusCode validateForRead(
      ByteBuffer source, int start, long expectedSchemaId,
      TupleShape expectedShape, TupleBTreePageHeader result,
      TupleBTreePageValidationProof proof) {
    if (proof == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    return TupleBTreePageValidation.validate(
        source, start, expectedSchemaId, expectedShape, result, proof);
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
      ByteBuffer source, int start, TupleBTreePageValidationProof proof,
      TupleBTreePageHeader result) {
    if (proof == null || result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!proof.matchesPage(source, start)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = TupleBTreePageValidation.readHeader(source, start, result);
    if (status.isOk() && !proof.matches(
        source, start, result.keySchemaId(), result.descriptorHash(), result.type())) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) result.bindValidation(proof);
    else result.reset();
    return status;
  }

  /** Validates self-describing page structure before a catalog descriptor is available. */
  public static StatusCode validateEnvelope(
      ByteBuffer source, int start, TupleBTreePageHeader result) {
    return TupleBTreeEnvelopeValidation.validate(source, start, result);
  }
}
