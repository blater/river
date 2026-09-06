package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.Transaction;
import io.riverdb.tx.TransactionGroupCommitParticipant;
import io.riverdb.tx.TransactionGroupCompletionTimings;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.lock.LockScope;

/** Reusable active-transaction-budget-sized workspace for one commit-writer cohort. */
class IndexedGroupCommitBatch implements TransactionGroupCommitParticipant {
  private static final LockScope[] LOCK_SCOPES = LockScope.values();

  private final IndexedGroupCommitRequest[] requests;
  private final IndexedPreparedLogicalCommit[] prepared;
  private final Transaction[] transactions;
  private final TransactionOutcome[] outcomes;
  private final StatusCode[] statuses;
  private final long[] commitSequences;
  private final long[] committedRows;
  private final TransactionManager manager;
  private final IndexedTable table;
  private final IndexedGroupCommitMetrics metrics;
  private final TransactionGroupCompletionTimings completionTimings =
      new TransactionGroupCompletionTimings();
  private final IndexedCommitOpportunityEvent probe = new IndexedCommitOpportunityEvent();
  private boolean attemptedGroupRecorded;
  private boolean publicationInstallAttempted;
  private long publicationInstallNanos;
  private StatusCode publicationInstallStatus;

  IndexedGroupCommitBatch(
      TransactionManager transactionManager,
      IndexedTable indexedTable,
      IndexedGroupCommitMetrics groupMetrics) {
    manager = transactionManager;
    table = indexedTable;
    metrics = groupMetrics;
    int capacity = transactionManager.maximumActiveTransactions();
    requests = new IndexedGroupCommitRequest[capacity];
    prepared = new IndexedPreparedLogicalCommit[capacity];
    transactions = new Transaction[capacity];
    outcomes = new TransactionOutcome[capacity];
    statuses = new StatusCode[capacity];
    commitSequences = new long[capacity];
    committedRows = new long[capacity];
  }

  int capacity() { return requests.length; }

  void add(int index, IndexedGroupCommitRequest request) {
    requests[index] = request;
    prepared[index] = request.session.preparedCommit();
    transactions[index] = request.transaction;
    outcomes[index] = request.outcome;
  }

  void process(int count) {
    probe.processStarted = System.nanoTime();
    probe.published = 0;
    probe.forceStarted = 0;
    probe.forceFinished = 0;
    probe.groupSize = count;
    if (requests[0].groupable) {
      metrics.recordAttemptedGroup(count);
      attemptedGroupRecorded = true;
    }
    if (!requests[0].groupable) {
      for (int index = 0; index < count; index++) {
        commitDirectly(index, IndexedDirectCommitReason.INITIALLY_INELIGIBLE);
      }
      return;
    }
    if (appendSharedGroup(count) && publishPrepared(count)) completeDurability(count);
  }

  boolean appendSharedGroup(int count) {
    long started = System.nanoTime();
    StatusCode status = table.preflightHybridCommitGroup(
        prepared, count, manager.oldestVisibleCommitSequence());
    metrics.recordStage(
        IndexedCommitPath.SHARED_GROUP,
        IndexedCommitStage.GROUP_PREFLIGHT,
        System.nanoTime() - started);
    if (!status.isOk()) {
      metrics.recordGroupFailure(IndexedGroupFailureStage.PREFLIGHT, status, count);
      abortPreparedGroup(count, status);
      return false;
    }
    attributePaths(count, IndexedCommitPath.SHARED_GROUP);
    started = System.nanoTime();
    status = manager.beginCommitGroup(transactions, count);
    metrics.recordStage(
        IndexedCommitPath.SHARED_GROUP,
        IndexedCommitStage.GROUP_ADMISSION,
        System.nanoTime() - started);
    if (!status.isOk()) {
      StatusCode cancellation = table.cancelCommitGroup();
      metrics.recordGroupFailure(IndexedGroupFailureStage.ADMISSION, status, count);
      abortPreparedGroup(
          count, cancellation.isOk() ? status : StatusCode.FENCED);
      return false;
    }
    started = System.nanoTime();
    status = table.appendHybridCommitGroup(
        prepared, commitSequences, committedRows, count);
    metrics.recordStage(
        IndexedCommitPath.SHARED_GROUP,
        IndexedCommitStage.GROUP_APPEND,
        System.nanoTime() - started);
    if (!status.isOk()) {
      failGroup(count, IndexedGroupFailureStage.APPEND, status);
      return false;
    }
    return true;
  }

