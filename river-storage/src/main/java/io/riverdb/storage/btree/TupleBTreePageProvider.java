package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleBTreePageValidationProof;

/**
 * Ownership boundary for tuple-tree pages and its root reference.
 *
 * <p>The caller serializes one tree mutation. Writable pins and allocations are transaction-staged;
 * any non-OK mutation result after a writable pin or allocation requires the enclosing transaction
 * to abort and discard all staged pages. A borrowed page is valid and immovable until
 * {@link #release(TupleBTreePageReference)} succeeds. Pin and allocation failures must leave their
 * result detached. A release failure retains the borrow so the caller can retry release or fence the
 * owning workspace. On successful release the caller resets the reference exactly once; providers
 * end their own pin but do not reset caller-owned reference state. Tree algorithms retain no more
 * than two page borrows at once; implementations must support those two and may support more.
 */
public interface TupleBTreePageProvider {
  int rootPageId();

  /** Monotonic root publication generation; providers that can reuse page ids must override. */
  default long rootGeneration() { return Integer.toUnsignedLong(rootPageId()); }

  StatusCode pin(int pageId, boolean writable, TupleBTreePageReference result);

  /**
   * Graph-validation providers admit a page's first visit and reject repeated reachability;
   * mutation providers need no additional accounting.
   */
  default StatusCode visit(int pageId) { return StatusCode.OK; }

  /**
   * Restores complete validation for this exact borrowed page generation.
   * {@link StatusCode#CONFLICT} is a cache miss, not a transaction conflict.
   */
  StatusCode restorePageValidation(
      TupleBTreePageReference reference, long schemaId,
      long descriptorHash, int expectedType,
      TupleBTreePageValidationProof target);

  /** Records successful complete validation for this immutable page generation. */
  StatusCode rememberPageValidation(
      TupleBTreePageReference reference, long schemaId,
      long descriptorHash, int pageType,
      TupleBTreePageValidationProof source);

  /**
   * Consumes validation lineage retained for the exact bytes presented by this writable borrow.
   * {@link StatusCode#CONFLICT} is a cache miss; implementations discard stale lineage either way.
   */
  StatusCode consumeCanonicalMutationValidation(
      TupleBTreePageReference reference, long schemaId,
      long descriptorHash, int pageType,
      TupleBTreePageValidationProof target);

  /**
   * Authenticates canonical bytes produced under this exact writable borrow.
   * The provider must reject detached, read-only, stale, or foreign references.
   */
  StatusCode sealCanonicalMutation(
      TupleBTreePageReference reference, long schemaId,
      long descriptorHash, int pageType,
      TupleBTreePageValidationProof source);

  StatusCode allocate(TupleBTreePageReference result);

  StatusCode replaceRoot(int expectedPageId, int replacementPageId);

  StatusCode release(TupleBTreePageReference reference);
}
