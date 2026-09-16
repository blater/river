package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.DurableFile;

/**
 * Single-owner synchronous WAL with reusable provider storage and globally ordered commit CSNs.
 */
public final class LocalWal {
  public static final String FILE_NAME = "river.wal";

  private final DatabaseIncarnation databaseIncarnation;
  private WalGeneration walGeneration;
  private String fileName;
  private final LocalWalAppendState appendState;
  private final LocalWalRecoveryState recoveryState;
  private final LocalWalStreamState streamState;
  private final LocalWalLifecycleState lifecycleState;
  private final LocalWalReservationState reservationState;
  private final LocalWalForceTarget publishForceTarget = new LocalWalForceTarget();
  private final LocalWalForceState forceState;
  private long nextReservationToken = 1;
  long nextForceToken = 1;
  long activeReservationToken;
  private DurableWalQuorum durableQuorum;
  boolean recoveryTailOpen = true;
  boolean failed;
  boolean closed;

  LocalWal(
      DurableFile file,
      DatabaseIncarnation database,
      WalGeneration generation,
      String openedFileName) {
    appendState = new LocalWalAppendState(this, file);
    recoveryState = new LocalWalRecoveryState(this, appendState);
    streamState = new LocalWalStreamState(this);
    forceState = new LocalWalForceState(this, appendState);
    lifecycleState = new LocalWalLifecycleState(this, appendState, forceState);
    reservationState = new LocalWalReservationState(this);
    databaseIncarnation = database;
    walGeneration = generation;
    fileName = openedFileName;
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
    return appendState.tailEnd();
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
    return appendState.nextJournalSequence();
  }

  public long nextCommitSequence() {
    return appendState.nextCommitSequence();
  }

  public long currentCommitSequence() {
    return appendState.currentCommitSequence();
  }

  public long nextTransactionId() {
    long maximum = appendState.maximumTransactionId();
    return maximum == Long.MAX_VALUE ? 0 : maximum + 1;
  }

  public long maximumTransactionId() {
    return appendState.maximumTransactionId();
  }

  public StatusCode adoptCheckpointState(long commitSequence, long transactionId) {
    return appendState.validateAndAdoptCheckpoint(commitSequence, transactionId);
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
    return appendState.durableEnd();
  }

  /** Explicit River-side payload copies; device transfer bytes are not copies. */
  public long copiedPayloadBytes() {
    return appendState.copiedPayloadBytes();
  }

  /** Copies bounded force telemetry into caller-owned storage without allocating. */
  public StatusCode copyMetrics(LocalWalMetrics result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    forceState.metrics().copyTo(result);
    return StatusCode.OK;
  }

  /** Starts one explicit aggregate-only observation window. */
  public StatusCode beginMetricsCapture() {
    return forceState.metrics().beginCapture();
  }

  /** Ends the active observation window into caller-owned storage. */
  public StatusCode endMetricsCapture(LocalWalMetrics result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    return forceState.metrics().endCapture(result);
  }

  public StatusCode cancelMetricsCapture() {
    return forceState.metrics().cancelCapture();
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
    return streamState.begin(transactionId, formatId, formatVersion, stream);
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
      LocalWalLogicalStream stream, LocalWalForceTarget target) {
    return forceLogicalStreamBatch(stream, target, LocalWalForceCause.OTHER);
  }

  public StatusCode forceLogicalStreamBatch(
      LocalWalLogicalStream stream,
      LocalWalForceTarget target,
      LocalWalForceCause cause) {
    if (cause == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!streamState.owns(stream)) return StatusCode.CONFLICT;
    return LocalWalForceCoordinator.force(this, target, cause);
  }

  public StatusCode releaseLogicalStreamBatch(
      LocalWalLogicalStream stream, LocalWalForceTarget target, long token) {
    return streamState.release(stream, target, token);
  }

