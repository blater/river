package io.riverdb.engine.page;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.page.PageCodec;
import io.riverdb.format.wal.WalFileHeaderCodec;

/** Loads the durable page or reconstructs it from the local WAL tail. */
final class SinglePageStoreRecovery {
  private SinglePageStoreRecovery() { }

  static StatusCode load(SinglePageStore store) {
    StatusCode status = store.file.size(store.fileSizeResult);
    if (!status.isOk()) return status;
    boolean valid = store.fileSizeResult.sizeBytes() == PageCodec.PAGE_BYTES;
    if (valid) {
      store.currentPage.clear();
      status = store.file.read(0, store.currentPage, store.ioResult);
      valid = status.isOk() && store.ioResult.bytesTransferred() == PageCodec.PAGE_BYTES;
      if (valid) {
        store.currentPage.flip();
        valid = PageCodec.validate(store.currentPage, store.pageHeader, store.checksum).isOk();
        if (valid && (store.pageHeader.databaseHigh() != store.database.high()
            || store.pageHeader.databaseLow() != store.database.low()
            || store.pageHeader.walGeneration() != store.walGeneration.value()
            || store.pageHeader.pageId() != SinglePageStore.PAGE_ID
            || store.pageHeader.pageGeneration() != SinglePageStore.PAGE_GENERATION)) return StatusCode.FENCED;
        valid = valid && store.pageHeader.recordEnd() <= store.wal.durableEnd();
      }
    }
    if (valid) {
      store.payloadBytes = store.pageHeader.payloadBytes();
      store.recordEnd = store.pageHeader.recordEnd();
      store.currentPage.position(0); store.currentPage.limit(PageCodec.PAGE_BYTES);
      return recover(store, store.recordEnd, false);
    }
    return recover(store, 0, true);
  }

  private static StatusCode recover(SinglePageStore store, long minimumRecordEnd,
      boolean recoveryRequired) {
    long offset = WalFileHeaderCodec.HEADER_BYTES, latestPageOffset = 0;
    while (offset < store.wal.tailEnd()) {
      StatusCode status = store.wal.read(offset, store.walReadResult);
      if (!status.isOk()) return recoveryRequired ? status : StatusCode.OK;
      if (store.walReadResult.header().formatId() == SinglePageStore.WAL_FORMAT_ID
          && store.walReadResult.header().formatVersion() == SinglePageStore.WAL_FORMAT_VERSION
          && store.walReadResult.header().decisionCode() != 2
          && store.walReadResult.header().payloadBytes() == PageCodec.PAGE_BYTES) {
        status = PageCodec.validate(store.walReadResult.payload(), store.pageHeader, store.checksum);
        if (!status.isOk()) return recoveryRequired ? status : StatusCode.OK;
        if (store.pageHeader.databaseHigh() == store.database.high()
            && store.pageHeader.databaseLow() == store.database.low()
            && store.pageHeader.walGeneration() == store.walGeneration.value()
            && store.pageHeader.pageId() == SinglePageStore.PAGE_ID
            && store.pageHeader.pageGeneration() == SinglePageStore.PAGE_GENERATION
            && store.pageHeader.recordStart() == offset
            && store.pageHeader.recordEnd() == store.walReadResult.nextOffset()
            && store.pageHeader.recordEnd() > minimumRecordEnd) latestPageOffset = offset;
      }
      offset = store.walReadResult.nextOffset();
    }
    if (latestPageOffset == 0) return recoveryRequired ? StatusCode.CORRUPTION : StatusCode.OK;
    StatusCode status = store.wal.read(latestPageOffset, store.walReadResult);
    if (!status.isOk()) return status;
    status = PageCodec.validate(store.walReadResult.payload(), store.pageHeader, store.checksum);
    if (!status.isOk()) return status;
    store.currentPage.clear(); store.currentPage.put(store.walReadResult.payload());
    store.copiedPayloadBytes += PageCodec.PAGE_BYTES;
    store.payloadBytes = store.pageHeader.payloadBytes(); store.recordEnd = store.pageHeader.recordEnd();
    store.currentPage.position(0); store.currentPage.limit(PageCodec.PAGE_BYTES);
    status = store.file.truncate(PageCodec.PAGE_BYTES);
    if (status.isOk()) status = store.file.write(0, store.currentPage, store.ioResult);
    if (status.isOk() && store.ioResult.bytesTransferred() != PageCodec.PAGE_BYTES) status = StatusCode.IO_FAILURE;
    if (status.isOk()) status = store.file.force(io.riverdb.platform.file.ForceMode.CONTENT_AND_METADATA);
    return status;
  }
}
