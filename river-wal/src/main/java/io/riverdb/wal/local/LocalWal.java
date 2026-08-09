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

/** Single-owner, synchronous local WAL with valid-prefix recovery. */
public final class LocalWal {
  public static final String FILE_NAME = "river.wal";

  private final DurableFile file;
  private final DatabaseIncarnation databaseIncarnation;
  private final WalGeneration walGeneration;
  private long tailEnd = WalFileHeaderCodec.HEADER_BYTES;
  private long nextJournalSequence = 1;
  private boolean failed;
  private boolean closed;

  private LocalWal(
      DurableFile file,
      DatabaseIncarnation database,
      WalGeneration generation) {
    this.file = file;
    databaseIncarnation = database;
    walGeneration = generation;
  }

  public static StatusCode open(
      DurableDirectory directory,
      DatabaseIncarnation databaseIncarnation,
      WalGeneration walGeneration,
      LocalWalOpenResult result) {
    if (directory == null
        || databaseIncarnation == null
        || !databaseIncarnation.isValid()
        || walGeneration == null
        || !walGeneration.isValid()
        || result == null) {
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
      if (operation.file() != null) {
        operation.file().close();
      }
      return status;
    }

    LocalWal wal = new LocalWal(operation.file(), databaseIncarnation, walGeneration);
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

  public long nextJournalSequence() {
    return nextJournalSequence;
  }

  public StatusCode append(
      long transactionId,
      long commitSequence,
      int decisionCode,
      int formatId,
      int formatVersion,
      ByteBuffer payload,
      LocalWalAppendResult result) {
    if (payload == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode admission = admission();
    if (!admission.isOk()) {
      return admission;
    }
    int recordBytes = WalRecordCodec.encodedBytes(payload.remaining());
    if (recordBytes < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }

    ByteBuffer encoded = ByteBuffer.allocate(recordBytes);
    StatusCode status = WalRecordCodec.encode(
        nextJournalSequence,
        transactionId,
        commitSequence,
        decisionCode,
        formatId,
        formatVersion,
        payload,
        encoded);
    if (!status.isOk()) {
      return status;
    }
    encoded.flip();
    IoResult io = new IoResult();
    status = file.write(tailEnd, encoded, io);
    if (status.isOk() && io.bytesTransferred() != recordBytes) {
      status = StatusCode.IO_FAILURE;
    }
    if (status.isOk()) {
      status = file.force(ForceMode.CONTENT_AND_METADATA);
    }
    if (!status.isOk()) {
      failed = true;
      return status;
    }

    long start = tailEnd;
    tailEnd += recordBytes;
    result.set(start, tailEnd, nextJournalSequence);
    nextJournalSequence++;
    return StatusCode.OK;
  }

  public StatusCode read(
      long offset,
      ByteBuffer payloadTarget,
      LocalWalReadResult result) {
    if (payloadTarget == null
        || result == null
        || offset < WalFileHeaderCodec.HEADER_BYTES
        || offset >= tailEnd) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode admission = admission();
    if (!admission.isOk()) {
      return admission;
    }
    ByteBuffer headerBytes = ByteBuffer.allocate(WalRecordCodec.HEADER_BYTES);
    StatusCode status = readExact(offset, headerBytes);
    if (!status.isOk()) {
      return status;
    }
    headerBytes.flip();
    status = WalRecordCodec.decodeHeader(headerBytes, result.header());
    if (!status.isOk()) {
      return status;
    }
    if (offset + result.header().totalBytes() > tailEnd
        || payloadTarget.remaining() < result.header().payloadBytes()) {
      result.reset();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }

    ByteBuffer record = ByteBuffer.allocate(result.header().totalBytes());
    status = readExact(offset, record);
    if (!status.isOk()) {
      result.reset();
      return status;
    }
    record.flip();
    status = WalRecordCodec.validate(record, result.header());
    if (!status.isOk()) {
      return status;
    }
    status = WalRecordCodec.copyPayload(record, result.header(), payloadTarget);
    if (status.isOk()) {
      result.setNextOffset(offset + result.header().totalBytes());
    }
    return status;
  }

  public StatusCode close() {
    if (closed) {
      return StatusCode.CLOSED;
    }
    closed = true;
    return file.close();
  }

  private StatusCode recoverValidTail() {
    FileSizeResult size = new FileSizeResult();
    StatusCode status = file.size(size);
    if (!status.isOk()) {
      return status;
    }
    long fileBytes = size.sizeBytes();
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
    WalRecordHeader header = new WalRecordHeader();
    while (offset < fileBytes) {
      if (fileBytes - offset < WalRecordCodec.HEADER_BYTES) {
        return truncateTail(offset, expectedSequence);
      }
      ByteBuffer headerBytes = ByteBuffer.allocate(WalRecordCodec.HEADER_BYTES);
      status = readExact(offset, headerBytes);
      if (!status.isOk()) {
        return status;
      }
      headerBytes.flip();
      status = WalRecordCodec.decodeHeader(headerBytes, header);
      if (!status.isOk() || header.journalSequence() != expectedSequence) {
        return StatusCode.CORRUPTION;
      }
      if (header.totalBytes() > fileBytes - offset) {
        return truncateTail(offset, expectedSequence);
      }

      ByteBuffer record = ByteBuffer.allocate(header.totalBytes());
      status = readExact(offset, record);
      if (!status.isOk()) {
        return status;
      }
      record.flip();
      status = WalRecordCodec.validate(record, header);
      if (!status.isOk()) {
        return StatusCode.CORRUPTION;
      }
      offset += header.totalBytes();
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
    IoResult io = new IoResult();
    status = file.write(0, header, io);
    if (status.isOk() && io.bytesTransferred() != WalFileHeaderCodec.HEADER_BYTES) {
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
    IoResult io = new IoResult();
    int expected = target.remaining();
    StatusCode status = file.read(offset, target, io);
    if (status.isOk() && io.bytesTransferred() != expected) {
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
}