  /** Cancels a stream only while no bytes from it have been accepted. */
  public StatusCode cancelLogicalStream(LocalWalLogicalStream stream) {
    return streamState.cancel(stream);
  }

  /** Permanently fences this provider after a partially accepted logical stream fails. */
  public StatusCode fenceLogicalStream(LocalWalLogicalStream stream) {
    return streamState.fence(stream);
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
      status = forcePending(publishForceTarget, cause);
    }
    if (status.isOk()) {
      status = releaseForcedBatch(publishForceTarget, publishForceTarget.token());
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
  public StatusCode forcePending(LocalWalForceTarget target) {
    return forcePending(target, LocalWalForceCause.OTHER);
  }

  /** Forces pending WAL records with an explicit, mutually exclusive cause. */
  public StatusCode forcePending(
      LocalWalForceTarget target, LocalWalForceCause cause) {
    if (cause == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (hasOpenLogicalStream()) return StatusCode.CONFLICT;
    return LocalWalForceCoordinator.force(this, target, cause);
  }

  /** Starts the one database-local force worker owned by this primary WAL. */
  public StatusCode enableForceWorker(Thread completionOwner) {
    return LocalWalForceCoordinator.enable(this, completionOwner);
  }

  /** Finishes the current footer and transfers its immutable bytes into the sealed suffix. */
  public StatusCode sealPendingBatch() {
    return LocalWalForceCoordinator.seal(this);
  }

  /** Captures and submits the whole currently sealed suffix without blocking its writer. */
  public StatusCode submitSealedForce(
      LocalWalForceTarget target, LocalWalForceCause cause) {
    return LocalWalForceCoordinator.submit(this, target, cause);
  }

  public boolean submittedForceComplete(LocalWalForceTarget target) {
    return LocalWalForceCoordinator.completed(this, target);
  }

  /** Consumes local force, advances local truth, then performs the configured quorum on this writer. */
  public StatusCode completeSubmittedForce(LocalWalForceTarget target, long token) {
    return LocalWalForceCoordinator.complete(this, target, token);
  }

  /** Opens the locally forced captured range; quorum uses it before configured completion. */
  public StatusCode openForcedCursor(
      LocalWalForceTarget target, long token, LocalWalForcedCursor cursor) {
    return forceState.openCursor(target, token, cursor);
  }

  /** Releases exactly the completed target, invalidating cursors before storage reuse. */
  public StatusCode releaseForcedBatch(LocalWalForceTarget target, long token) {
    if (hasOpenLogicalStream()) return StatusCode.CONFLICT;
    return forceState.release(target, token);
  }

  public StatusCode cancel(LocalWalReservation reservation) {
    return reservationState.cancel(reservation);
  }

  /** Fences a partially assembled decision batch whose outcome can no longer be reported safely. */
  public StatusCode fencePendingBatch() {
    return reservationState.fencePendingBatch();
  }

  public StatusCode read(long offset, LocalWalReadResult result) {
    return LocalWalReader.read(this, offset, result);
  }

  /**
   * Removes an incomplete, decisionless logical suffix during startup recovery only.
   */
  public StatusCode truncateDecisionlessRecoveredSuffix(
      long startOffset, long firstJournalSequence) {
    return recoveryState.truncateDecisionless(startOffset, firstJournalSequence);
  }

  /** Ends the startup-only recovered-tail repair window without changing bytes. */
  public StatusCode completeRecovery() {
    return recoveryState.completeRecovery();
  }

  public StatusCode close() {
    return lifecycleState.close();
  }

  private StatusCode recoverValidTail() {
    return LocalWalRecovery.recover(this);
  }

  StatusCode recoverValidTailForOpen() {
    return recoverValidTail();
  }

  private StatusCode initializeFile(
      DurableDirectory directory, LocalWalForceCause forceCause) {
    return lifecycleState.initialize(directory, forceCause);
  }

  StatusCode initializeFileForOpen(
      DurableDirectory directory, LocalWalForceCause forceCause) {
    return initializeFile(directory, forceCause);
  }

  StatusCode closeFileAfterOpen() {
    return appendState.file().close();
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

  boolean decisionCodeValid(
      long transactionId,
      long commitSequence,
      int decisionCode,
      long priorCommitSequence) {
    return switch (decisionCode) {
      case 0 -> commitSequence == 0;
      case 1 -> transactionId > 0 && commitSequence > priorCommitSequence;
      case 2 -> transactionId > 0 && commitSequence == 0;
      default -> false;
    };
  }

  boolean hasActiveReservation() {
    return activeReservationToken != 0;
  }

  boolean hasOpenLogicalStream() {
    return streamState.open();
  }

  boolean ownsLogicalStream(LocalWalLogicalStream stream) {
    return streamState.owns(stream);
  }

  long logicalStreamToken(LocalWalLogicalStream stream) {
    return streamState.token(stream);
  }

  void acceptLogicalStreamBatch(boolean finalBatch) {
    streamState.acceptBatch(finalBatch);
  }

  private void completeLogicalStream(LocalWalLogicalStream stream) {
    streamState.complete(stream);
  }

  boolean hasPendingRecords() {
    return appendState.hasPendingRecords();
  }

  boolean hasRetainedForceTarget() {
    return forceState.hasTarget();
  }

  StatusCode admissionStatus() {
    return admission();
  }

  LocalWalAppendState appendState() { return appendState; }

  LocalWalForceState forceState() { return forceState; }

  LocalWalRecoveryState recoveryState() { return recoveryState; }

  void installDurableQuorum(DurableWalQuorum quorum) {
    durableQuorum = quorum;
  }

  DurableWalQuorum durableQuorum() { return durableQuorum; }

  StatusCode cancelDurableLogicalStream() {
    return durableQuorum == null ? StatusCode.OK : durableQuorum.cancelLogicalStreams();
  }

  void fenceDurableLogicalStream() {
    if (durableQuorum != null) durableQuorum.fenceLogicalStreams();
  }

  void clearActiveReservation() { activeReservationToken = 0; }

  boolean ownsReservation(LocalWalReservation reservation) {
    return reservation.isOwnedBy(this, activeReservationToken);
  }

  boolean validDecisionForAppend(long transactionId, long commitSequence, int decisionCode) {
    return decisionCodeValid(
        transactionId, commitSequence, decisionCode, appendState.lastAppendedCommitSequence());
  }

  long claimNextReservationToken() {
    return nextReservationToken++;
  }

  void activateReservation(long token) {
    activeReservationToken = token;
    recoveryTailOpen = false;
  }



  void markFailed() {
    failed = true;
  }

  StatusCode captureForceTarget(LocalWalForceTarget target) {
    StatusCode status = appendState.pendingRecordCount() == 0 ? StatusCode.OK : sealPendingBatch();
    return status.isOk() ? forceState.capture(target) : status;
  }

  StatusCode replicateForcedBatch(LocalWalForceTarget target, LocalWalForceCause cause) {
    return hasOpenLogicalStream()
        ? durableQuorum.replicateLogicalStreamBatch(this, target, cause)
        : durableQuorum.replicateForcedBatch(this, target, cause);
  }

  StatusCode adoptRotatedState(
      LocalWal replacement,
      String nextFileName,
      WalGeneration nextGeneration,
      long checkpointTransactionId) {
    forceState.mergeMetrics(replacement.forceState);
    fileName = nextFileName;
    walGeneration = nextGeneration;
    nextReservationToken = replacement.nextReservationToken;
    return appendState.adoptFrom(replacement.appendState, checkpointTransactionId);
  }

  void abortAppend(LocalWalReservation reservation) {
    failed = true;
    activeReservationToken = 0;
    reservation.complete();
  }

  void abortRecordBatchAppend() {
    failed = true;
  }

}
