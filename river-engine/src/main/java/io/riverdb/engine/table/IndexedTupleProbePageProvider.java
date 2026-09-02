package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.btree.TupleBTreePageProvider;
import io.riverdb.storage.btree.TupleBTreePageReference;
import java.nio.ByteBuffer;

/** Read-only current-page provider for one short tuple probe. */
final class IndexedTupleProbePageProvider implements TupleBTreePageProvider {
  private final IndexedPageSet pages;
  private final IndexedPageGenerationPin firstPin = new IndexedPageGenerationPin();
  private final IndexedPageGenerationPin secondPin = new IndexedPageGenerationPin();
  private TupleBTreePageReference firstReference;
  private TupleBTreePageReference secondReference;
  private long ownerKeyId;
  private long visibleCommitSequence;
  private int rootPageId;
  private int nextPageId;
  private long rootGeneration;

  IndexedTupleProbePageProvider(IndexedPageSet pageSet) { pages = pageSet; }

  StatusCode configure(
      int root, long owner, int next, long generation, long visible) {
    if (root <= 0 || root >= next || owner <= 0 || generation <= 0 || visible < 0
        || firstReference != null || secondReference != null) {
      return StatusCode.CORRUPTION;
    }
    rootPageId = root;
    ownerKeyId = owner;
    nextPageId = next;
    rootGeneration = generation;
    visibleCommitSequence = visible;
    return StatusCode.OK;
  }

  @Override public int rootPageId() { return rootPageId; }
  @Override public long rootGeneration() { return rootGeneration; }

  @Override
  public StatusCode pin(int pageId, boolean writable, TupleBTreePageReference result) {
    if (writable || result == null || result.isAttached()
        || pageId <= 0 || pageId >= nextPageId) {
      return writable || result == null || result != null && result.isAttached()
          ? StatusCode.INVALID_EXTERNAL_INPUT : StatusCode.CORRUPTION;
    }
    IndexedPageGenerationPin pin = firstReference == null
        ? firstPin : secondReference == null ? secondPin : null;
    if (pin == null) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = pages.pinPageAt(pageId, visibleCommitSequence, pin);
    if (!status.isOk()) return status;
    ByteBuffer payload = pin.payload();
    if (pin.payloadKind() != PageCodec.PAYLOAD_KIND_TUPLE_BTREE
        || pin.ownerKeyId() != ownerKeyId) status = StatusCode.CORRUPTION;
    else status = result.attach(pageId, payload, 0, false, pin.pageGeneration());
    if (!status.isOk()) {
      result.reset();
      pages.unpinPage(pin);
    } else if (pin == firstPin) {
      firstReference = result;
    } else {
      secondReference = result;
    }
    return status;
  }

  @Override
  public boolean pageValidationMatches(
      TupleBTreePageReference reference, long schemaId,
      long descriptorHash, int expectedType) {
    return pages.pageValidationMatches(
        reference.pageId(), reference.pageGeneration(),
        schemaId, descriptorHash, expectedType);
  }

  @Override
  public void rememberPageValidation(
      TupleBTreePageReference reference, long schemaId,
      long descriptorHash, int pageType) {
    pages.rememberPageValidation(
        reference.pageId(), reference.pageGeneration(),
        schemaId, descriptorHash, pageType);
  }

  @Override public StatusCode allocate(TupleBTreePageReference result) {
    return StatusCode.FEATURE_NOT_SUPPORTED;
  }

  @Override public StatusCode replaceRoot(int expected, int replacement) {
    return StatusCode.FEATURE_NOT_SUPPORTED;
  }

  @Override
  public StatusCode release(TupleBTreePageReference reference) {
    if (reference == null || !reference.isAttached()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    IndexedPageGenerationPin pin = reference == firstReference ? firstPin
        : reference == secondReference ? secondPin : null;
    if (pin == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = pages.unpinPage(pin);
    if (status.isOk()) {
      reference.reset();
      if (reference == firstReference) firstReference = null;
      else secondReference = null;
    }
    return status;
  }
}
