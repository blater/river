package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.wal.WalFileHeader;
import io.riverdb.format.wal.WalFileHeaderCodec;
import io.riverdb.format.wal.WalFileHeaderDecodeResult;
import io.riverdb.format.wal.WalRecordCodec;
import io.riverdb.format.wal.WalRecordHeader;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/**
 * Single-owner synchronous WAL with reusable provider storage and globally ordered commit CSNs.
 */
public final class LocalWal {
  public static final String FILE_NAME = "river.wal";

  private DurableFile file;
  private final DatabaseIncarnation databaseIncarnation;
  private WalGeneration walGeneration;
  private String fileName;
  private final ByteBuffer appendRecord;
  private final ByteBuffer appendPayload;
  private final ByteBuffer readRecord;
  private final ByteBuffer readPayload;
  private final IoResult ioResult = new IoResult();
  private final FileSizeResult fileSizeResult = new FileSizeResult();
  private final WalRecordHeader recoveryHeader = new WalRecordHeader();
  private final CRC32C checksum = new CRC32C();
  private long tailEnd = WalFileHeaderCodec.HEADER_BYTES;
  private long nextJournalSequence = 1;
  private long nextReservationToken = 1;
  private long lastCommitSequence;
  private long maximumTransactionId = 1;
  private long activeReservationToken;
  private long copiedPayloadBytes;
  private boolean failed;
  private boolean closed;

  private LocalWal(
      DurableFile file,
      DatabaseIncarnation database,
      WalGeneration generation,
      String openedFileName) {
    this.file = file;
    databaseIncarnation = database;
    walGeneration = generation;
    fileName = openedFileName;
    int capacity = WalRecordCodec.HEADER_BYTES + WalRecordCodec.MAX_PAYLOAD_BYTES;
    appendRecord = ByteBuffer.allocateDirect(capacity);
    appendPayload = payloadView(appendRecord);
    readRecord = ByteBuffer.allocateDirect(capacity);
    readPayload = payloadView(readRecord);
  }

  public static StatusCode open(
      DurableDirectory directory,
      DatabaseIncarnation databaseIncarnation,
      WalGeneration walGeneration,
      LocalWalOpenResult result) {
    return open(
        directory, FILE_NAME, databaseIncarnation, walGeneration, true, false, result);
  }

  public static StatusCode create(
      DurableDirectory directory,
      DatabaseIncarnation databaseIncarnation,
      WalGeneration walGeneration,
      LocalWalOpenResult result) {
    return open(
        directory, FILE_NAME, databaseIncarnation, walGeneration, false, true, result);
  }

  public static StatusCode openExisting(
      DurableDirectory directory,
      DatabaseIncarnation databaseIncarnation,
      WalGeneration walGeneration,
      LocalWalOpenResult result) {
    return open(
        directory, FILE_NAME, databaseIncarnation, walGeneration, false, false, result);
  }

  public static StatusCode createNamed(
      DurableDirectory directory,
      String fileName,
      DatabaseIncarnation databaseIncarnation,
      WalGeneration walGeneration,
      LocalWalOpenResult result) {
    return open(
        directory, fileName, databaseIncarnation, walGeneration, false, true, result);
  }

  public static StatusCode openExistingNamed(
      DurableDirectory directory,
      String fileName,
      DatabaseIncarnation databaseIncarnation,
      WalGeneration walGeneration,
      LocalWalOpenResult result) {
    return open(
        directory, fileName, databaseIncarnation, walGeneration, false, false, result);
  }

