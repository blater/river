package io.riverdb.engine.table;

import io.riverdb.format.page.PageCodec;
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
  volatile long validatedPageGeneration;
  long validatedSchemaId;
  long validatedDescriptorHash;
  int validatedType;

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
    validatedSchemaId = 0;
    validatedDescriptorHash = 0;
    validatedType = 0;
    validatedPageGeneration = 0;
  }

  boolean pageValidationMatches(
      long generation, long schemaId, long descriptorHash, int expectedType) {
    return generation > 0 && generation == pageGeneration
        && validatedPageGeneration == pageGeneration
        && validatedSchemaId == schemaId
        && validatedDescriptorHash == descriptorHash
        && (expectedType <= 0 || validatedType == expectedType);
  }

  void rememberPageValidation(long schemaId, long descriptorHash, int pageType) {
    validatedSchemaId = schemaId;
    validatedDescriptorHash = descriptorHash;
    validatedType = pageType;
    validatedPageGeneration = pageGeneration;
  }

  void copyPageValidationFrom(IndexedPageFrame source) {
    if (source == null || source.validatedPageGeneration != source.pageGeneration) {
      invalidatePageValidation();
      return;
    }
    validatedSchemaId = source.validatedSchemaId;
    validatedDescriptorHash = source.validatedDescriptorHash;
    validatedType = source.validatedType;
    validatedPageGeneration = pageGeneration;
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
