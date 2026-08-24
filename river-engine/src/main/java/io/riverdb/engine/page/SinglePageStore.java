package io.riverdb.engine.page;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.page.PageCodec;
import io.riverdb.format.page.PageHeader;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalAppendResult;
import io.riverdb.wal.local.LocalWalReadResult;
import io.riverdb.wal.local.LocalWalReservation;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** First WAL-protected page: double-buffered staging, flush, reopen, and recovery. */
public final class SinglePageStore {
  public static final String FILE_NAME = "river.page.1";
  public static final int WAL_FORMAT_ID = 1001;
  public static final int WAL_FORMAT_VERSION = 1;
  public static final long PAGE_ID = 1;
  public static final long PAGE_GENERATION = 1;

  final DurableFile file;
  final LocalWal wal;
  final DatabaseIncarnation database;
  final WalGeneration walGeneration;
  final CRC32C checksum = new CRC32C();
  final IoResult ioResult = new IoResult();
  final FileSizeResult fileSizeResult = new FileSizeResult();
  final PageHeader pageHeader = new PageHeader();
  private final LocalWalReservation walReservation = new LocalWalReservation();
  private final LocalWalAppendResult walAppendResult = new LocalWalAppendResult();
  final LocalWalReadResult walReadResult = new LocalWalReadResult();
  ByteBuffer currentPage = ByteBuffer.allocateDirect(PageCodec.PAGE_BYTES);
  private ByteBuffer currentPayload = payloadView(currentPage);
  private ByteBuffer stagingPage = ByteBuffer.allocateDirect(PageCodec.PAGE_BYTES);
  private ByteBuffer stagingPayload = payloadView(stagingPage);
  private long nextUpdateToken = 1;
  private long activeUpdateToken;
  long recordEnd;
  long copiedPayloadBytes;
  int payloadBytes;
  private boolean dirty;
  private boolean closed;

  private SinglePageStore(
      DurableFile durableFile,
      LocalWal localWal,
      DatabaseIncarnation databaseIncarnation,
      WalGeneration localWalGeneration) {
    file = durableFile;
    wal = localWal;
    database = databaseIncarnation;
    walGeneration = localWalGeneration;
  }

