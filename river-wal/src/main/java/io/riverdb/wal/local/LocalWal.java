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
  public static final int MAX_PENDING_RECORDS = 16;

  private DurableFile file;
  private final DatabaseIncarnation databaseIncarnation;
  private WalGeneration walGeneration;
  private String fileName;
  private final ByteBuffer[] appendRecords = new ByteBuffer[MAX_PENDING_RECORDS];
  private final ByteBuffer[] appendPayloads = new ByteBuffer[MAX_PENDING_RECORDS];
  private final long[] pendingEnds = new long[MAX_PENDING_RECORDS];
  private final ByteBuffer readRecord;
  private final ByteBuffer readPayload;
  private final IoResult ioResult = new IoResult();
  private final FileSizeResult fileSizeResult = new FileSizeResult();
  private final WalRecordHeader recoveryHeader = new WalRecordHeader();
  private final LocalWalForceResult publishForceResult = new LocalWalForceResult();
  private final CRC32C checksum = new CRC32C();
  private long tailEnd = WalFileHeaderCodec.HEADER_BYTES;
  private long durableEnd = WalFileHeaderCodec.HEADER_BYTES;
  private long nextJournalSequence = 1;
  private long nextReservationToken = 1;
  private long lastCommitSequence;
  private long lastAppendedCommitSequence;
  private long pendingStart;
  private long maximumTransactionId = 1;
  private long activeReservationToken;
  private long copiedPayloadBytes;
  private int pendingRecordCount;
  private DurableWalQuorum durableQuorum;
  private boolean forcedBatch;
  private boolean failed;
  private boolean closed;

  LocalWal(
      DurableFile file,
      DatabaseIncarnation database,
      WalGeneration generation,
      String openedFileName) {
    this.file = file;
    databaseIncarnation = database;
    walGeneration = generation;
    fileName = openedFileName;
    int capacity = WalRecordCodec.HEADER_BYTES + WalRecordCodec.MAX_PAYLOAD_BYTES;
    for (int index = 0; index < MAX_PENDING_RECORDS; index++) {
      appendRecords[index] = ByteBuffer.allocateDirect(capacity);
      appendPayloads[index] = payloadView(appendRecords[index]);
    }
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
    return LocalWalOpener.open(
        directory,
        fileName,
        databaseIncarnation,
        walGeneration,
        createWhenMissing,
        requireCreate,
        result);
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
    return lastAppendedCommitSequence == Long.MAX_VALUE
        ? 0 : lastAppendedCommitSequence + 1;
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
      lastAppendedCommitSequence = commitSequence;
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
    return LocalWalRotator.rotate(
        this, directory, nextFileName, nextGeneration, checkpointTransactionId);
  }

  /** Exclusive local byte end known forced by this synchronous provider. */
  public long durableEnd() {
    return durableEnd;
  }

  /** Explicit River-side payload copies; device transfer bytes are not copies. */
  public long copiedPayloadBytes() {
    return copiedPayloadBytes;
  }

  /**
   * Enables fixed-membership synchronous durable replication for all subsequent force batches.
   */
  public StatusCode enableDurableQuorum(LocalWal[] followers, int requiredNodeCount) {
    return LocalWalQuorumAdmission.enable(this, followers, requiredNodeCount);
  }

  public boolean hasDurableQuorum() {
    return durableQuorum != null;
  }

  public int requiredDurableNodeCount() {
    return durableQuorum == null ? 1 : durableQuorum.requiredNodeCount();
  }

  public int availableDurableNodeCount() {
    return durableQuorum == null ? 1 : durableQuorum.availableNodeCount();
  }

  public long replicatedPayloadBytes() {
    return durableQuorum == null ? 0 : durableQuorum.replicatedPayloadBytes();
  }

  public long quorumDurableCommitSequence() {
    return durableQuorum == null ? 0 : durableQuorum.quorumDurableCommitSequence();
  }

  public StatusCode reserve(int payloadBytes, LocalWalReservation reservation) {
    return LocalWalReservationAdmission.reserve(this, payloadBytes, reservation);
  }

  public StatusCode publish(
      LocalWalReservation reservation,
      long transactionId,
      long commitSequence,
      int decisionCode,
      int formatId,
      int formatVersion,
      LocalWalAppendResult result) {
    StatusCode status = appendUnforced(
        reservation,
        transactionId,
        commitSequence,
        decisionCode,
        formatId,
        formatVersion,
        result);
    if (status.isOk()) {
      status = forcePending(publishForceResult);
    }
    if (status.isOk()) {
      status = releaseForcedBatch();
    }
    return status;
  }

  /** Appends one complete checksummed record without advancing the durable frontier. */
  public StatusCode appendUnforced(
      LocalWalReservation reservation,
      long transactionId,
      long commitSequence,
      int decisionCode,
      int formatId,
      int formatVersion,
      LocalWalAppendResult result) {
    return LocalWalAppender.append(
        this,
        reservation,
        transactionId,
        commitSequence,
        decisionCode,
        formatId,
        formatVersion,
        result);
  }

  /** Forces the current append batch and atomically advances its local durable frontier. */
  public StatusCode forcePending(LocalWalForceResult result) {
    return LocalWalForceCoordinator.force(this, result);
  }

  /** Borrows one record from the last forced batch until {@link #releaseForcedBatch()}. */
  public StatusCode readForcedRecord(int index, LocalWalReadResult result) {
    if (result == null || !forcedBatch || index < 0 || index >= pendingRecordCount) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    ByteBuffer record = appendRecords[index];
    record.position(0);
    StatusCode status = WalRecordCodec.validate(record, result.header(), checksum);
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer payload = appendPayloads[index];
    payload.position(0);
    payload.limit(result.header().payloadBytes());
    result.set(pendingEnds[index], payload);
    return StatusCode.OK;
  }

  /** Releases provider-owned forced views so their fixed slots may be reused. */
  public StatusCode releaseForcedBatch() {
    if (!forcedBatch) {
      return StatusCode.CONFLICT;
    }
    forcedBatch = false;
    for (int index = 0; index < pendingRecordCount; index++) {
      pendingEnds[index] = 0;
    }
    pendingStart = 0;
    pendingRecordCount = 0;
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
    return LocalWalReader.read(this, offset, result);
  }

  public StatusCode close() {
    if (closed) {
      return StatusCode.CLOSED;
    }
    if ((pendingRecordCount != 0 || forcedBatch) && !failed) {
      return StatusCode.CONFLICT;
    }
    closed = true;
    return file.close();
  }

  private StatusCode recoverValidTail() {
    return LocalWalRecovery.recover(this);
  }

  StatusCode recoverValidTailForOpen() {
    return recoverValidTail();
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

  StatusCode initializeFileForOpen(DurableDirectory directory) {
    return initializeFile(directory);
  }

  StatusCode closeFileAfterOpen() {
    return file.close();
  }

  private StatusCode truncateTail(long validEnd, long sequence) {
    StatusCode status = file.truncate(validEnd);
    if (status.isOk()) {
      status = file.force(ForceMode.CONTENT_AND_METADATA);
    }
    if (status.isOk()) {
      tailEnd = validEnd;
      durableEnd = validEnd;
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
      case 1 -> transactionId > 0 && commitSequence > lastAppendedCommitSequence;
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

  boolean hasActiveReservation() {
    return activeReservationToken != 0;
  }

  boolean hasPendingRecords() {
    return pendingRecordCount != 0;
  }

  boolean hasForcedBatch() {
    return forcedBatch;
  }

  StatusCode admissionStatus() {
    return admission();
  }

  void installDurableQuorum(DurableWalQuorum quorum) {
    durableQuorum = quorum;
  }

  StatusCode readFileSize() {
    return file.size(fileSizeResult);
  }

  long fileSizeBytes() {
    return fileSizeResult.sizeBytes();
  }

  ByteBuffer recoveryRecord() {
    return readRecord;
  }

  ByteBuffer readPayloadBuffer() {
    return readPayload;
  }

  WalRecordHeader recoveryHeader() {
    return recoveryHeader;
  }

  java.util.zip.CRC32C recoveryChecksum() {
    return checksum;
  }

  StatusCode readExactForRecovery(long offset, ByteBuffer target) {
    return readExact(offset, target);
  }

  StatusCode truncateTailForRecovery(long validEnd, long sequence) {
    return truncateTail(validEnd, sequence);
  }

  boolean validDecisionForRecovery(WalRecordHeader header) {
    return validDecision(
        header.transactionId(), header.commitSequence(), header.decisionCode());
  }

  void acceptRecoveredRecord(WalRecordHeader header) {
    if (header.decisionCode() == 1) {
      lastCommitSequence = header.commitSequence();
      lastAppendedCommitSequence = lastCommitSequence;
    }
    if (header.transactionId() > maximumTransactionId) {
      maximumTransactionId = header.transactionId();
    }
  }

  void finishRecovery(long offset, long sequence) {
    tailEnd = offset;
    durableEnd = offset;
    nextJournalSequence = sequence;
  }

  boolean ownsReservation(LocalWalReservation reservation) {
    return reservation.isOwnedBy(this, activeReservationToken);
  }

  boolean validDecisionForAppend(long transactionId, long commitSequence, int decisionCode) {
    return validDecision(transactionId, commitSequence, decisionCode);
  }

  ByteBuffer appendRecordBuffer() {
    return appendRecords[pendingRecordCount];
  }

  ByteBuffer appendPayloadBuffer() {
    return appendPayloads[pendingRecordCount];
  }

  int pendingRecordCountValue() {
    return pendingRecordCount;
  }

  long claimNextReservationToken() {
    return nextReservationToken++;
  }

  void activateReservation(long token) {
    activeReservationToken = token;
  }

  StatusCode forceAppendFile() {
    return file.force(ForceMode.CONTENT_AND_METADATA);
  }

  void markFailed() {
    failed = true;
  }

  void markForced(LocalWalForceResult result) {
    durableEnd = tailEnd;
    lastCommitSequence = lastAppendedCommitSequence;
    result.set(pendingStart, durableEnd, pendingRecordCount, lastCommitSequence);
    forcedBatch = true;
  }

  StatusCode replicateForcedBatch() {
    return durableQuorum.replicateForcedBatch(this, pendingRecordCount);
  }

  StatusCode adoptRotatedState(
      LocalWal replacement,
      String nextFileName,
      WalGeneration nextGeneration,
      long checkpointTransactionId) {
    replacement.lastCommitSequence = lastCommitSequence;
    replacement.lastAppendedCommitSequence = lastCommitSequence;
    replacement.maximumTransactionId = Math.max(maximumTransactionId, checkpointTransactionId);
    DurableFile previousFile = file;
    file = replacement.file;
    fileName = nextFileName;
    walGeneration = nextGeneration;
    tailEnd = replacement.tailEnd;
    durableEnd = replacement.durableEnd;
    nextJournalSequence = replacement.nextJournalSequence;
    nextReservationToken = replacement.nextReservationToken;
    lastCommitSequence = replacement.lastCommitSequence;
    lastAppendedCommitSequence = replacement.lastAppendedCommitSequence;
    maximumTransactionId = replacement.maximumTransactionId;
    copiedPayloadBytes += replacement.copiedPayloadBytes;
    return previousFile.close();
  }

  long nextJournalSequenceValue() {
    return nextJournalSequence;
  }

  CRC32C appendChecksum() {
    return checksum;
  }

  StatusCode writeAppendRecord(long offset, ByteBuffer record, int recordBytes) {
    StatusCode status = file.write(offset, record, ioResult);
    return status.isOk() && ioResult.bytesTransferred() != recordBytes
        ? StatusCode.IO_FAILURE : status;
  }

  void abortAppend(LocalWalReservation reservation) {
    failed = true;
    activeReservationToken = 0;
    reservation.complete();
  }

  void acceptAppend(
      LocalWalReservation reservation,
      LocalWalAppendResult result,
      long transactionId,
      long commitSequence,
      int decisionCode,
      int recordBytes) {
    long start = tailEnd;
    if (pendingRecordCount == 0) {
      pendingStart = start;
    }
    tailEnd += recordBytes;
    pendingEnds[pendingRecordCount] = tailEnd;
    result.set(start, tailEnd, nextJournalSequence);
    nextJournalSequence = nextJournalSequence == Long.MAX_VALUE
        ? 0 : nextJournalSequence + 1;
    pendingRecordCount++;
    if (decisionCode == 1) {
      lastAppendedCommitSequence = commitSequence;
    }
    if (transactionId > maximumTransactionId) {
      maximumTransactionId = transactionId;
    }
    activeReservationToken = 0;
    reservation.complete();
  }
}
