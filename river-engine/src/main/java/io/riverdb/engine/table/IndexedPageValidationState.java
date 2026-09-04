package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleBTreePageValidationProof;
import java.nio.ByteBuffer;

/** Validation lineage owned by one page frame across read and exclusive-write borrows. */
final class IndexedPageValidationState {
  private final TupleBTreePageValidationProof readable =
      new TupleBTreePageValidationProof();
  private final TupleBTreePageValidationProof mutationInput =
      new TupleBTreePageValidationProof();
  private final TupleBTreePageValidationProof pendingMutation =
      new TupleBTreePageValidationProof();
  private volatile long readableGeneration;
  private long mutationInputGeneration;
  private long pendingMutationGeneration;
  private boolean writableBorrowed;

  void invalidate() {
    discardMutationInput();
    discardPendingMutation();
    invalidateReadable();
  }

  boolean beginWritable(ByteBuffer payload, long pageGeneration) {
    if (writableBorrowed) return false;
    discardMutationInput();
    discardPendingMutation();
    if (readableGeneration == pageGeneration
        && readable.copyTo(payload, 0, mutationInput).isOk()) {
      mutationInputGeneration = pageGeneration;
    }
    invalidateReadable();
    writableBorrowed = true;
    return true;
  }

  StatusCode consumeMutationInput(
      ByteBuffer payload, long pageGeneration, long generation,
      long schemaId, long descriptorHash, int pageType,
      TupleBTreePageValidationProof target) {
    boolean matches = writableBorrowed && generation == pageGeneration
        && mutationInputGeneration == pageGeneration
        && mutationInput.matches(
            payload, 0, schemaId, descriptorHash, pageType);
    StatusCode status = matches
        ? mutationInput.copyTo(payload, 0, target) : StatusCode.CONFLICT;
    discardMutationInput();
    if (!matches && target != null) target.reset();
    return status;
  }

  StatusCode endWritable(ByteBuffer payload, long pageGeneration) {
    if (!writableBorrowed) return StatusCode.INVARIANT_BROKEN;
    if (pendingMutationGeneration != 0) {
      if (pendingMutationGeneration != pageGeneration
          || !pendingMutation.matchesPage(payload, 0)) {
        return StatusCode.INVARIANT_BROKEN;
      }
      StatusCode status = pendingMutation.copyTo(payload, 0, readable);
      if (!status.isOk()) return status;
      readableGeneration = pageGeneration;
    }
    discardPendingMutation();
    discardMutationInput();
    writableBorrowed = false;
    return StatusCode.OK;
  }

  StatusCode restore(
      ByteBuffer payload, long pageGeneration, long generation,
      long schemaId, long descriptorHash, int expectedType,
      TupleBTreePageValidationProof target) {
    boolean matches = !writableBorrowed && generation > 0
        && generation == pageGeneration && readableGeneration == pageGeneration
        && readable.matches(payload, 0, schemaId, descriptorHash, expectedType);
    if (!matches) {
      if (target != null) target.reset();
      return StatusCode.CONFLICT;
    }
    return readable.lendTo(payload, 0, target);
  }

  StatusCode remember(
      ByteBuffer payload, long pageGeneration,
      long schemaId, long descriptorHash, int pageType,
      TupleBTreePageValidationProof source) {
    if (writableBorrowed || source == null || !source.matches(
        payload, 0, schemaId, descriptorHash, pageType)) {
      invalidateReadable();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = source.copyTo(payload, 0, readable);
    if (!status.isOk()) return status;
    readableGeneration = pageGeneration;
    return StatusCode.OK;
  }

  StatusCode sealMutation(
      ByteBuffer payload, long pageGeneration, long generation,
      long schemaId, long descriptorHash, int pageType,
      TupleBTreePageValidationProof source) {
    if (!writableBorrowed || generation != pageGeneration || source == null
        || !source.matches(payload, 0, schemaId, descriptorHash, pageType)) {
      discardPendingMutation();
      return StatusCode.INVARIANT_BROKEN;
    }
    StatusCode status = source.copyTo(payload, 0, pendingMutation);
    if (!status.isOk()) {
      discardPendingMutation();
      return status;
    }
    pendingMutationGeneration = pageGeneration;
    return StatusCode.OK;
  }

  StatusCode copyPayloadFrom(
      IndexedPageValidationState source,
      long sourceGeneration,
      ByteBuffer targetPayload, long targetGeneration) {
    if (writableBorrowed) return StatusCode.INVARIANT_BROKEN;
    discardMutationInput();
    discardPendingMutation();
    StatusCode status = source.readableGeneration == sourceGeneration
        ? source.readable.copyValidatedPayloadTo(targetPayload, 0, readable)
        : StatusCode.CONFLICT;
    if (!status.isOk()) {
      invalidateReadable();
      return status;
    }
    readableGeneration = targetGeneration;
    return StatusCode.OK;
  }

  void invalidateReadable() {
    readable.reset();
    readableGeneration = 0;
  }

  private void discardMutationInput() {
    mutationInput.reset();
    mutationInputGeneration = 0;
  }

  private void discardPendingMutation() {
    pendingMutation.reset();
    pendingMutationGeneration = 0;
  }
}