  public static StatusCode create(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration walGeneration,
      SinglePageStoreOpenResult result) {
    if (!validInput(directory, wal, database, walGeneration, result)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    DirectoryOperationResult operation = new DirectoryOperationResult();
    StatusCode status = directory.createFile(FILE_NAME, operation);
    if (!status.isOk()) {
      return status;
    }
    SinglePageStore store = new SinglePageStore(
        operation.file(), wal, database, walGeneration);
    PageUpdate update = new PageUpdate();
    status = store.beginUpdate(0, update);
    if (status.isOk()) {
      status = store.commit(update);
    }
    if (status.isOk()) {
      status = store.flush();
    }
    if (status.isOk()) {
      DirectoryOperationResult forceResult = new DirectoryOperationResult();
      status = directory.force(forceResult);
    }
    if (!status.isOk()) {
      store.file.close();
      return status;
    }
    result.set(store);
    return StatusCode.OK;
  }

  public static StatusCode open(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration walGeneration,
      SinglePageStoreOpenResult result) {
    if (!validInput(directory, wal, database, walGeneration, result)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    DirectoryOperationResult operation = new DirectoryOperationResult();
    StatusCode status = directory.reopen(FILE_NAME, operation);
    boolean created = false;
    if (status == StatusCode.CONFLICT) {
      status = directory.createFile(FILE_NAME, operation);
      created = status.isOk();
    }
    if (!status.isOk()) {
      return status;
    }
    SinglePageStore store = new SinglePageStore(
        operation.file(), wal, database, walGeneration);
    status = store.loadOrRecover();
    if (status.isOk() && created) {
      DirectoryOperationResult forceResult = new DirectoryOperationResult();
      status = directory.force(forceResult);
    }
    if (!status.isOk()) {
      store.file.close();
      return status;
    }
    result.set(store);
    return StatusCode.OK;
  }

  public StatusCode beginUpdate(int bytes, PageUpdate update) {
    if (update == null || bytes < 0 || bytes > PageCodec.MAX_PAYLOAD_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode admission = admission();
    if (!admission.isOk()) {
      return admission;
    }
    if (activeUpdateToken != 0) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    stagingPage.clear();
    stagingPayload.clear();
    stagingPayload.limit(bytes);
    long token = nextUpdateToken++;
    StatusCode status = update.claim(this, token, stagingPayload, bytes);
    if (status.isOk()) {
      activeUpdateToken = token;
    }
    return status;
  }

  public StatusCode beginUpdateFromCurrent(PageUpdate update) {
    StatusCode status = beginUpdate(payloadBytes, update);
    if (!status.isOk()) {
      return status;
    }
    currentPayload.clear();
    currentPayload.limit(payloadBytes);
    stagingPayload.put(currentPayload);
    copiedPayloadBytes += payloadBytes;
    return StatusCode.OK;
  }

  public StatusCode commit(PageUpdate update) {
    return commit(update, 0, 0, 0);
  }

  public StatusCode commit(
      PageUpdate update,
      long transactionId,
      long commitSequence,
      int decisionCode) {
    if (update == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode admission = admission();
    if (!admission.isOk()) {
      return admission;
    }
    if (!update.isOwnedBy(this, activeUpdateToken)) {
      return StatusCode.CONFLICT;
    }
    if (update.writablePayload().position() != update.payloadBytes()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if ((decisionCode == 0 && commitSequence != 0)
        || (decisionCode == 1 && (transactionId <= 0 || commitSequence <= 0))
        || (decisionCode == 2 && (transactionId <= 0 || commitSequence != 0))
        || decisionCode < 0
        || decisionCode > 2) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = wal.reserve(PageCodec.PAGE_BYTES, walReservation);
    if (!status.isOk()) {
      return status;
    }
    status = PageCodec.encode(
        database,
        walGeneration,
        PAGE_ID,
        PAGE_GENERATION,
        walReservation.recordStartOffset(),
        walReservation.recordEndOffset(),
        update.payloadBytes(),
        stagingPage,
        checksum);
    if (!status.isOk()) {
      wal.cancel(walReservation);
      return status;
    }
    walReservation.writablePayload().put(stagingPage);
    copiedPayloadBytes += PageCodec.PAGE_BYTES;
    status = wal.publish(
        walReservation,
        transactionId,
        commitSequence,
        decisionCode,
        WAL_FORMAT_ID,
        WAL_FORMAT_VERSION,
        walAppendResult);
    if (!status.isOk()) {
      return status;
    }

    ByteBuffer previousPage = currentPage;
    currentPage = stagingPage;
    stagingPage = previousPage;
    ByteBuffer previousPayload = currentPayload;
    currentPayload = stagingPayload;
    stagingPayload = previousPayload;
    payloadBytes = update.payloadBytes();
    recordEnd = walAppendResult.endOffset();
    dirty = true;
    activeUpdateToken = 0;
    update.complete();
    return StatusCode.OK;
  }

  public StatusCode cancel(PageUpdate update) {
    if (update == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode admission = admission();
    if (!admission.isOk()) {
      return admission;
    }
    if (!update.isOwnedBy(this, activeUpdateToken)) {
      return StatusCode.CONFLICT;
    }
    activeUpdateToken = 0;
    update.complete();
    return StatusCode.OK;
  }

  public StatusCode flush() {
    StatusCode admission = admission();
    if (!admission.isOk()) {
      return admission;
    }
    if (!dirty) {
      return StatusCode.OK;
    }
    if (wal.durableEnd() < recordEnd) {
      return StatusCode.RETRY;
    }
    currentPage.position(0);
    currentPage.limit(PageCodec.PAGE_BYTES);
    StatusCode status = file.write(0, currentPage, ioResult);
    if (status.isOk() && ioResult.bytesTransferred() != PageCodec.PAGE_BYTES) {
      status = StatusCode.IO_FAILURE;
    }
    if (status.isOk()) {
      status = file.truncate(PageCodec.PAGE_BYTES);
    }
    if (status.isOk()) {
      status = file.force(ForceMode.CONTENT_AND_METADATA);
    }
    if (status.isOk()) {
      dirty = false;
    }
    return status;
  }

  public StatusCode read(PageReadResult result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode admission = admission();
    if (!admission.isOk()) {
      return admission;
    }
    currentPayload.clear();
    currentPayload.limit(payloadBytes);
    result.set(currentPayload, recordEnd);
    return StatusCode.OK;
  }

  public long copiedPayloadBytes() {
    return copiedPayloadBytes;
  }

  public long nextCommitSequence() {
    return wal.nextCommitSequence();
  }

  public boolean isDirty() {
    return dirty;
  }

  public StatusCode close() {
    if (closed) {
      return StatusCode.CLOSED;
    }
    if (dirty || activeUpdateToken != 0) {
      return StatusCode.CONFLICT;
    }
    closed = true;
    return file.close();
  }

  private StatusCode loadOrRecover() {
    return SinglePageStoreRecovery.load(this);
  }

  private StatusCode admission() {
    return closed ? StatusCode.CLOSED : StatusCode.OK;
  }

  private static boolean validInput(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration walGeneration,
      SinglePageStoreOpenResult result) {
    return directory != null
        && wal != null
        && database != null
        && database.isValid()
        && walGeneration != null
        && walGeneration.isValid()
        && database.equals(wal.databaseIncarnation())
        && walGeneration.equals(wal.walGeneration())
        && result != null;
  }

  private static ByteBuffer payloadView(ByteBuffer page) {
    page.position(PageCodec.HEADER_BYTES);
    ByteBuffer payload = page.slice();
    page.clear();
    return payload;
  }
}
