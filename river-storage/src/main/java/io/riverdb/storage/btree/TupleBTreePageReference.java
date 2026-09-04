package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.page.PageCodec;
import io.riverdb.format.btree.TupleBTreePageValidationProof;
import java.nio.ByteBuffer;

/** Provider-populated borrowed tuple-page payload with explicit pin lifetime. */
public final class TupleBTreePageReference {
  private int pageId;
  private ByteBuffer page;
  private int start;
  private boolean writable;
  private long pageGeneration;
  private final TupleBTreePageValidationProof validation =
      new TupleBTreePageValidationProof();

  public StatusCode attach(
      int id, ByteBuffer source, int offset, boolean forWrite, long generation) {
    reset();
    if (id <= 0 || source == null || offset < 0
        || source.limit() - offset < PageCodec.MAX_PAYLOAD_BYTES
        || forWrite && source.isReadOnly() || generation <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    pageId = id;
    page = source;
    start = offset;
    writable = forWrite;
    pageGeneration = generation;
    return StatusCode.OK;
  }

  public void reset() {
    validation.reset();
    pageId = 0;
    page = null;
    start = 0;
    writable = false;
    pageGeneration = 0;
  }

  public boolean isAttached() { return page != null; }
  public int pageId() { return pageId; }

  /**
   * Returns the exact provider-owned borrowed view. Do not duplicate or retain it beyond successful
   * release, and mutate it only while this reference is writable.
   */
  public ByteBuffer page() { return page; }
  public int start() { return start; }
  public boolean isWritable() { return writable; }
  public long pageGeneration() { return pageGeneration; }
  TupleBTreePageValidationProof validation() { return validation; }
}