  boolean publishPrepared(int count) {
    long publicationStarted = System.nanoTime();
    long started = publicationStarted;
    StatusCode status = table.prepareGroupPublication();
    long preparedNanos = System.nanoTime() - started;
    metrics.recordStage(
        IndexedCommitPath.SHARED_GROUP,
        IndexedCommitStage.GROUP_PUBLICATION_PREPARE,
        preparedNanos);
    if (!status.isOk()) {
      metrics.recordStageFailure(
          IndexedCommitPath.SHARED_GROUP,
          IndexedCommitStage.GROUP_PUBLICATION_PREPARE,
          status);
      metrics.recordStage(
          IndexedCommitPath.SHARED_GROUP,
          IndexedCommitStage.GROUP_PUBLICATION,
          preparedNanos);
      failGroup(count, IndexedGroupFailureStage.PUBLICATION, status);
      return false;
    }
    publicationInstallAttempted = false;
    publicationInstallNanos = 0;
    publicationInstallStatus = null;
    started = System.nanoTime();
    status = manager.publishCommitGroup(
        transactions, outcomes, commitSequences, count, this, completionTimings);
    probe.published = System.nanoTime();
    long publishNanos = probe.published - started;
    if (publicationInstallAttempted) {
      metrics.recordStage(
          IndexedCommitPath.SHARED_GROUP,
          IndexedCommitStage.GROUP_PUBLICATION_INSTALL,
          publicationInstallNanos);
      if (!publicationInstallStatus.isOk()) {
        metrics.recordStageFailure(
            IndexedCommitPath.SHARED_GROUP,
            IndexedCommitStage.GROUP_PUBLICATION_INSTALL,
            publicationInstallStatus);
      } else {
        metrics.recordStage(
            IndexedCommitPath.SHARED_GROUP,
            IndexedCommitStage.GROUP_TRANSACTION_COMPLETION,
            Math.max(0, publishNanos - publicationInstallNanos));
        metrics.recordStage(
            IndexedCommitPath.SHARED_GROUP,
            IndexedCommitStage.GROUP_LOCK_RELEASE,
            completionTimings.lockReleaseNanos());
        metrics.recordStage(
            IndexedCommitPath.SHARED_GROUP,
            IndexedCommitStage.GROUP_LOCK_OUTCOME,
            completionTimings.lockOutcomeNanos());
        metrics.recordStage(
            IndexedCommitPath.SHARED_GROUP,
            IndexedCommitStage.GROUP_LOCK_REQUEST_CANCELLATION,
            completionTimings.lockRequestCancellationNanos());
        metrics.recordStage(
            IndexedCommitPath.SHARED_GROUP,
            IndexedCommitStage.GROUP_LOCK_HOLDING_RELEASE,
            completionTimings.lockHoldingReleaseNanos());
        metrics.recordStage(
            IndexedCommitPath.SHARED_GROUP,
            IndexedCommitStage.GROUP_LOCK_RECORD_RECYCLE,
            completionTimings.lockRecordRecycleNanos());
        for (LockScope scope : LOCK_SCOPES) {
          long released = completionTimings.lockHoldingsReleased(scope);
          if (released != 0) metrics.recordGroupLockHoldingsReleased(scope, released);
        }
        metrics.recordStage(
            IndexedCommitPath.SHARED_GROUP,
            IndexedCommitStage.GROUP_ACTIVE_REMOVAL,
            completionTimings.activeRemovalNanos());
        if (!status.isOk()) {
          metrics.recordStageFailure(
              IndexedCommitPath.SHARED_GROUP,
              IndexedCommitStage.GROUP_TRANSACTION_COMPLETION,
              status);
        }
      }
    }
    metrics.recordStage(
        IndexedCommitPath.SHARED_GROUP,
        IndexedCommitStage.GROUP_PUBLICATION,
        System.nanoTime() - publicationStarted);
    if (!status.isOk()) {
      table.cancelCommitGroup();
      metrics.recordGroupFailure(IndexedGroupFailureStage.PUBLICATION, status, count);
      setAll(count, status);
      return false;
    }
    return true;
  }

  void completeDurability(int count) {
    StatusCode status = force();
    if (!status.isOk()) {
      failGroup(count, IndexedGroupFailureStage.FORCE, status);
      return;
    }
    status = table.completeGroupDurability();
    if (status.isOk()) {
      long started = System.nanoTime();
      status = manager.completeCommitGroup(transactions, outcomes, count);
      metrics.recordStage(IndexedCommitPath.SHARED_GROUP,
          IndexedCommitStage.GROUP_OUTCOME_PUBLICATION, System.nanoTime() - started);
    }
    if (!status.isOk()) {
      table.fenceCommitWriter();
      failGroup(count, IndexedGroupFailureStage.PUBLICATION, status);
      return;
    }
    for (int index = 0; index < count; index++) {
      requests[index].session.recordGroupPublication(
          committedRows[index], commitSequences[index]);
    }
    metrics.recordSuccessfulGroup(count);
    setAll(count, StatusCode.OK);
  }

