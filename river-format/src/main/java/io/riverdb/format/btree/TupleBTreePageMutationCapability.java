package io.riverdb.format.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import java.nio.ByteBuffer;

/** Single-use proof that one exact mutable page passed complete tuple-page validation. */
public final class TupleBTreePageMutationCapability {
  private ByteBuffer page;
  private TupleShape shape;
  private TupleBTreePageHeader header;
  private TupleBTreePageValidationProof proof;
  private long proofVersion;
  private int start;
  private long schemaId;
  private int type;
  private int entryCount;
  private int leftSibling;
  private int pointer;
  private int freeEnd;
  private int highKeyOffset;
  private int highKeyLength;

  void bind(
      ByteBuffer validatedPage, int validatedStart,
      long validatedSchemaId, TupleShape validatedShape,
      TupleBTreePageHeader header, TupleBTreePageValidationProof validationProof) {
    page = validatedPage;
    shape = validatedShape;
    this.header = header;
    proof = validationProof;
    proofVersion = validationProof.version();
    start = validatedStart;
    schemaId = validatedSchemaId;
    type = header.type();
    entryCount = header.entryCount();
    leftSibling = header.leftSiblingPageId();
    pointer = type == TupleBTreePageCodec.TYPE_LEAF
        ? header.rightSiblingPageId() : header.firstChildPageId();
    freeEnd = header.freeEnd();
    highKeyOffset = header.highKeyOffset();
    highKeyLength = header.highKeyLength();
  }

  boolean matches(
      ByteBuffer candidatePage, int candidateStart,
      long candidateSchemaId, TupleShape candidateShape, int expectedType) {
    return page != null && page == candidatePage && start == candidateStart
        && schemaId == candidateSchemaId && shape == candidateShape && type == expectedType
        && proof != null && proof.version() == proofVersion
        && proof.matches(
            candidatePage, candidateStart, candidateSchemaId,
            candidateShape.descriptorHash(), expectedType);
  }

  int entryCount() { return entryCount; }
  int leftSibling() { return leftSibling; }
  int pointer() { return pointer; }
  int freeEnd() { return freeEnd; }
  int highKeyOffset() { return highKeyOffset; }
  int highKeyLength() { return highKeyLength; }

  StatusCode sealValidation() {
    if (proof == null || proof.version() != proofVersion || !proof.matches(
        page, start, schemaId, shape.descriptorHash(), type)) {
      reset();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = proof.bind(page, start, schemaId, shape.descriptorHash(), type);
    if (!status.isOk() && header != null) header.invalidateValidation();
    clear();
    return status;
  }

  public void reset() {
    if (header != null) header.invalidateValidation();
    if (proof != null) proof.reset();
    clear();
  }

  private void clear() {
    page = null;
    shape = null;
    header = null;
    proof = null;
    proofVersion = 0;
    start = 0;
    schemaId = 0;
    type = 0;
    entryCount = 0;
    leftSibling = 0;
    pointer = 0;
    freeEnd = 0;
    highKeyOffset = 0;
    highKeyLength = 0;
  }
}
