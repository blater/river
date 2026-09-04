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
  private final LocalWalForceResult publishForceResult = new LocalWalForceResult();
  private final LocalWalBatchAdmissionResult batchAdmissionResult =
      new LocalWalBatchAdmissionResult();
  private final LocalWalReadResult suffixReadResult = new LocalWalReadResult();
  private final LocalWalForceMetrics forceMetrics = new LocalWalForceMetrics();
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
  private long activeLogicalStreamToken;
  private long copiedPayloadBytes;
  private long pendingRecordCount;
  private DurableWalQuorum durableQuorum;
  private boolean forcedBatch;
  private boolean logicalStreamAppended;
  private boolean logicalStreamFinalAppended;
  private boolean recoveryTailOpen = true;
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
        directory, FILE_NAME, databaseIncarnation, walGeneration, true, false,
        LocalWalForceCause.OTHER, result);
  }

  public static StatusCode create(
      DurableDirectory directory,
      DatabaseIncarnation databaseIncarnation,
      WalGeneration walGeneration,
      LocalWalOpenResult result) {
    return open(
        directory, FILE_NAME, databaseIncarnation, walGeneration, false, true,
        LocalWalForceCause.OTHER, result);
  }

  public static StatusCode openExisting(
      DurableDirectory directory,
      DatabaseIncarnation databaseIncarnation,
      WalGeneration walGeneration,
      LocalWalOpenResult result) {
    return open(
        directory, FILE_NAME, databaseIncarnation, walGeneration, false, false,
        LocalWalForceCause.OTHER, result);
  }

  public static StatusCode createNamed(
      DurableDirectory directory,
      String fileName,
      DatabaseIncarnation databaseIncarnation,
      WalGeneration walGeneration,
      LocalWalOpenResult result) {
    return open(
        directory, fileName, databaseIncarnation, walGeneration, false, true,
        LocalWalForceCause.OTHER, result);
  }

  static StatusCode createCheckpointGeneration(
      DurableDirectory directory,
      String fileName,
      DatabaseIncarnation databaseIncarnation,
      WalGeneration walGeneration,
      LocalWalOpenResult result) {
    return open(
        directory, fileName, databaseIncarnation, walGeneration, false, true,
        LocalWalForceCause.CHECKPOINT, result);
  }

  public static StatusCode openExistingNamed(
      DurableDirectory directory,
      String fileName,
      DatabaseIncarnation databaseIncarnation,
      WalGeneration walGeneration,
      LocalWalOpenResult result) {
    return open(
        directory, fileName, databaseIncarnation, walGeneration, false, false,
        LocalWalForceCause.OTHER, result);
  }

  private static StatusCode open(
      DurableDirectory directory,
      String fileName,
      DatabaseIncarnation databaseIncarnation,
      WalGeneration walGeneration,
      boolean createWhenMissing,
      boolean requireCreate,
      LocalWalForceCause createForceCause,
      LocalWalOpenResult result) {
    return LocalWalOpener.open(
        directory,
        fileName,
        databaseIncarnation,
        walGeneration,
        createWhenMissing,
        requireCreate,
        createForceCause,
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

  /** Copies bounded force telemetry into caller-owned storage without allocating. */
  public StatusCode copyMetrics(LocalWalMetrics result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    forceMetrics.copyTo(result);
    return StatusCode.OK;
  }

  /** Starts one explicit aggregate-only observation window. */
  public StatusCode beginMetricsCapture() {
    return forceMetrics.beginCapture();
  }

  /** Ends the active observation window into caller-owned storage. */
  public StatusCode endMetricsCapture(LocalWalMetrics result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    return forceMetrics.endCapture(result);
  }

  public StatusCode cancelMetricsCapture() {
    return forceMetrics.cancelCapture();
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

  /** Opens one authenticated, non-interleavable logical stream across force batches. */
  public StatusCode beginLogicalStream(
      long transactionId,
      int formatId,
      int formatVersion,
      LocalWalLogicalStream stream) {
    if (stream == null || transactionId <= 0 || formatId <= 0 || formatVersion <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = admission();
    if (!status.isOk()) return status;
    if (activeLogicalStreamToken != 0 || activeReservationToken != 0
        || pendingRecordCount != 0 || forcedBatch) {
      return StatusCode.CONFLICT;
    }
    long token = claimNextReservationToken();
    status = stream.claim(this, token, transactionId, formatId, formatVersion);
    if (!status.isOk()) return status;
    activeLogicalStreamToken = token;
    recoveryTailOpen = false;
    logicalStreamAppended = false;
    logicalStreamFinalAppended = false;
    if (durableQuorum != null) {
      status = durableQuorum.beginLogicalStream(transactionId, formatId, formatVersion);
      if (!status.isOk()) {
        completeLogicalStream(stream);
        if (status == StatusCode.FENCED) failed = true;
      }
    }
    return status;
  }

  public StatusCode appendLogicalStreamContinuation(
      LocalWalLogicalStream stream,
      LocalWalRecordBatch batch,
      LocalWalGroupAppendResult result) {
    return LocalWalRecordBatchAppender.appendContinuation(this, stream, batch, result);
  }

  public StatusCode appendLogicalStreamFinal(
      LocalWalLogicalStream stream,
      LocalWalRecordBatch batch,
      long commitSequence,
      LocalWalGroupAppendResult result) {
    return LocalWalRecordBatchAppender.appendFinal(
        this, stream, batch, commitSequence, result);
  }

  public StatusCode forceLogicalStreamBatch(
      LocalWalLogicalStream stream, LocalWalForceResult result) {
    return forceLogicalStreamBatch(stream, result, LocalWalForceCause.OTHER);
  }

  public StatusCode forceLogicalStreamBatch(
      LocalWalLogicalStream stream,
      LocalWalForceResult result,
      LocalWalForceCause cause) {
    if (cause == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!ownsLogicalStream(stream)) return StatusCode.CONFLICT;
    return LocalWalForceCoordinator.force(this, result, cause);
  }

  public StatusCode releaseLogicalStreamBatch(LocalWalLogicalStream stream) {
    if (!ownsLogicalStream(stream)) return StatusCode.CONFLICT;
    StatusCode status = releaseForcedBatchInternal();
    if (status.isOk() && logicalStreamFinalAppended) completeLogicalStream(stream);
    return status;
  }

  /** Cancels a stream only while no bytes from it have been accepted. */
  public StatusCode cancelLogicalStream(LocalWalLogicalStream stream) {
    if (!ownsLogicalStream(stream)) return StatusCode.CONFLICT;
    if (logicalStreamAppended || pendingRecordCount != 0 || forcedBatch) {
      return StatusCode.CONFLICT;
    }
    if (activeReservationToken != 0) return StatusCode.CONFLICT;
    StatusCode status = durableQuorum == null
        ? StatusCode.OK : durableQuorum.cancelLogicalStreams();
    if (status.isOk()) completeLogicalStream(stream);
    return status;
  }

  /** Permanently fences this provider after a partially accepted logical stream fails. */
  public StatusCode fenceLogicalStream(LocalWalLogicalStream stream) {
    if (!ownsLogicalStream(stream)) return StatusCode.CONFLICT;
    failed = true;
    activeReservationToken = 0;
    if (durableQuorum != null) durableQuorum.fenceLogicalStreams();
    completeLogicalStream(stream);
    return StatusCode.OK;
  }

  public StatusCode publish(
      LocalWalReservation reservation,
      long transactionId,
      long commitSequence,
      int decisionCode,
      int formatId,
      int formatVersion,
      LocalWalAppendResult result) {
    return publish(
        reservation, transactionId, commitSequence, decisionCode,
        formatId, formatVersion, result, LocalWalForceCause.DIRECT_COMMIT);
  }

  /** Appends and forces one record with an explicit force cause. */
  public StatusCode publish(
      LocalWalReservation reservation,
      long transactionId,
      long commitSequence,
      int decisionCode,
      int formatId,
      int formatVersion,
      LocalWalAppendResult result,
      LocalWalForceCause cause) {
    if (cause == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = appendUnforced(
        reservation,
        transactionId,
        commitSequence,
        decisionCode,
        formatId,
        formatVersion,
        result);
    if (status.isOk()) {
      status = forcePending(publishForceResult, cause);
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

  /** Appends one fully populated logical record group without forcing its pending batch. */
  public StatusCode appendGroupUnforced(
      LocalWalRecordBatch batch,
      long transactionId,
      long commitSequence,
      int formatId,
      int formatVersion,
      LocalWalGroupAppendResult result) {
    return LocalWalRecordBatchAppender.appendFinal(
        this, batch, transactionId, commitSequence, formatId, formatVersion, result);
  }

  /** Appends independently decided logical groups admitted by one aggregate reservation. */
  public StatusCode appendDecisionBatchUnforced(
      LocalWalDecisionBatch batch,
      int formatId,
      int formatVersion,
      LocalWalGroupAppendResult result) {
    return LocalWalDecisionBatchAppender.append(
        this, batch, formatId, formatVersion, result);
  }

  /** Appends a forced-batch continuation whose records carry no transaction decision. */
  StatusCode appendContinuationGroupUnforced(
      LocalWalRecordBatch batch,
      long transactionId,
      int formatId,
      int formatVersion,
      LocalWalGroupAppendResult result) {
    return LocalWalRecordBatchAppender.appendContinuation(
        this, batch, transactionId, formatId, formatVersion, result);
  }

  /** Forces the current append batch and atomically advances its local durable frontier. */
  public StatusCode forcePending(LocalWalForceResult result) {
    return forcePending(result, LocalWalForceCause.OTHER);
  }

  /** Forces pending WAL records with an explicit, mutually exclusive cause. */
  public StatusCode forcePending(
      LocalWalForceResult result, LocalWalForceCause cause) {
    if (cause == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (hasOpenLogicalStream()) return StatusCode.CONFLICT;
    return LocalWalForceCoordinator.force(this, result, cause);
  }

  /** Opens a sequential view over the current forced range. */
  public StatusCode openForcedCursor(LocalWalForcedCursor cursor) {
    if (cursor == null || !forcedBatch || pendingRecordCount <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return cursor.open(this, pendingStart, durableEnd, pendingRecordCount);
  }

  /** Releases provider-owned forced views so their fixed slots may be reused. */
  public StatusCode releaseForcedBatch() {
    if (hasOpenLogicalStream()) return StatusCode.CONFLICT;
    return releaseForcedBatchInternal();
  }

  private StatusCode releaseForcedBatchInternal() {
    if (!forcedBatch) {
      return StatusCode.CONFLICT;
    }
    forcedBatch = false;
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

  /** Fences a partially assembled decision batch whose outcome can no longer be reported safely. */
  public StatusCode fencePendingBatch() {
    if (pendingRecordCount == 0 || activeReservationToken != 0) {
      return StatusCode.CONFLICT;
    }
    failed = true;
    return StatusCode.OK;
  }

  public StatusCode read(long offset, LocalWalReadResult result) {
    return LocalWalReader.read(this, offset, result);
  }

  /**
   * Removes an incomplete, decisionless logical suffix during startup recovery only.
   */
  public StatusCode truncateDecisionlessRecoveredSuffix(
      long startOffset, long firstJournalSequence) {
    StatusCode status = admission();
    if (!status.isOk()) return status;
    if (!recoveryTailOpen || durableQuorum != null || activeReservationToken != 0
        || pendingRecordCount != 0 || forcedBatch || hasOpenLogicalStream()
        || startOffset < WalFileHeaderCodec.HEADER_BYTES || startOffset >= tailEnd
        || firstJournalSequence <= 0 || firstJournalSequence >= nextJournalSequence) {
      return StatusCode.CONFLICT;
    }
    long offset = startOffset;
    long sequence = firstJournalSequence;
    while (offset < tailEnd) {
      status = read(offset, suffixReadResult);
      if (!status.isOk()) return status;
      WalRecordHeader header = suffixReadResult.header();
      if (header.journalSequence() != sequence || header.decisionCode() != 0
          || header.commitSequence() != 0 || suffixReadResult.nextOffset() <= offset) {
        return StatusCode.CORRUPTION;
      }
      offset = suffixReadResult.nextOffset();
      sequence = sequence == Long.MAX_VALUE ? 0 : sequence + 1;
    }
    if (offset != tailEnd || sequence != nextJournalSequence) {
      return StatusCode.CORRUPTION;
    }
    status = truncateTail(startOffset, firstJournalSequence);
    if (status.isOk()) recoveryTailOpen = false;
    else failed = true;
    return status;
  }

  /** Ends the startup-only recovered-tail repair window without changing bytes. */
  public StatusCode completeRecovery() {
    StatusCode status = admission();
    if (!status.isOk()) return status;
    if (activeReservationToken != 0 || pendingRecordCount != 0
        || forcedBatch || hasOpenLogicalStream()) return StatusCode.CONFLICT;
    recoveryTailOpen = false;
    return StatusCode.OK;
  }

  public StatusCode close() {
    if (closed) {
      return StatusCode.CLOSED;
    }
    if ((pendingRecordCount != 0 || forcedBatch || hasOpenLogicalStream()) && !failed) {
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

  private StatusCode initializeFile(
      DurableDirectory directory, LocalWalForceCause forceCause) {
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
      status = forceFile(forceCause, WalFileHeaderCodec.HEADER_BYTES);
    }
    if (status.isOk()) {
      DirectoryOperationResult forceResult = new DirectoryOperationResult();
      status = directory.force(forceResult);
    }
    return status;
  }

  StatusCode initializeFileForOpen(
      DurableDirectory directory, LocalWalForceCause forceCause) {
    return initializeFile(directory, forceCause);
  }

  StatusCode closeFileAfterOpen() {
    return file.close();
  }

  private StatusCode truncateTail(long validEnd, long sequence) {
    StatusCode status = file.truncate(validEnd);
    if (status.isOk()) {
      status = forceFile(LocalWalForceCause.RECOVERY_MAINTENANCE, validEnd);
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

  boolean hasOpenLogicalStream() {
    return activeLogicalStreamToken != 0;
  }

  boolean ownsLogicalStream(LocalWalLogicalStream stream) {
    return stream != null && stream.isOwnedBy(this, activeLogicalStreamToken);
  }

  long logicalStreamToken(LocalWalLogicalStream stream) {
    return stream == null ? 0 : activeLogicalStreamToken;
  }

  void acceptLogicalStreamBatch(boolean finalBatch) {
    logicalStreamAppended = true;
    logicalStreamFinalAppended = finalBatch;
  }

  private void completeLogicalStream(LocalWalLogicalStream stream) {
    activeLogicalStreamToken = 0;
    logicalStreamAppended = false;
    logicalStreamFinalAppended = false;
    stream.complete();
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
    return appendRecord;
  }

  ByteBuffer appendPayloadBuffer() {
    return appendPayload;
  }

  ByteBuffer prepareAppendPayload(int payloadBytes) {
    appendRecord.clear();
    appendPayload.clear();
    appendPayload.limit(payloadBytes);
    return appendPayload;
  }

  long pendingRecordCountValue() {
    return pendingRecordCount;
  }

  LocalWalBatchAdmissionResult batchAdmissionResult() {
    return batchAdmissionResult;
  }

  long claimNextReservationToken() {
    return nextReservationToken++;
  }

  void activateReservation(long token) {
    activeReservationToken = token;
    recoveryTailOpen = false;
  }

  StatusCode forceAppendFile(LocalWalForceCause cause) {
    return forceFile(cause, pendingForceBytes());
  }

  private StatusCode forceFile(LocalWalForceCause cause, long coveredBytes) {
    long started = System.nanoTime();
    StatusCode status = file.force(ForceMode.CONTENT_AND_METADATA);
    forceMetrics.record(cause, coveredBytes, System.nanoTime() - started, status);
    return status;
  }

  long pendingForceBytes() {
    return Math.max(0, tailEnd - durableEnd);
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

  StatusCode replicateForcedBatch(LocalWalForceCause cause) {
    return hasOpenLogicalStream()
        ? durableQuorum.replicateLogicalStreamBatch(this, pendingRecordCount, cause)
        : durableQuorum.replicateForcedBatch(this, pendingRecordCount, cause);
  }

  StatusCode adoptRotatedState(
      LocalWal replacement,
      String nextFileName,
      WalGeneration nextGeneration,
      long checkpointTransactionId) {
    forceMetrics.merge(replacement.forceMetrics);
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

  void abortRecordBatchAppend() {
    failed = true;
  }

  void acceptRecordBatchAppend(
      LocalWalGroupAppendResult result,
      long transactionId,
      long commitSequence,
      long appendedEnd,
      int recordCount) {
    long start = tailEnd;
    long firstSequence = nextJournalSequence;
    if (pendingRecordCount == 0) pendingStart = start;
    tailEnd = appendedEnd;
    pendingRecordCount += recordCount;
    nextJournalSequence = firstSequence > Long.MAX_VALUE - recordCount
        ? 0 : firstSequence + recordCount;
    if (commitSequence > 0) lastAppendedCommitSequence = commitSequence;
    if (transactionId > maximumTransactionId) maximumTransactionId = transactionId;
    result.set(start, tailEnd, firstSequence, recordCount);
  }

  void acceptDecisionBatchAppend(
      LocalWalGroupAppendResult result,
      LocalWalDecisionBatch batch,
      long appendedEnd) {
    long start = tailEnd;
    long firstSequence = nextJournalSequence;
    if (pendingRecordCount == 0) pendingStart = start;
    int records = batch.recordCount();
    tailEnd = appendedEnd;
    pendingRecordCount += records;
    nextJournalSequence = firstSequence > Long.MAX_VALUE - records
        ? 0 : firstSequence + records;
    int transactions = batch.transactionCount();
    lastAppendedCommitSequence = batch.commitSequence(transactions - 1);
    for (int index = 0; index < transactions; index++) {
      maximumTransactionId = Math.max(maximumTransactionId, batch.transactionId(index));
    }
    result.set(start, tailEnd, firstSequence, records);
  }
}
