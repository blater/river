package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.page.PageReadResult;
import io.riverdb.engine.page.PageUpdate;
import io.riverdb.engine.page.SinglePageStore;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.heap.HeapInsertResult;
import io.riverdb.storage.heap.HeapPage;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.storage.heap.HeapScanCursor;
import java.nio.ByteBuffer;

/** First internal table: one WAL-protected slotted heap page with autocommit inserts. */
public final class SinglePageTable {
  private static final long BOOTSTRAP_TRANSACTION_ID = 1;

  private final SinglePageStore pageStore;
  private final PageUpdate pageUpdate = new PageUpdate();
  private final PageReadResult pageRead = new PageReadResult();
  private long copiedRowBytes;

  private SinglePageTable(SinglePageStore store) {
    pageStore = store;
  }

  public static StatusCode create(
      SinglePageStore store,
      SinglePageTableOpenResult result) {
    if (store == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    SinglePageTable table = new SinglePageTable(store);
    StatusCode status = store.beginUpdate(PageCodec.MAX_PAYLOAD_BYTES, table.pageUpdate);
    if (!status.isOk()) {
      return status;
    }
    status = HeapPage.initialize(table.pageUpdate.writablePayload());
    if (!status.isOk()) {
      store.cancel(table.pageUpdate);
      return status;
    }
    table.pageUpdate.writablePayload().position(PageCodec.MAX_PAYLOAD_BYTES);
    status = store.commit(
        table.pageUpdate,
        BOOTSTRAP_TRANSACTION_ID,
        BOOTSTRAP_TRANSACTION_ID,
        1);
    if (status.isOk()) {
      status = store.flush();
    }
    if (status.isOk()) {
      result.set(table);
    }
    return status;
  }

  public static StatusCode open(
      SinglePageStore store,
      SinglePageTableOpenResult result) {
    if (store == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    SinglePageTable table = new SinglePageTable(store);
    StatusCode status = store.read(table.pageRead);
    if (status.isOk()) {
      status = HeapPage.validate(table.pageRead.payload());
    }
    if (status.isOk()) {
      result.set(table);
    }
    return status;
  }

  public StatusCode insert(
      long transactionId,
      ByteBuffer row,
      HeapInsertResult result) {
    if (transactionId <= BOOTSTRAP_TRANSACTION_ID || row == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int rowBytes = row.remaining();
    StatusCode status = pageStore.beginUpdateFromCurrent(pageUpdate);
    if (!status.isOk()) {
      return status;
    }
    status = HeapPage.insert(pageUpdate.writablePayload(), row, result);
    if (!status.isOk()) {
      pageStore.cancel(pageUpdate);
      return status;
    }
    status = pageStore.commit(pageUpdate, transactionId, transactionId, 1);
    if (status.isOk()) {
      copiedRowBytes += rowBytes;
    }
    return status;
  }

  public StatusCode fetch(int rowId, HeapRowResult result) {
    StatusCode status = pageStore.read(pageRead);
    if (!status.isOk()) {
      return status;
    }
    return HeapPage.fetch(pageRead.payload(), rowId, result);
  }

  public StatusCode next(HeapScanCursor cursor, HeapRowResult result) {
    StatusCode status = pageStore.read(pageRead);
    if (!status.isOk()) {
      return status;
    }
    return HeapPage.next(pageRead.payload(), cursor, result);
  }

  public int rowCount() {
    StatusCode status = pageStore.read(pageRead);
    return status.isOk() ? HeapPage.rowCount(pageRead.payload()) : -1;
  }

  public long copiedRowBytes() {
    return copiedRowBytes;
  }

  public StatusCode flush() {
    return pageStore.flush();
  }

  public StatusCode close() {
    return pageStore.close();
  }
}
