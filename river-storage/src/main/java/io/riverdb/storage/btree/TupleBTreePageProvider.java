package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;

/**
 * Ownership boundary for tuple-tree pages and its root reference.
 *
 * <p>The caller serializes one tree mutation. Writable pins and allocations are transaction-staged;
 * any non-OK mutation result after a writable pin or allocation requires the enclosing transaction
 * to abort and discard all staged pages. A borrowed page is valid and immovable until
 * {@link #release(TupleBTreePageReference)} succeeds. Pin and allocation failures must leave their
 * result detached. A release failure retains the borrow so the caller can retry release or fence the
 * owning workspace. Implementations support at least two simultaneous borrows from one workspace.
 */
public interface TupleBTreePageProvider {
  int rootPageId();

  /** Monotonic root publication generation; providers that can reuse page ids must override. */
  default long rootGeneration() { return Integer.toUnsignedLong(rootPageId()); }

  StatusCode pin(int pageId, boolean writable, TupleBTreePageReference result);

  /** Admits a first graph traversal of a page; mutation providers need no extra accounting. */
  default StatusCode visit(int pageId) { return StatusCode.OK; }

  /**
   * Reports whether this borrowed page generation has already passed complete
   * tuple-page validation for the requested schema and page type.
   */
  boolean pageValidationMatches(
      TupleBTreePageReference reference, long schemaId,
      long descriptorHash, int expectedType);

  /** Records successful complete validation for this immutable page generation. */
  void rememberPageValidation(
      TupleBTreePageReference reference, long schemaId,
      long descriptorHash, int pageType);

  StatusCode allocate(TupleBTreePageReference result);

  StatusCode replaceRoot(int expectedPageId, int replacementPageId);

  StatusCode release(TupleBTreePageReference reference);
}
