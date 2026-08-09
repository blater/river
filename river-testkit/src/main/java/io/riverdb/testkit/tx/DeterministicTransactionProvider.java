package io.riverdb.testkit.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.tx.api.TransactionContext;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import io.riverdb.tx.api.Visibility;
import io.riverdb.tx.api.VisibilityResult;
import io.riverdb.tx.api.VisibilityState;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockService;
import io.riverdb.tx.api.lock.LockToken;
import io.riverdb.tx.api.version.VacuumResult;
import io.riverdb.tx.api.version.VersionPointer;
import io.riverdb.tx.api.version.VersionRecord;
import io.riverdb.tx.spi.RecoveryTransactionView;
import io.riverdb.tx.spi.TransactionStorage;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed-capacity single-owner semantic model for transaction API contract tests. It deliberately
 * contains no durable codec, page access, transaction manager, deadlock detector, or WAL logic.
 * Mutations belong to the construction thread; borrowed reads are synchronized for deterministic
 * observation. One bounded copy establishes provider ownership of appended version bytes.
 */
public final class DeterministicTransactionProvider
    implements TransactionStorage, Visibility, LockService {
  private static final AtomicLong PROVIDER_IDENTITIES = new AtomicLong();
  private static final byte VERSION_FREE = 0;
  private static final byte VERSION_LIVE = 1;
  private static final byte VERSION_ROLLED_BACK = 2;

  private final long databaseIncarnationHigh;
  private final long databaseIncarnationLow;
  private final long storeGeneration;
  private final long providerGeneration;
  private final long capabilityOwnerHigh;
  private final long capabilityOwnerLow;
  private final Thread ownerThread;

  private final byte[] versionStates;
  private final long[] versionAddresses;
  private final long[] versionOwners;
  private final long[] versionCommitSequences;
  private final long[] previousStoreGenerations;
  private final long[] previousAddresses;
  private final int[] versionPayloadLengths;
  private final byte[][] versionPayloads;

  private final long[] transactionIds;
  private final byte[] transactionStates;
  private final long[] transactionLastGenerations;
  private final long[] transactionLastLsns;
  private final long[] transactionUndoGenerations;
  private final long[] transactionUndoLsns;
  private final long[] transactionCommitSequences;

  private final boolean[] lockActive;
  private final long[] lockResourceHigh;
  private final long[] lockResourceLow;
  private final long[] lockTransactionIds;
  private final byte[] lockModes;
  private final long[] lockCapabilityTokens;

  private long nextVersionAddress = 1;
  private long nextLockToken = 1;

  public DeterministicTransactionProvider(
      long databaseHigh,
      long databaseLow,
      long versionStoreGeneration,
      int transactionCapacity,
      int versionCapacity,
      int maxVersionBytes,
      int lockCapacity) {
    databaseIncarnationHigh = databaseHigh;
    databaseIncarnationLow = databaseLow;
    storeGeneration = versionStoreGeneration;
    long identity = PROVIDER_IDENTITIES.incrementAndGet();
    providerGeneration = identity;
    capabilityOwnerHigh = mix(databaseHigh ^ versionStoreGeneration ^ identity);
    capabilityOwnerLow = mix(databaseLow ^ 0x54584c4f434b5350L ^ identity);
    ownerThread = Thread.currentThread();

    int boundedTransactions = Math.max(0, transactionCapacity);
    transactionIds = new long[boundedTransactions];
    transactionStates = new byte[boundedTransactions];
    transactionLastGenerations = new long[boundedTransactions];
    transactionLastLsns = new long[boundedTransactions];
    transactionUndoGenerations = new long[boundedTransactions];
    transactionUndoLsns = new long[boundedTransactions];
    transactionCommitSequences = new long[boundedTransactions];

    int boundedVersions = Math.max(0, versionCapacity);
    int boundedVersionBytes = Math.max(0, maxVersionBytes);
    versionStates = new byte[boundedVersions];
    versionAddresses = new long[boundedVersions];
    versionOwners = new long[boundedVersions];
    versionCommitSequences = new long[boundedVersions];
    previousStoreGenerations = new long[boundedVersions];
    previousAddresses = new long[boundedVersions];
    versionPayloadLengths = new int[boundedVersions];
    versionPayloads = new byte[boundedVersions][boundedVersionBytes];

    int boundedLocks = Math.max(0, lockCapacity);
    lockActive = new boolean[boundedLocks];
    lockResourceHigh = new long[boundedLocks];
    lockResourceLow = new long[boundedLocks];
    lockTransactionIds = new long[boundedLocks];
    lockModes = new byte[boundedLocks];
    lockCapabilityTokens = new long[boundedLocks];
  }

  @Override
  public synchronized StatusCode appendVersion(
      TransactionContext context,
      VersionRecord record,
      VersionPointer result,
      StatusDetail detail) {
    detail.reset();
    StatusCode owner = mutationOwner();
    if (!owner.isOk()) {
      return detail.set(owner).code();
    }
    StatusCode contextStatus = contextStatus(context);
    if (!contextStatus.isOk()) {
      return detail.set(contextStatus).code();
    }
    if (record.owningTransactionId() != context.transactionId()) {
      return detail.set(StatusCode.NOT_OWNER).code();
    }
    if (versionPayloads.length == 0
        || record.payloadLength() > versionPayloads[0].length) {
      return detail.set(StatusCode.RESOURCE_EXHAUSTED).code();
    }
    int slot = freeVersionSlot();
    if (slot < 0) {
      return detail.set(StatusCode.RESOURCE_EXHAUSTED).code();
    }
    long address = nextVersionAddress++;
    versionStates[slot] = VERSION_LIVE;
    versionAddresses[slot] = address;
    versionOwners[slot] = record.owningTransactionId();
    versionCommitSequences[slot] = record.cachedCommitSequence();
    previousStoreGenerations[slot] = record.previousStoreGeneration();
    previousAddresses[slot] = record.previousAddress();
    versionPayloadLengths[slot] = record.payloadLength();
    System.arraycopy(
        record.payloadArray(),
        record.payloadOffset(),
        versionPayloads[slot],
        0,
        record.payloadLength());
    result.set(storeGeneration, address);
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode readVersion(
      VersionPointer pointer,
      VersionRecord result,
      StatusDetail detail) {
    detail.reset();
    int slot = versionSlot(pointer);
    if (slot < 0) {
      return detail.set(StatusCode.CONFLICT).code();
    }
    result.set(
        versionOwners[slot],
        resolvedVersionCommitSequence(slot),
        previousStoreGenerations[slot],
        previousAddresses[slot],
        versionPayloads[slot],
        0,
        versionPayloadLengths[slot]);
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode applyRollback(
      TransactionContext context,
      VersionPointer pointer,
      StatusDetail detail) {
    detail.reset();
    StatusCode owner = mutationOwner();
    if (!owner.isOk()) {
      return detail.set(owner).code();
    }
    StatusCode contextStatus = contextStatus(context);
    if (!contextStatus.isOk()) {
      return detail.set(contextStatus).code();
    }
    int slot = versionSlot(pointer);
    if (slot < 0) {
      return detail.set(StatusCode.CONFLICT).code();
    }
    if (versionOwners[slot] != context.transactionId()) {
      return detail.set(StatusCode.NOT_OWNER).code();
    }
    versionStates[slot] = VERSION_ROLLED_BACK;
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode storeRecoveryView(
      RecoveryTransactionView view,
      StatusDetail detail) {
    detail.reset();
    StatusCode owner = mutationOwner();
    if (!owner.isOk()) {
      return detail.set(owner).code();
    }
    if (!matchesDatabase(
        view.databaseIncarnationHigh(), view.databaseIncarnationLow())) {
      return detail.set(StatusCode.CONFLICT).code();
    }
    int slot = transactionSlot(view.transactionId());
    if (slot < 0) {
      if (view.state() != TransactionState.ACTIVE) {
        return detail.set(StatusCode.CONFLICT).code();
      }
      slot = freeTransactionSlot();
      if (slot < 0) {
        return detail.set(StatusCode.RESOURCE_EXHAUSTED).code();
      }
      transactionIds[slot] = view.transactionId();
    } else {
      TransactionState current = transactionState(slot);
      if (!allowedTransition(current, view.state())) {
        return detail.set(StatusCode.CONFLICT).code();
      }
      if (lineageRegresses(slot, view)) {
        return detail.set(StatusCode.CONFLICT).code();
      }
    }
    if (view.state() == TransactionState.COMMITTED && view.commitSequence() == 0) {
      return detail.set(StatusCode.CONFLICT).code();
    }
    if (view.state() != TransactionState.COMMITTED
        && view.state() != TransactionState.INDETERMINATE
        && view.commitSequence() != 0) {
      return detail.set(StatusCode.CONFLICT).code();
    }
    transactionStates[slot] = (byte) (view.state().ordinal() + 1);
    transactionLastGenerations[slot] = view.lastRecordGeneration();
    transactionLastLsns[slot] = view.lastRecordLsn();
    transactionUndoGenerations[slot] = view.undoNextGeneration();
    transactionUndoLsns[slot] = view.undoNextLsn();
    transactionCommitSequences[slot] = view.commitSequence();
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode lookupOutcome(
      long databaseHigh,
      long databaseLow,
      long transactionId,
      TransactionOutcome result,
      StatusDetail detail) {
    detail.reset();
    result.reset();
    if (!matchesDatabase(databaseHigh, databaseLow)) {
      return detail.set(StatusCode.CONFLICT).code();
    }
    int slot = transactionSlot(transactionId);
    if (slot < 0) {
      return detail.set(StatusCode.RETRY).code();
    }
    result.set(
        databaseIncarnationHigh,
        databaseIncarnationLow,
        transactionId,
        transactionState(slot),
        transactionCommitSequences[slot]);
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode lookupRecoveryTransaction(
      long databaseHigh,
      long databaseLow,
      long transactionId,
      RecoveryTransactionView result,
      StatusDetail detail) {
    detail.reset();
    result.reset();
    if (!matchesDatabase(databaseHigh, databaseLow)) {
      return detail.set(StatusCode.CONFLICT).code();
    }
    int slot = transactionSlot(transactionId);
    if (slot < 0) {
      return detail.set(StatusCode.RETRY).code();
    }
    result.set(
        databaseIncarnationHigh,
        databaseIncarnationLow,
        transactionId,
        transactionState(slot),
        transactionLastGenerations[slot],
        transactionLastLsns[slot],
        transactionUndoGenerations[slot],
        transactionUndoLsns[slot],
        transactionCommitSequences[slot]);
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode vacuumBefore(
      long visibleCommitSequenceExclusive,
      int maxRecords,
      VacuumResult result,
      StatusDetail detail) {
    detail.reset();
    StatusCode owner = mutationOwner();
    if (!owner.isOk()) {
      return detail.set(owner).code();
    }
    int inspected = 0;
    int reclaimed = 0;
    boolean moreWork = false;
    for (int slot = 0; slot < versionStates.length; slot++) {
      if (versionStates[slot] == VERSION_FREE) {
        continue;
      }
      if (inspected == maxRecords) {
        moreWork = true;
        break;
      }
      inspected++;
      long commitSequence = resolvedVersionCommitSequence(slot);
      if (versionStates[slot] == VERSION_ROLLED_BACK
          || (commitSequence != 0 && commitSequence < visibleCommitSequenceExclusive)) {
        clearVersion(slot);
        reclaimed++;
      }
    }
    result.set(inspected, reclaimed, moreWork);
    return moreWork ? StatusCode.RETRY : StatusCode.OK;
  }

  @Override
  public synchronized StatusCode resolve(
      TransactionContext context,
      long owningTransactionId,
      long cachedCommitSequence,
      VisibilityResult result,
      StatusDetail detail) {
    detail.reset();
    StatusCode contextStatus = contextStatus(context);
    if (!contextStatus.isOk()) {
      return detail.set(contextStatus).code();
    }
    if (owningTransactionId == context.transactionId()) {
      result.set(VisibilityState.OWN_WRITE, 0);
      return StatusCode.OK;
    }
    long commitSequence = cachedCommitSequence;
    if (commitSequence == 0) {
      int slot = transactionSlot(owningTransactionId);
      if (slot < 0) {
        result.set(VisibilityState.OUTCOME_UNAVAILABLE, 0);
        return detail.set(StatusCode.RETRY).code();
      }
      TransactionState state = transactionState(slot);
      if (state == TransactionState.INDETERMINATE) {
        result.set(VisibilityState.INDETERMINATE, transactionCommitSequences[slot]);
        return detail.set(StatusCode.FENCED).code();
      }
      if (state != TransactionState.COMMITTED) {
        result.set(VisibilityState.HIDDEN, 0);
        return StatusCode.OK;
      }
      commitSequence = transactionCommitSequences[slot];
    }
    boolean visible = commitSequence <= context.snapshot().visibleCommitSequence()
        && !context.snapshot().excludesTransaction(owningTransactionId);
    result.set(visible ? VisibilityState.VISIBLE : VisibilityState.HIDDEN, commitSequence);
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode tryAcquire(
      TransactionContext context,
      LockRequest request,
      long nowNanos,
      LockToken token,
      StatusDetail detail) {
    detail.reset();
    StatusCode owner = mutationOwner();
    if (!owner.isOk()) {
      return detail.set(owner).code();
    }
    StatusCode contextStatus = contextStatus(context);
    if (!contextStatus.isOk()) {
      return detail.set(contextStatus).code();
    }
    if (token.isActive()) {
      return detail.set(StatusCode.CONFLICT).code();
    }
    if (request.deadlineNanos() != 0 && nowNanos >= request.deadlineNanos()) {
      return detail.set(StatusCode.TIMEOUT).code();
    }
    for (int slot = 0; slot < lockActive.length; slot++) {
      if (lockActive[slot]
          && lockResourceHigh[slot] == request.resourceHigh()
          && lockResourceLow[slot] == request.resourceLow()
          && lockTransactionIds[slot] != context.transactionId()) {
        LockMode existing = LockMode.values()[lockModes[slot]];
        if (request.mode().conflictsWith(existing) || existing.conflictsWith(request.mode())) {
          return detail.set(StatusCode.RETRY).code();
        }
      }
    }
    int slot = freeLockSlot();
    if (slot < 0) {
      return detail.set(StatusCode.RESOURCE_EXHAUSTED).code();
    }
    long capability = nextLockToken++;
    lockActive[slot] = true;
    lockResourceHigh[slot] = request.resourceHigh();
    lockResourceLow[slot] = request.resourceLow();
    lockTransactionIds[slot] = context.transactionId();
    lockModes[slot] = (byte) request.mode().ordinal();
    lockCapabilityTokens[slot] = capability;
    StatusCode claimed = token.claim(
        capabilityOwnerHigh,
        capabilityOwnerLow,
        providerGeneration,
        capability,
        context.transactionId(),
        slot);
    if (!claimed.isOk()) {
      clearLock(slot);
      return detail.set(claimed).code();
    }
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode release(LockToken token, StatusDetail detail) {
    detail.reset();
    StatusCode owner = mutationOwner();
    if (!owner.isOk()) {
      return detail.set(owner).code();
    }
    if (!token.isActive()
        || !token.isOwnedBy(capabilityOwnerHigh, capabilityOwnerLow)
        || token.providerGeneration() != providerGeneration) {
      return detail.set(StatusCode.CONFLICT).code();
    }
    int slot = token.slot();
    if (slot < 0
        || slot >= lockActive.length
        || !lockActive[slot]
        || lockCapabilityTokens[slot] != token.capabilityToken()
        || lockTransactionIds[slot] != token.transactionId()) {
      return detail.set(StatusCode.CONFLICT).code();
    }
    clearLock(slot);
    return token.complete(capabilityOwnerHigh, capabilityOwnerLow);
  }

  public long storeGeneration() {
    return storeGeneration;
  }

  private StatusCode contextStatus(TransactionContext context) {
    if (!matchesDatabase(
        context.databaseIncarnationHigh(), context.databaseIncarnationLow())) {
      return StatusCode.CONFLICT;
    }
    return context.cancellation().status();
  }

  private StatusCode mutationOwner() {
    return ownerThread == Thread.currentThread() ? StatusCode.OK : StatusCode.NOT_OWNER;
  }

  private boolean matchesDatabase(long high, long low) {
    return high == databaseIncarnationHigh && low == databaseIncarnationLow;
  }

  private int freeVersionSlot() {
    for (int slot = 0; slot < versionStates.length; slot++) {
      if (versionStates[slot] == VERSION_FREE) {
        return slot;
      }
    }
    return -1;
  }

  private int versionSlot(VersionPointer pointer) {
    if (pointer.storeGeneration() != storeGeneration || pointer.address() == 0) {
      return -1;
    }
    for (int slot = 0; slot < versionStates.length; slot++) {
      if (versionStates[slot] != VERSION_FREE
          && versionAddresses[slot] == pointer.address()) {
        return slot;
      }
    }
    return -1;
  }

  private long resolvedVersionCommitSequence(int slot) {
    if (versionCommitSequences[slot] != 0) {
      return versionCommitSequences[slot];
    }
    int transactionSlot = transactionSlot(versionOwners[slot]);
    if (transactionSlot >= 0
        && transactionState(transactionSlot) == TransactionState.COMMITTED) {
      return transactionCommitSequences[transactionSlot];
    }
    return 0;
  }

  private void clearVersion(int slot) {
    versionStates[slot] = VERSION_FREE;
    versionAddresses[slot] = 0;
    versionOwners[slot] = 0;
    versionCommitSequences[slot] = 0;
    previousStoreGenerations[slot] = 0;
    previousAddresses[slot] = 0;
    versionPayloadLengths[slot] = 0;
  }

  private int transactionSlot(long transactionId) {
    for (int slot = 0; slot < transactionIds.length; slot++) {
      if (transactionStates[slot] != 0 && transactionIds[slot] == transactionId) {
        return slot;
      }
    }
    return -1;
  }

  private int freeTransactionSlot() {
    for (int slot = 0; slot < transactionIds.length; slot++) {
      if (transactionStates[slot] == 0) {
        return slot;
      }
    }
    return -1;
  }

  private TransactionState transactionState(int slot) {
    return TransactionState.values()[transactionStates[slot] - 1];
  }

  private static boolean allowedTransition(TransactionState current, TransactionState next) {
    if (current == next) {
      return true;
    }
    return switch (current) {
      case ACTIVE -> next == TransactionState.COMMITTING || next == TransactionState.ABORTING;
      case COMMITTING -> next == TransactionState.COMMITTED
          || next == TransactionState.INDETERMINATE;
      case ABORTING -> next == TransactionState.ABORTED
          || next == TransactionState.INDETERMINATE;
      case COMMITTED, ABORTED, INDETERMINATE -> false;
    };
  }

  private boolean lineageRegresses(int slot, RecoveryTransactionView next) {
    long currentGeneration = transactionLastGenerations[slot];
    long nextGeneration = next.lastRecordGeneration();
    return nextGeneration < currentGeneration
        || (nextGeneration == currentGeneration
            && next.lastRecordLsn() < transactionLastLsns[slot]);
  }

  private int freeLockSlot() {
    for (int slot = 0; slot < lockActive.length; slot++) {
      if (!lockActive[slot]) {
        return slot;
      }
    }
    return -1;
  }

  private void clearLock(int slot) {
    lockActive[slot] = false;
    lockResourceHigh[slot] = 0;
    lockResourceLow[slot] = 0;
    lockTransactionIds[slot] = 0;
    lockModes[slot] = 0;
    lockCapabilityTokens[slot] = 0;
  }

  private static long mix(long value) {
    value ^= value >>> 33;
    value *= 0xff51afd7ed558ccdl;
    value ^= value >>> 33;
    value *= 0xc4ceb9fe1a85ec53l;
    value ^= value >>> 33;
    return value == 0 ? 1 : value;
  }
}