  @Override
  public StatusCode installPreparedGroup() {
    publicationInstallAttempted = true;
    long started = System.nanoTime();
    StatusCode status = table.installPreparedGroup();
    publicationInstallNanos = System.nanoTime() - started;
    publicationInstallStatus = status;
    return status;
  }

  void complete(int count) {
    for (int index = 0; index < count; index++) {
      IndexedGroupCommitRequest request = requests[index];
      StatusCode status = statuses[index];
      probe.submitted = request.submittedNanos();
      probe.enqueued = request.probeEnqueued;
      probe.selected = request.probeSelected;
      probe.successful = status.isOk();
      probe.completed = System.nanoTime();
      probe.commit();
      requests[index] = null;
      prepared[index] = null;
      transactions[index] = null;
      outcomes[index] = null;
      statuses[index] = null;
      commitSequences[index] = 0;
      committedRows[index] = 0;
      StatusCode completion = request.completeOnce(
          request.session.completeCoordinatedCommit(status));
      if (!completion.isOk()) table.fenceCommitWriter();
    }
    attemptedGroupRecorded = false;
    publicationInstallAttempted = false;
    publicationInstallNanos = 0;
    publicationInstallStatus = null;
  }

  void failUnexpected(int count) {
    IndexedCommitPath path = requests[0].groupable
        ? IndexedCommitPath.SHARED_GROUP : IndexedCommitPath.DIRECT_COMMIT;
    metrics.recordStageFailure(
        path, IndexedCommitStage.WRITER_FAILURE, StatusCode.INVARIANT_BROKEN);
    if (requests[0].groupable) {
      if (!attemptedGroupRecorded) metrics.recordAttemptedGroup(count);
      metrics.recordGroupFailure(
          IndexedGroupFailureStage.WRITER_FAILURE,
          StatusCode.INVARIANT_BROKEN,
          count);
    }
    StatusCode terminal = manager.terminalizeAcceptedCommitGroup(
        transactions, outcomes, count, StatusCode.INVARIANT_BROKEN);
    setAll(count, terminal.isOk() ? StatusCode.INVARIANT_BROKEN : StatusCode.FENCED);
  }

  private StatusCode force() {
    long started = System.nanoTime();
    probe.forceStarted = started;
    StatusCode status = table.forceHybridCommitGroup();
    probe.forceFinished = System.nanoTime();
    long elapsed = probe.forceFinished - started;
    metrics.recordStage(
        IndexedCommitPath.SHARED_GROUP,
        IndexedCommitStage.GROUP_FORCE,
        elapsed);
    return status;
  }

  private void failGroup(
      int count, IndexedGroupFailureStage stage, StatusCode status) {
    metrics.recordGroupFailure(stage, status, count);
    boolean decisionAppended = table.commitGroupDurabilityUncertain();
    StatusCode cancellation = table.cancelCommitGroup();
    StatusCode terminal = decisionAppended
        ? manager.failForcedCommitGroup(transactions, outcomes, count, status)
        : manager.failCommitGroup(transactions, outcomes, count, status);
    if (!terminal.isOk()) {
      table.fenceCommitWriter();
      StatusCode emergency = manager.terminalizeAcceptedCommitGroup(
          transactions, outcomes, count, StatusCode.INVARIANT_BROKEN);
      setAll(count, emergency.isOk() ? StatusCode.FENCED : terminal);
      return;
    }
    setAll(count, cancellation.isOk() || cancellation == StatusCode.FENCED
        ? status : StatusCode.FENCED);
  }

  private void abortPreparedGroup(int count, StatusCode failure) {
    StatusCode terminal = manager.abortPreparedCommitGroup(
        transactions, outcomes, count, failure);
    if (!terminal.isOk()) {
      table.fenceCommitWriter();
      StatusCode emergency = manager.terminalizeAcceptedCommitGroup(
          transactions, outcomes, count, StatusCode.INVARIANT_BROKEN);
      setAll(count, emergency.isOk() ? StatusCode.FENCED : terminal);
      return;
    }
    setAll(count, failure);
  }

  private void commitDirectly(int index, IndexedDirectCommitReason reason) {
    attributePath(index, IndexedCommitPath.DIRECT_COMMIT);
    metrics.recordDirectCommit(reason);
    statuses[index] = requests[index].session.commitDirect(outcomes[index]);
  }

  private void attributePaths(int count, IndexedCommitPath path) {
    for (int index = 0; index < count; index++) attributePath(index, path);
  }

  private void attributePath(int index, IndexedCommitPath path) {
    IndexedGroupCommitRequest request = requests[index];
    request.commitPath(path);
    long submitted = request.submittedNanos();
    if (submitted != 0) {
      metrics.recordStage(
          path,
          IndexedCommitStage.QUEUE_RESIDENCE,
          System.nanoTime() - submitted);
    }
  }

  private void setAll(int count, StatusCode status) {
    for (int index = 0; index < count; index++) statuses[index] = status;
  }
}
