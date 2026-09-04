package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.page.PageCodec;
import io.riverdb.format.btree.TupleBTreePageValidationProof;
import java.nio.ByteBuffer;

/** One reusable direct page frame and its borrowed payload view. */
final class IndexedPageFrame {
  final ByteBuffer page = ByteBuffer.allocateDirect(PageCodec.PAGE_BYTES);
  ByteBuffer payload;
  int pageId;
  long pageGeneration;
  boolean dirty;
  long recordStart;
  long recordEnd;
  long access;
  int pinCount;
  long validFromCommitSequence;
  long validUntilCommitSequence = Long.MAX_VALUE;
  int previousVersionSlot = -1;
  int nextVersionSlot = -1;
  boolean publicationReserved;
  int payloadKind = PageCodec.PAYLOAD_KIND_SCALAR_BTREE;
  long ownerKeyId = PageCodec.SCALAR_OWNER_KEY_ID;
  int previousPayloadKind = PageCodec.PAYLOAD_KIND_SCALAR_BTREE;
  long previousOwnerKeyId = PageCodec.SCALAR_OWNER_KEY_ID;
  final IndexedPageValidationState validation = new IndexedPageValidationState();

  IndexedPageFrame() {
    prepare();
  }

  void prepare() {
    page.clear();
    page.limit(PageCodec.PAGE_BYTES);
    ByteBuffer view = page.duplicate();
    view.position(PageCodec.HEADER_BYTES);
    view.limit(PageCodec.PAGE_BYTES);
    payload = view.slice();
    invalidatePageValidation();
  }

  void beginPageGeneration(long generation) {
    pageGeneration = generation;
    invalidatePageValidation();
  }

  void invalidatePageValidation() {
    validation.invalidate();
  }

  boolean beginWritableBorrow() {
    return validation.beginWritable(payload, pageGeneration);
  }

  StatusCode consumeMutationInputValidation(
      long generation, long schemaId, long descriptorHash, int pageType,
      TupleBTreePageValidationProof target) {
    return validation.consumeMutationInput(
        payload, pageGeneration, generation,
        schemaId, descriptorHash, pageType, target);
  }

  StatusCode endWritableBorrow() {
    return validation.endWritable(payload, pageGeneration);
  }

  StatusCode restorePageValidation(
      long generation, long schemaId, long descriptorHash, int expectedType,
      TupleBTreePageValidationProof target) {
    return validation.restore(
        payload, pageGeneration, generation,
        schemaId, descriptorHash, expectedType, target);
  }

  StatusCode rememberPageValidation(
      long schemaId, long descriptorHash, int pageType,
      TupleBTreePageValidationProof source) {
    return validation.remember(
        payload, pageGeneration, schemaId, descriptorHash, pageType, source);
  }

  StatusCode sealMutationValidation(
      long generation, long schemaId, long descriptorHash, int pageType,
      TupleBTreePageValidationProof source) {
    return validation.sealMutation(
        payload, pageGeneration, generation,
        schemaId, descriptorHash, pageType, source);
  }

  void copyPageFrom(IndexedPageFrame source) {
    if (source == null) {
      invalidatePageValidation();
      return;
    }
    page.put(0, source.page, 0, PageCodec.HEADER_BYTES);
    StatusCode copied = validation.copyPayloadFrom(
        source.validation, source.pageGeneration,
        payload, pageGeneration);
    if (!copied.isOk()) {
      page.put(
          PageCodec.HEADER_BYTES, source.page,
          PageCodec.HEADER_BYTES, PageCodec.MAX_PAYLOAD_BYTES);
      validation.invalidateReadable();
    }
    page.position(0);
    page.limit(PageCodec.PAGE_BYTES);
  }

  void identity(int kind, long owner) {
    payloadKind = kind;
    ownerKeyId = owner;
  }

  void rememberIdentity(int kind, long owner) {
    previousPayloadKind = kind;
    previousOwnerKeyId = owner;
  }

  void currentGeneration(long validFrom, int previousSlot) {
    validFromCommitSequence = validFrom;
    validUntilCommitSequence = Long.MAX_VALUE;
    previousVersionSlot = previousSlot;
    nextVersionSlot = -1;
  }

  void clearGeneration() {
    validFromCommitSequence = 0;
    validUntilCommitSequence = Long.MAX_VALUE;
    previousVersionSlot = -1;
    nextVersionSlot = -1;
    publicationReserved = false;
    invalidatePageValidation();
  }
}
