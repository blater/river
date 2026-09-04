package io.riverdb.format.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;

/**
 * Revocable proof of complete validation for one exact tuple-page payload.
 *
 * <p>The byte owner must reset this proof before changing or reusing the bound buffer. The proof
 * deliberately carries no content hash: it is an allocation-free ownership capability, not a way
 * to trust bytes that remain mutable outside the owning page lifecycle.
 */
public final class TupleBTreePageValidationProof {
  private ByteBuffer page;
  private int start;
  private long schemaId;
  private long descriptorHash;
  private int type;
  private long version;
  private boolean versionExhausted;
  private TupleBTreePageValidationProof revocationRoot;
  private long revocationVersion;

  StatusCode bind(
      ByteBuffer validatedPage, int validatedStart,
      long validatedSchemaId, long validatedDescriptorHash, int validatedType) {
    if (!advanceVersion()) {
      clearBinding();
      return StatusCode.FENCED;
    }
    page = validatedPage;
    start = validatedStart;
    schemaId = validatedSchemaId;
    descriptorHash = validatedDescriptorHash;
    type = validatedType;
    revocationRoot = null;
    revocationVersion = 0;
    return StatusCode.OK;
  }

  public boolean matches(
      ByteBuffer candidatePage, int candidateStart,
      long candidateSchemaId, long candidateDescriptorHash, int expectedType) {
    return authorityActive() && page == candidatePage && start == candidateStart
        && candidateStart >= 0
        && candidatePage.limit() - candidateStart >= PageCodec.MAX_PAYLOAD_BYTES
        && schemaId == candidateSchemaId && descriptorHash == candidateDescriptorHash
        && (expectedType <= 0 || type == expectedType);
  }

  public boolean matchesPage(ByteBuffer candidatePage, int candidateStart) {
    return authorityActive() && page == candidatePage && start == candidateStart
        && candidateStart >= 0
        && candidatePage.limit() - candidateStart >= PageCodec.MAX_PAYLOAD_BYTES;
  }

  public StatusCode copyTo(
      ByteBuffer candidatePage, int candidateStart,
      TupleBTreePageValidationProof target) {
    TupleBTreePageValidationProof root = revocationRoot;
    if (target == null || target == this || target == root) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!matchesPage(candidatePage, candidateStart)) {
      target.reset();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return root == null
        ? target.bind(page, start, schemaId, descriptorHash, type)
        : target.bindDependent(page, start, schemaId, descriptorHash, type, root);
  }

  /** Issues authority that is revoked when this proof is reset or rebound. */
  public StatusCode lendTo(
      ByteBuffer candidatePage, int candidateStart,
      TupleBTreePageValidationProof target) {
    TupleBTreePageValidationProof root = revocationRoot == null ? this : revocationRoot;
    if (target == null || target == this || target == root
        || !matchesPage(candidatePage, candidateStart)) {
      if (target != null && target != this && target != root) target.reset();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return target.bindDependent(page, start, schemaId, descriptorHash, type, root);
  }

  /**
   * Copies the complete format-defined payload and binds the target proof as one operation.
   * The owner supplies a distinct, non-aliasing target buffer.
   */
  public StatusCode copyValidatedPayloadTo(
      ByteBuffer targetPage, int targetStart, TupleBTreePageValidationProof target) {
    if (target == null || target == this || !authorityActive() || targetPage == null
        || targetPage == page
        || start < 0 || page.limit() - start < PageCodec.MAX_PAYLOAD_BYTES
        || targetPage.isReadOnly() || targetStart < 0
        || targetPage.limit() - targetStart < PageCodec.MAX_PAYLOAD_BYTES) {
      if (target != null && target != this) target.reset();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!target.canRebind()) return StatusCode.FENCED;
    targetPage.put(targetStart, page, start, PageCodec.MAX_PAYLOAD_BYTES);
    targetPage.position(0);
    return target.bind(targetPage, targetStart, schemaId, descriptorHash, type);
  }

  long version() { return version; }

  public void reset() {
    advanceVersion();
    clearBinding();
  }

  private void clearBinding() {
    page = null;
    start = 0;
    schemaId = 0;
    descriptorHash = 0;
    type = 0;
    revocationRoot = null;
    revocationVersion = 0;
  }

  private StatusCode bindDependent(
      ByteBuffer validatedPage, int validatedStart,
      long validatedSchemaId, long validatedDescriptorHash, int validatedType,
      TupleBTreePageValidationProof root) {
    if (!advanceVersion()) {
      clearBinding();
      return StatusCode.FENCED;
    }
    page = validatedPage;
    start = validatedStart;
    schemaId = validatedSchemaId;
    descriptorHash = validatedDescriptorHash;
    type = validatedType;
    revocationRoot = root;
    revocationVersion = root.version();
    return StatusCode.OK;
  }

  private boolean authorityActive() {
    return page != null && (revocationRoot == null
        || revocationRoot.version() == revocationVersion
            && revocationRoot.authorityActive());
  }

  boolean canRebind() {
    return !versionExhausted && version != Long.MAX_VALUE;
  }

  private boolean advanceVersion() {
    if (!canRebind()) {
      versionExhausted = true;
      return false;
    }
    version++;
    return true;
  }
}