  private static StatusCode open(
      DurableDirectory directory,
      String fileName,
      DatabaseIncarnation databaseIncarnation,
      WalGeneration walGeneration,
      boolean createWhenMissing,
      boolean requireCreate,
      LocalWalOpenResult result) {
    if (directory == null
        || fileName == null
        || fileName.isEmpty()
        || databaseIncarnation == null
        || !databaseIncarnation.isValid()
        || walGeneration == null
        || !walGeneration.isValid()
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    DirectoryOperationResult operation = new DirectoryOperationResult();
    StatusCode status = requireCreate
        ? directory.createFile(fileName, operation)
        : directory.reopen(fileName, operation);
    boolean created = false;
    if (requireCreate && status.isOk()) {
      created = true;
    } else if (status == StatusCode.CONFLICT && createWhenMissing) {
      status = directory.createFile(fileName, operation);
      created = status.isOk();
    }
    if (!status.isOk()) {
      if (operation.file() != null) {
        operation.file().close();
      }
      return status;
    }

    LocalWal wal = new LocalWal(
        operation.file(), databaseIncarnation, walGeneration, fileName);
    status = created ? wal.initializeFile(directory) : wal.recoverValidTail();
    if (!status.isOk()) {
      wal.file.close();
      return status;
    }
    result.set(wal);
    return StatusCode.OK;
  }

  public long tailEnd() {
    return tailEnd;
  }

  public DatabaseIncarnation databaseIncarnation() {
    return databaseIncarnation;
  }

  public WalGeneration walGeneration() {
    return walGeneration;
  }

  public String fileName() {
    return fileName;
  }

  public long nextJournalSequence() {
    return nextJournalSequence;
  }

  public long nextCommitSequence() {
    return lastCommitSequence + 1;
  }

  public long currentCommitSequence() {
    return lastCommitSequence;
  }

  public long nextTransactionId() {
    return maximumTransactionId == Long.MAX_VALUE ? 0 : maximumTransactionId + 1;
  }

  public long maximumTransactionId() {
    return maximumTransactionId;
  }

  public StatusCode adoptCheckpointState(long commitSequence, long transactionId) {
    if (commitSequence <= 0
        || transactionId <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (lastCommitSequence != 0 && lastCommitSequence <= commitSequence) {
      return StatusCode.CORRUPTION;
    }
    if (lastCommitSequence == 0) {
      lastCommitSequence = commitSequence;
    }
    maximumTransactionId = Math.max(maximumTransactionId, transactionId);
    return StatusCode.OK;
  }

  public static String generationFileName(WalGeneration generation) {
    return generation == null || !generation.isValid()
        ? "" : FILE_NAME + "." + generation.value();
  }

  /** Switches this live provider to a forced empty next-generation WAL file. */
  public StatusCode rotate(
      DurableDirectory directory,
      String nextFileName,
      WalGeneration nextGeneration,
      long checkpointTransactionId) {
    if (directory == null
        || nextFileName == null
        || nextFileName.isEmpty()
        || nextGeneration == null
        || !nextGeneration.isValid()
        || nextGeneration.value() <= walGeneration.value()
        || checkpointTransactionId <= 0
        || activeReservationToken != 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    LocalWalOpenResult opened = new LocalWalOpenResult();
    StatusCode status = createNamed(
        directory, nextFileName, databaseIncarnation, nextGeneration, opened);
    if (!status.isOk()) {
      return status;
    }
    LocalWal replacement = opened.wal();
    replacement.lastCommitSequence = lastCommitSequence;
    replacement.maximumTransactionId = Math.max(maximumTransactionId, checkpointTransactionId);
    DurableFile previousFile = file;
    file = replacement.file;
    fileName = nextFileName;
    walGeneration = nextGeneration;
    tailEnd = replacement.tailEnd;
    nextJournalSequence = replacement.nextJournalSequence;
    nextReservationToken = replacement.nextReservationToken;
    lastCommitSequence = replacement.lastCommitSequence;
    maximumTransactionId = replacement.maximumTransactionId;
    copiedPayloadBytes += replacement.copiedPayloadBytes;
    return previousFile.close();
  }

  /** Exclusive local byte end known forced by this synchronous provider. */
  public long durableEnd() {
    return tailEnd;
  }

  /** Explicit River-side payload copies; device transfer bytes are not copies. */
  public long copiedPayloadBytes() {
    return copiedPayloadBytes;
  }

  public StatusCode reserve(int payloadBytes, LocalWalReservation reservation) {
    if (reservation == null || payloadBytes < 0
        || payloadBytes > WalRecordCodec.MAX_PAYLOAD_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode admission = admission();
    if (!admission.isOk()) {
      return admission;
    }
    if (activeReservationToken != 0) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    appendRecord.clear();
    appendPayload.clear();
    appendPayload.limit(payloadBytes);
    long token = nextReservationToken++;
    long endOffset = tailEnd + WalRecordCodec.encodedBytes(payloadBytes);
    StatusCode status = reservation.claim(
        this,
        token,
        appendPayload,
        payloadBytes,
        tailEnd,
        endOffset);
    if (status.isOk()) {
      activeReservationToken = token;
    }
    return status;
  }

  public StatusCode publish(
      LocalWalReservation reservation,
      long transactionId,
      long commitSequence,
      int decisionCode,
      int formatId,
      int formatVersion,
      LocalWalAppendResult result) {
    if (reservation == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode admission = admission();
    if (!admission.isOk()) {
      return admission;
    }
    if (!reservation.isOwnedBy(this, activeReservationToken)) {
      return StatusCode.CONFLICT;
    }
    if (reservation.writablePayload().position() != reservation.payloadBytes()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!validDecision(transactionId, commitSequence, decisionCode)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }

    int recordBytes = WalRecordCodec.encodedBytes(reservation.payloadBytes());
    StatusCode status = WalRecordCodec.encodeReserved(
        nextJournalSequence,
        transactionId,
        commitSequence,
        decisionCode,
        formatId,
        formatVersion,
        reservation.payloadBytes(),
        appendRecord,
        checksum);
    if (!status.isOk()) {
      return status;
    }
    status = file.write(tailEnd, appendRecord, ioResult);
    if (status.isOk() && ioResult.bytesTransferred() != recordBytes) {
      status = StatusCode.IO_FAILURE;
    }
    if (status.isOk()) {
      status = file.force(ForceMode.CONTENT_AND_METADATA);
    }
    if (!status.isOk()) {
      failed = true;
      activeReservationToken = 0;
      reservation.complete();
      return status;
    }

    long start = tailEnd;
    tailEnd += recordBytes;
    result.set(start, tailEnd, nextJournalSequence);
    nextJournalSequence++;
    if (decisionCode == 1) {
      lastCommitSequence = commitSequence;
    }
    if (transactionId > maximumTransactionId) {
      maximumTransactionId = transactionId;
    }
    activeReservationToken = 0;
    reservation.complete();
    return StatusCode.OK;
  }

  public StatusCode cancel(LocalWalReservation reservation) {
    if (reservation == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode admission = admission();
    if (!admission.isOk()) {
      return admission;
    }
    if (!reservation.isOwnedBy(this, activeReservationToken)) {
      return StatusCode.CONFLICT;
    }
    activeReservationToken = 0;
    reservation.complete();
    return StatusCode.OK;
  }

  public StatusCode read(long offset, LocalWalReadResult result) {
    if (result == null
        || offset < WalFileHeaderCodec.HEADER_BYTES
        || offset >= tailEnd) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode admission = admission();
    if (!admission.isOk()) {
      return admission;
    }
    readRecord.clear();
    readRecord.limit(WalRecordCodec.HEADER_BYTES);
    StatusCode status = readExact(offset, readRecord);
    if (!status.isOk()) {
      return status;
    }
    readRecord.flip();
    status = WalRecordCodec.decodeHeader(readRecord, result.header());
    if (!status.isOk() || offset + result.header().totalBytes() > tailEnd) {
      return status.isOk() ? StatusCode.CORRUPTION : status;
    }

    readRecord.clear();
    readRecord.limit(result.header().totalBytes());
    status = readExact(offset, readRecord);
    if (!status.isOk()) {
      result.reset();
      return status;
    }
    readRecord.flip();
    status = WalRecordCodec.validate(readRecord, result.header(), checksum);
    if (!status.isOk()) {
      return status;
    }
    readPayload.clear();
    readPayload.limit(result.header().payloadBytes());
    result.set(offset + result.header().totalBytes(), readPayload);
    return StatusCode.OK;
  }

  public StatusCode close() {
    if (closed) {
      return StatusCode.CLOSED;
    }
    closed = true;
    return file.close();
  }

  private StatusCode recoverValidTail() {
    StatusCode status = file.size(fileSizeResult);
    if (!status.isOk()) {
      return status;
    }
    long fileBytes = fileSizeResult.sizeBytes();
    if (fileBytes < WalFileHeaderCodec.HEADER_BYTES) {
      return StatusCode.CORRUPTION;
    }
    ByteBuffer fileHeaderBytes = ByteBuffer.allocate(WalFileHeaderCodec.HEADER_BYTES);
    status = readExact(0, fileHeaderBytes);
    if (!status.isOk()) {
      return status;
    }
    fileHeaderBytes.flip();
    WalFileHeaderDecodeResult fileHeader = new WalFileHeaderDecodeResult();
    status = WalFileHeaderCodec.decode(fileHeaderBytes, fileHeader);
    if (!status.isOk()) {
      return status;
    }
    if (!databaseIncarnation.equals(fileHeader.header().databaseIncarnation())
        || !walGeneration.equals(fileHeader.header().walGeneration())) {
      return StatusCode.FENCED;
    }

    long offset = WalFileHeaderCodec.HEADER_BYTES;
    long expectedSequence = 1;
    while (offset < fileBytes) {
      if (fileBytes - offset < WalRecordCodec.HEADER_BYTES) {
        return truncateTail(offset, expectedSequence);
      }
      readRecord.clear();
      readRecord.limit(WalRecordCodec.HEADER_BYTES);
      status = readExact(offset, readRecord);
      if (!status.isOk()) {
        return status;
      }
      readRecord.flip();
      status = WalRecordCodec.decodeHeader(readRecord, recoveryHeader);
      if (!status.isOk() || recoveryHeader.journalSequence() != expectedSequence) {
        return StatusCode.CORRUPTION;
      }
      if (recoveryHeader.totalBytes() > fileBytes - offset) {
        return truncateTail(offset, expectedSequence);
      }
      readRecord.clear();
      readRecord.limit(recoveryHeader.totalBytes());
      status = readExact(offset, readRecord);
      if (!status.isOk()) {
        return status;
      }
      readRecord.flip();
      status = WalRecordCodec.validate(readRecord, recoveryHeader, checksum);
      if (!status.isOk()) {
        return StatusCode.CORRUPTION;
      }
      if (!validDecision(
          recoveryHeader.transactionId(),
          recoveryHeader.commitSequence(),
          recoveryHeader.decisionCode())) {
        return StatusCode.CORRUPTION;
      }
      if (recoveryHeader.decisionCode() == 1) {
        lastCommitSequence = recoveryHeader.commitSequence();
      }
      if (recoveryHeader.transactionId() > maximumTransactionId) {
        maximumTransactionId = recoveryHeader.transactionId();
      }
      offset += recoveryHeader.totalBytes();
      expectedSequence++;
    }
    tailEnd = offset;
    nextJournalSequence = expectedSequence;
    return StatusCode.OK;
  }

  private StatusCode initializeFile(DurableDirectory directory) {
    ByteBuffer header = ByteBuffer.allocate(WalFileHeaderCodec.HEADER_BYTES);
    StatusCode status = WalFileHeaderCodec.encode(
        new WalFileHeader(databaseIncarnation, walGeneration),
        header);
    if (!status.isOk()) {
      return status;
    }
    header.flip();
    status = file.write(0, header, ioResult);
    if (status.isOk() && ioResult.bytesTransferred() != WalFileHeaderCodec.HEADER_BYTES) {
      status = StatusCode.IO_FAILURE;
    }
    if (status.isOk()) {
      status = file.force(ForceMode.CONTENT_AND_METADATA);
    }
    if (status.isOk()) {
      DirectoryOperationResult forceResult = new DirectoryOperationResult();
      status = directory.force(forceResult);
    }
    return status;
  }

  private StatusCode truncateTail(long validEnd, long sequence) {
    StatusCode status = file.truncate(validEnd);
    if (status.isOk()) {
      status = file.force(ForceMode.CONTENT_AND_METADATA);
    }
    if (status.isOk()) {
      tailEnd = validEnd;
      nextJournalSequence = sequence;
    }
    return status;
  }

  private StatusCode readExact(long offset, ByteBuffer target) {
    int expected = target.remaining();
    StatusCode status = file.read(offset, target, ioResult);
    if (status.isOk() && ioResult.bytesTransferred() != expected) {
      return StatusCode.IO_FAILURE;
    }
    return status;
  }

  private StatusCode admission() {
    if (closed) {
      return StatusCode.CLOSED;
    }
    if (failed) {
      return StatusCode.FENCED;
    }
    return StatusCode.OK;
  }

  private boolean validDecision(
      long transactionId,
      long commitSequence,
      int decisionCode) {
    return switch (decisionCode) {
      case 0 -> commitSequence == 0;
      case 1 -> transactionId > 0 && commitSequence > lastCommitSequence;
      case 2 -> transactionId > 0 && commitSequence == 0;
      default -> false;
    };
  }

  private static ByteBuffer payloadView(ByteBuffer record) {
    record.position(WalRecordCodec.HEADER_BYTES);
    ByteBuffer payload = record.slice();
    record.clear();
    return payload;
  }
}
