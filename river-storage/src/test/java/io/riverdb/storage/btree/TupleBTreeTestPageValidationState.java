package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleBTreePageValidationProof;
import java.nio.ByteBuffer;

final class TupleBTreeTestPageValidationState {
  private final long[] validatedGenerations;
  private final TupleBTreePageValidationProof[] validations;
  private final long[] mutationInputGenerations;
  private final TupleBTreePageValidationProof[] mutationInputValidations;
  private final long[] pendingMutationGenerations;
  private final TupleBTreePageValidationProof[] pendingMutationValidations;
  private final boolean[] writableBorrowed;
  private int validationCount;
  private int validationMissCount;
  private int canonicalSealCount;
  private int canonicalValidationReuseCount;

  TupleBTreeTestPageValidationState(int maximumPages) {
    int capacity = maximumPages + 1;
    validatedGenerations = new long[capacity];
    validations = new TupleBTreePageValidationProof[capacity];
    mutationInputGenerations = new long[capacity];
    mutationInputValidations = new TupleBTreePageValidationProof[capacity];
    pendingMutationGenerations = new long[capacity];
    pendingMutationValidations = new TupleBTreePageValidationProof[capacity];
    writableBorrowed = new boolean[capacity];
    for (int index = 0; index < capacity; index++) {
      validations[index] = new TupleBTreePageValidationProof();
      mutationInputValidations[index] = new TupleBTreePageValidationProof();
      pendingMutationValidations[index] = new TupleBTreePageValidationProof();
    }
  }

  boolean writableBorrowed(int pageId) {
    return writableBorrowed[pageId];
  }

  StatusCode restore(
      int pageId, long generation, ByteBuffer page,
      long schemaId, long descriptorHash, int expectedType,
      TupleBTreePageValidationProof target) {
    boolean matches = validatedGenerations[pageId] == generation
        && validations[pageId].matches(page, 0, schemaId, descriptorHash, expectedType);
    if (!matches) validationMissCount++;
    if (!matches) {
      if (target != null) target.reset();
      return StatusCode.CONFLICT;
    }
    return validations[pageId].lendTo(page, 0, target);
  }

  StatusCode restoreMiss(TupleBTreePageValidationProof target) {
    validationMissCount++;
    if (target != null) target.reset();
    return StatusCode.CONFLICT;
  }

  StatusCode remember(
      int pageId, long generation, ByteBuffer page,
      TupleBTreePageValidationProof source) {
    StatusCode status = source.copyTo(page, 0, validations[pageId]);
    if (!status.isOk()) return status;
    validatedGenerations[pageId] = generation;
    validationCount++;
    return StatusCode.OK;
  }

  StatusCode consumeCanonicalMutation(
      int pageId, long generation, ByteBuffer page,
      long schemaId, long descriptorHash, int pageType,
      TupleBTreePageValidationProof target) {
    boolean matches = mutationInputGenerations[pageId] == generation
        && mutationInputValidations[pageId].matches(
            page, 0, schemaId, descriptorHash, pageType);
    StatusCode status = matches
        ? mutationInputValidations[pageId].copyTo(page, 0, target)
        : StatusCode.CONFLICT;
    discardMutationInputValidation(pageId);
    if (matches) canonicalValidationReuseCount++;
    if (!matches && target != null) target.reset();
    return status;
  }

  StatusCode rejectCanonicalMutation(
      int pageId, TupleBTreePageValidationProof target) {
    discardMutationInputValidation(pageId);
    if (target != null) target.reset();
    return StatusCode.CONFLICT;
  }

  StatusCode sealCanonicalMutation(
      int pageId, long generation, ByteBuffer page,
      long schemaId, long descriptorHash, int pageType,
      TupleBTreePageValidationProof source) {
    StatusCode status = source.copyTo(
        page, 0, pendingMutationValidations[pageId]);
    if (!status.isOk()) return status;
    pendingMutationGenerations[pageId] = generation;
    return StatusCode.OK;
  }

  StatusCode releaseWritable(int pageId, long generation, ByteBuffer page) {
    if (pendingMutationGenerations[pageId] != 0) {
      if (pendingMutationGenerations[pageId] != generation
          || !pendingMutationValidations[pageId].matchesPage(page, 0)) {
        return StatusCode.INVARIANT_BROKEN;
      }
      StatusCode status = pendingMutationValidations[pageId].copyTo(
          page, 0, validations[pageId]);
      if (!status.isOk()) return status;
      validatedGenerations[pageId] = generation;
      canonicalSealCount++;
    }
    discardPendingMutationValidation(pageId);
    discardMutationInputValidation(pageId);
    writableBorrowed[pageId] = false;
    return StatusCode.OK;
  }

  void beginWritableBorrow(int pageId, long generation, ByteBuffer page) {
    discardMutationInputValidation(pageId);
    discardPendingMutationValidation(pageId);
    if (validatedGenerations[pageId] == generation) {
      validations[pageId].copyTo(
          page, 0, mutationInputValidations[pageId]);
      mutationInputGenerations[pageId] = generation;
    }
    validations[pageId].reset();
    validatedGenerations[pageId] = 0;
    writableBorrowed[pageId] = true;
  }

  void invalidate(int pageId) {
    discardMutationInputValidation(pageId);
    discardPendingMutationValidation(pageId);
    validations[pageId].reset();
    validatedGenerations[pageId] = 0;
  }

  int validationCount() { return validationCount; }
  int validationMissCount() { return validationMissCount; }
  int canonicalSealCount() { return canonicalSealCount; }
  int canonicalValidationReuseCount() { return canonicalValidationReuseCount; }

  private void discardMutationInputValidation(int pageId) {
    mutationInputValidations[pageId].reset();
    mutationInputGenerations[pageId] = 0;
  }

  private void discardPendingMutationValidation(int pageId) {
    pendingMutationValidations[pageId].reset();
    pendingMutationGenerations[pageId] = 0;
  }
}
