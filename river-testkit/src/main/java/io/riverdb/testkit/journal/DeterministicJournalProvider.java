package io.riverdb.testkit.journal;

import io.riverdb.base.concurrent.CancellationToken;
import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.CommitSequence;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.IdempotencyKey;
import io.riverdb.base.id.JournalPosition;
import io.riverdb.base.id.RequestId;
import io.riverdb.base.id.TransactionId;
import io.riverdb.journal.api.JournalAppendRequest;
import io.riverdb.journal.api.JournalAppendResult;
import io.riverdb.journal.api.JournalProvider;
import io.riverdb.journal.api.JournalReservation;
import io.riverdb.journal.api.JournalReserveRequest;
import io.riverdb.journal.api.NodeIncarnation;
import io.riverdb.journal.api.durability.DurabilityOutcome;
import io.riverdb.journal.api.durability.DurabilityRequirement;
import io.riverdb.journal.api.durability.DurabilityResult;
import io.riverdb.journal.api.durability.DurabilityTicket;
import io.riverdb.journal.api.durability.DurabilityWaitRequest;
import io.riverdb.journal.api.durability.JournalCapabilities;
import io.riverdb.journal.api.frontier.JournalFrontierSnapshot;
import io.riverdb.journal.api.mapping.JournalPositionMapping;
import io.riverdb.journal.api.outcome.RequestOutcomeResult;
import io.riverdb.journal.api.outcome.RequestOutcomeState;
import io.riverdb.journal.api.outcome.TransactionDecision;
import io.riverdb.journal.api.retention.RetentionOwnerKind;
import io.riverdb.journal.api.retention.RetentionSnapshot;
import io.riverdb.journal.api.retention.WalRetentionLease;
import io.riverdb.journal.api.retention.WalRetentionLeaseRequest;
import java.nio.ByteBuffer;

/**
 * Fixed-capacity single-process journal model for the provider contract suite. It deliberately
 * models semantic state only: it is not a WAL block, segment, checksum, or consensus
 * implementation. The fake copies payload bytes into preallocated stable-lifetime slots so
 * retained-byte capacity is explicit and bounded.
 *
 * <p>The construction thread owns mutations; synchronized observation permits deterministic
 * concurrent frontier readers. Common operations return stable statuses and populate caller-owned
 * carriers. Numeric allocation/copy budgets remain provisional until P09 measures production
 * reservation and framing alternatives.
 */
public final class DeterministicJournalProvider implements JournalProvider {
  private static final byte FREE = 0;
  private static final byte RESERVED = 1;
  private static final byte PUBLISHED = 2;
  private static final byte WRITTEN = 3;
  private static final byte DURABLE = 4;
  private static final int FRAME_OVERHEAD_BYTES = 16;

  private final DatabaseIncarnation databaseIncarnation;
  private final long journalGeneration;
  private final long walGeneration;
  private final JournalCapabilities capabilities;
  private final FatalStateFence fatalState;
  private final Thread ownerThread;
  private final byte[] states;
  private final long[] sequences;
  private final long[] reservationTokens;
  private final int[] payloadLengths;
  private final byte[][] payloads;
  private final long[] walStarts;
  private final long[] walEnds;
  private final long[] requestHigh;
  private final long[] requestLow;
  private final long[] idempotencyHigh;
  private final long[] idempotencyLow;
  private final long[] transactionIds;
  private final long[] commitSequences;
  private final int[] formatIds;
  private final int[] formatVersions;
  private final byte[] transactionDecisions;
  private final byte[] requestedDurability;
  private final boolean[] unknownOutcomes;
  private final long[] leaseIds;
  private final long[] leaseTokens;
  private final long[] leaseMinimumSequences;
  private final long[] leaseExpiries;
  private final byte[] leaseOwnerKinds;
  private final long maxLeaseDurationNanos;
  private NodeIncarnation nodeIncarnation;
  private long providerToken = 1;
  private long lifecycleToken = 1;
  private long nextSequence = 1;
  private long nextWalStart;
  private long preparedSequence;
  private long committedSequence;
  private long durableSequence;
  private long durableEndExclusive;
  private int retainedEntries;
  private DurabilityOutcome terminalWaitOutcome = DurabilityOutcome.PENDING;

  public DeterministicJournalProvider(
      DatabaseIncarnation database,
      NodeIncarnation node,
      long generation,
      long localWalGeneration,
      int capacity,
      int maxEntryBytes,
      FatalStateFence fence) {
    this(database, node, generation, localWalGeneration, capacity, maxEntryBytes, 4,
        1_000_000_000L, fence);
  }

  public DeterministicJournalProvider(
      DatabaseIncarnation database,
      NodeIncarnation node,
      long generation,
      long localWalGeneration,
      int capacity,
      int maxEntryBytes,
      int maxRetentionLeases,
      long maximumLeaseDurationNanos,
      FatalStateFence fence) {
    databaseIncarnation = database;
    nodeIncarnation = node;
    journalGeneration = generation;
    walGeneration = localWalGeneration;
    capabilities = JournalCapabilities.LOCAL_ONLY;
    fatalState = fence;
    ownerThread = Thread.currentThread();
    int boundedCapacity = Math.max(0, capacity);
    int boundedEntryBytes = Math.max(0, maxEntryBytes);
    states = new byte[boundedCapacity];
    sequences = new long[boundedCapacity];
    reservationTokens = new long[boundedCapacity];
    payloadLengths = new int[boundedCapacity];
    payloads = new byte[boundedCapacity][boundedEntryBytes];
    walStarts = new long[boundedCapacity];
    walEnds = new long[boundedCapacity];
    requestHigh = new long[boundedCapacity];
    requestLow = new long[boundedCapacity];
    idempotencyHigh = new long[boundedCapacity];
    idempotencyLow = new long[boundedCapacity];
    transactionIds = new long[boundedCapacity];
    commitSequences = new long[boundedCapacity];
    formatIds = new int[boundedCapacity];
    formatVersions = new int[boundedCapacity];
    transactionDecisions = new byte[boundedCapacity];
    requestedDurability = new byte[boundedCapacity];
    unknownOutcomes = new boolean[boundedCapacity];
    int boundedLeaseCount = Math.max(0, maxRetentionLeases);
    leaseIds = new long[boundedLeaseCount];
    leaseTokens = new long[boundedLeaseCount];
    leaseMinimumSequences = new long[boundedLeaseCount];
    leaseExpiries = new long[boundedLeaseCount];
    leaseOwnerKinds = new byte[boundedLeaseCount];
    maxLeaseDurationNanos = Math.max(0, maximumLeaseDurationNanos);
  }

  @Override
  public DatabaseIncarnation databaseIncarnation() {
    return databaseIncarnation;
  }

  @Override
  public synchronized NodeIncarnation nodeIncarnation() {
    return nodeIncarnation;
  }

  @Override
  public JournalCapabilities capabilities() {
    return capabilities;
  }

  @Override
  public synchronized StatusCode reserve(
      JournalReserveRequest request,
      JournalReservation reservation,
      StatusDetail detail) {
    reservation.reset();
    detail.reset();
    StatusCode admission = admission(request.databaseIncarnation(), request.nodeIncarnation());
    if (!admission.isOk()) {
      return detail.set(admission).code();
    }
    if (!capabilities.supports(request.durabilityRequirement())) {
      return detail.set(StatusCode.INVALID_EXTERNAL_INPUT)
          .append("unsupported durability requirement")
          .code();
    }
    if ((request.requestIdHigh() == 0 && request.requestIdLow() == 0)
        || (request.idempotencyKeyHigh() == 0 && request.idempotencyKeyLow() == 0)
        || request.payloadBytes() < 0
        || (payloads.length > 0 && request.payloadBytes() > payloads[0].length)) {
      return detail.set(StatusCode.INVALID_EXTERNAL_INPUT).code();
    }
    int duplicateSlot = findIdempotency(
        request.idempotencyKeyHigh(), request.idempotencyKeyLow());
    if (duplicateSlot >= 0) {
      return detail.set(StatusCode.CONFLICT).append("duplicate idempotency key").code();
    }
    if (retainedEntries == states.length) {
      return detail.set(StatusCode.RESOURCE_EXHAUSTED).append("journal capacity").code();
    }
    int slot = slot(nextSequence);
    if (states[slot] != FREE) {
      return detail.set(StatusCode.RESOURCE_EXHAUSTED).append("retained ring slot").code();
    }
    long sequence = nextSequence++;
    long frameBytes = FRAME_OVERHEAD_BYTES + request.payloadBytes();
    long end = nextWalStart + frameBytes;
    if (end < nextWalStart) {
      return detail.set(StatusCode.RESOURCE_EXHAUSTED).append("local LSN overflow").code();
    }
    states[slot] = RESERVED;
    sequences[slot] = sequence;
    long reservationToken = ++providerToken;
    reservationTokens[slot] = reservationToken;
    payloadLengths[slot] = request.payloadBytes();
    walStarts[slot] = nextWalStart;
    walEnds[slot] = end;
    requestHigh[slot] = request.requestIdHigh();
    requestLow[slot] = request.requestIdLow();
    idempotencyHigh[slot] = request.idempotencyKeyHigh();
    idempotencyLow[slot] = request.idempotencyKeyLow();
    nextWalStart = end;
    retainedEntries++;
    requestedDurability[slot] = (byte) request.durabilityRequirement().ordinal();
    reservation.assign(
        databaseIncarnation.high(),
        databaseIncarnation.low(),
        journalGeneration,
        sequence,
        reservationToken,
        slot,
        request.payloadBytes());
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode publish(
      JournalReservation reservation,
      JournalAppendRequest request,
      JournalAppendResult result,
      StatusDetail detail) {
    result.reset();
    detail.reset();
    StatusCode admission = checkOwnerAndFence();
    if (!admission.isOk()) {
      return detail.set(admission).code();
    }
    int slot = validateReservation(reservation);
    if (slot < 0) {
      return detail.set(StatusCode.CONFLICT).append("stale reservation").code();
    }
    ByteBuffer payload = request.payload();
    if (payload == null
        || payload.remaining() != payloadLengths[slot]
        || request.formatId() <= 0
        || request.formatVersion() <= 0
        || (request.transactionDecision() == TransactionDecision.COMMITTED
            && (request.transactionId() <= 0 || request.commitSequence() <= 0))
        || (request.transactionDecision() == TransactionDecision.ABORTED
            && (request.transactionId() <= 0 || request.commitSequence() != 0))
        || (request.transactionDecision() == TransactionDecision.NONE
            && request.commitSequence() != 0)) {
      return detail.set(StatusCode.INVALID_EXTERNAL_INPUT).code();
    }
    int payloadPosition = payload.position();
    for (int index = 0; index < payloadLengths[slot]; index++) {
      payloads[slot][index] = payload.get(payloadPosition + index);
    }
    transactionIds[slot] = request.transactionId();
    commitSequences[slot] = request.commitSequence();
    formatIds[slot] = request.formatId();
    formatVersions[slot] = request.formatVersion();
    transactionDecisions[slot] = (byte) request.transactionDecision().ordinal();
    states[slot] = PUBLISHED;
    reservation.complete();
    advancePublishedFrontiers();
    result.set(
        databaseIncarnation.high(),
        databaseIncarnation.low(),
        journalGeneration,
        sequences[slot],
        walGeneration,
        walStarts[slot],
        walEnds[slot],
        false);
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode cancelReservation(
      JournalReservation reservation,
      StatusDetail detail) {
    detail.reset();
    StatusCode admission = checkOwnerAndFence();
    if (!admission.isOk()) {
      return detail.set(admission).code();
    }
    int slot = validateReservation(reservation);
    if (slot < 0) {
      return detail.set(StatusCode.CONFLICT).append("stale reservation").code();
    }
    payloadLengths[slot] = 0;
    transactionIds[slot] = 0;
    commitSequences[slot] = 0;
    transactionDecisions[slot] = 0;
    states[slot] = PUBLISHED;
    reservation.complete();
    advancePublishedFrontiers();
    return StatusCode.CANCELLED;
  }

  @Override
  public synchronized StatusCode beginDurabilityWait(
      DurabilityWaitRequest request,
      DurabilityTicket ticket,
      DurabilityResult result,
      StatusDetail detail) {
    ticket.reset();
    result.reset();
    detail.reset();
    StatusCode admission = admission(request.databaseIncarnation(), request.nodeIncarnation());
    if (!admission.isOk()) {
      setDurabilityResult(result, DurabilityOutcome.FENCED, request.requirement(), 0);
      return detail.set(admission).code();
    }
    if (!capabilities.supports(request.requirement())) {
      setDurabilityResult(result, DurabilityOutcome.UNSUPPORTED, request.requirement(), 0);
      return detail.set(StatusCode.INVALID_EXTERNAL_INPUT).code();
    }
    if (request.journalGeneration() != journalGeneration
        || request.requiredSequence() <= 0
        || request.requiredSequence() >= nextSequence
        || request.deadlineNanos() < 0) {
      return detail.set(StatusCode.INVALID_EXTERNAL_INPUT).code();
    }
    if (durableSequence >= request.requiredSequence()) {
      setDurabilityResult(
          result, DurabilityOutcome.SATISFIED, request.requirement(), request.requiredSequence());
      return StatusCode.OK;
    }
    ticket.assign(lifecycleToken, journalGeneration, request.requiredSequence(),
        request.requirement(), request.deadlineNanos());
    return StatusCode.RETRY;
  }

  @Override
  public synchronized StatusCode pollDurability(
      DurabilityTicket ticket,
      long nowNanos,
      CancellationToken cancellation,
      DurabilityResult result,
      StatusDetail detail) {
    result.reset();
    detail.reset();
    if (!ticket.isActive()) {
      return detail.set(StatusCode.CONFLICT).append("inactive durability ticket").code();
    }
    if (ticket.providerToken() != lifecycleToken
        || ticket.journalGeneration() != journalGeneration) {
      ticket.complete();
      setDurabilityResult(result, DurabilityOutcome.FENCED, ticket.requirement(), 0);
      return detail.set(StatusCode.FENCED).code();
    }
    if (fatalState.isFenced()) {
      ticket.complete();
      DurabilityOutcome outcome = terminalWaitOutcome == DurabilityOutcome.UNKNOWN
          ? DurabilityOutcome.UNKNOWN
          : DurabilityOutcome.FENCED;
      setDurabilityResult(result, outcome, ticket.requirement(), 0);
      return detail.set(StatusCode.FENCED).code();
    }
    if (cancellation.isCancellationRequested()) {
      ticket.complete();
      setDurabilityResult(result, DurabilityOutcome.CANCELLED, ticket.requirement(), 0);
      return detail.set(StatusCode.CANCELLED).code();
    }
    if (durableSequence >= ticket.requiredSequence()) {
      ticket.complete();
      setDurabilityResult(
          result, DurabilityOutcome.SATISFIED, ticket.requirement(), ticket.requiredSequence());
      return StatusCode.OK;
    }
    if (ticket.deadlineNanos() != 0 && nowNanos >= ticket.deadlineNanos()) {
      ticket.complete();
      setDurabilityResult(result, DurabilityOutcome.TIMED_OUT, ticket.requirement(), 0);
      return detail.set(StatusCode.TIMEOUT).code();
    }
    return StatusCode.RETRY;
  }

  @Override
  public synchronized StatusCode cancelDurabilityWait(
      DurabilityTicket ticket,
      DurabilityResult result,
      StatusDetail detail) {
    result.reset();
    detail.reset();
    if (!ticket.isActive()) {
      return detail.set(StatusCode.CONFLICT).code();
    }
    ticket.complete();
    setDurabilityResult(result, DurabilityOutcome.CANCELLED, ticket.requirement(), 0);
    return detail.set(StatusCode.CANCELLED).code();
  }

  @Override
  public synchronized StatusCode snapshotFrontiers(JournalFrontierSnapshot result) {
    result.set(
        databaseIncarnation.high(),
        databaseIncarnation.low(),
        journalGeneration,
        preparedSequence,
        0,
        committedSequence,
        durableSequence,
        0);
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode inspectMapping(
      JournalPosition requestedPosition,
      JournalPositionMapping result) {
    result.reset();
    if (!sameJournalLineage(requestedPosition)) {
      return StatusCode.FENCED;
    }
    int slot = findSequence(requestedPosition.sequence());
    if (slot < 0 || states[slot] == RESERVED) {
      return StatusCode.RETRY;
    }
    result.set(
        databaseIncarnation.high(),
        databaseIncarnation.low(),
        journalGeneration,
        requestedPosition.sequence(),
        walGeneration,
        walStarts[slot],
        walEnds[slot],
        transactionIds[slot],
        commitSequences[slot],
        transactionDecisions[slot] != 0);
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode lookupOutcome(
      DatabaseIncarnation database,
      NodeIncarnation node,
      IdempotencyKey idempotencyKey,
      RequestOutcomeResult result,
      StatusDetail detail) {
    result.reset();
    detail.reset();
    StatusCode owner = checkOwner();
    if (!owner.isOk()) {
      return detail.set(owner).code();
    }
    if (!databaseIncarnation.equals(database) || !nodeIncarnation.equals(node)) {
      return detail.set(StatusCode.FENCED).code();
    }
    if (!idempotencyKey.isValid()) {
      return detail.set(StatusCode.INVALID_EXTERNAL_INPUT).code();
    }
    int slot = findIdempotency(idempotencyKey.high(), idempotencyKey.low());
    if (slot < 0) {
      return StatusCode.OK;
    }
    TransactionDecision decision = TransactionDecision.values()[transactionDecisions[slot]];
    RequestOutcomeState outcomeState;
    boolean finalOutcome = false;
    if (unknownOutcomes[slot]) {
      outcomeState = RequestOutcomeState.UNKNOWN;
    } else if (states[slot] == RESERVED) {
      outcomeState = RequestOutcomeState.RESERVED;
    } else if (states[slot] == DURABLE) {
      outcomeState = RequestOutcomeState.DURABLE;
      finalOutcome = decision != TransactionDecision.NONE;
    } else if (decision != TransactionDecision.NONE) {
      outcomeState = RequestOutcomeState.DECIDED;
    } else {
      outcomeState = RequestOutcomeState.PUBLISHED;
    }
    result.set(
        outcomeState,
        RequestId.of(requestHigh[slot], requestLow[slot]),
        IdempotencyKey.of(idempotencyHigh[slot], idempotencyLow[slot]),
        databaseIncarnation.high(),
        databaseIncarnation.low(),
        journalGeneration,
        sequences[slot],
        transactionIds[slot] == 0 ? TransactionId.NONE : TransactionId.of(transactionIds[slot]),
        commitSequences[slot] == 0
            ? CommitSequence.NONE
            : CommitSequence.of(commitSequences[slot]),
        decision,
        DurabilityRequirement.values()[requestedDurability[slot]],
        finalOutcome);
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode acquireRetentionLease(
      WalRetentionLeaseRequest request,
      WalRetentionLease lease,
      StatusDetail detail) {
    lease.reset();
    detail.reset();
    StatusCode admission = admission(request.databaseIncarnation(), request.nodeIncarnation());
    if (!admission.isOk()) {
      return detail.set(admission).code();
    }
    if (!validLeaseRequest(request)) {
      return detail.set(StatusCode.INVALID_EXTERNAL_INPUT).code();
    }
    expireLeases(request.nowNanos());
    if (findLeaseId(request.leaseId()) >= 0) {
      return detail.set(StatusCode.INVALID_EXTERNAL_INPUT).code();
    }
    int slot = findFreeLease();
    if (slot < 0) {
      return detail.set(StatusCode.RESOURCE_EXHAUSTED).append("retention lease capacity").code();
    }
    long token = ++providerToken;
    leaseIds[slot] = request.leaseId();
    leaseTokens[slot] = token;
    leaseMinimumSequences[slot] = request.minimumRequired().sequence();
    leaseExpiries[slot] = request.expiresAtNanos();
    leaseOwnerKinds[slot] = (byte) request.ownerKind().ordinal();
    lease.assign(token, nodeIncarnation, request.leaseId(), request.ownerKind(),
        request.minimumRequired(), request.expiresAtNanos());
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode renewRetentionLease(
      WalRetentionLease lease,
      WalRetentionLeaseRequest request,
      StatusDetail detail) {
    detail.reset();
    StatusCode admission = admission(request.databaseIncarnation(), request.nodeIncarnation());
    if (!admission.isOk()) {
      return detail.set(admission).code();
    }
    if (!lease.nodeIncarnation().equals(nodeIncarnation)) {
      return detail.set(StatusCode.FENCED).code();
    }
    expireLeases(request.nowNanos());
    int slot = findLease(lease);
    if (slot < 0
        || !validLeaseRequest(request)
        || request.leaseId() != lease.leaseId()
        || request.ownerKind() != lease.ownerKind()
        || request.minimumRequired().sequence() < leaseMinimumSequences[slot]) {
      return detail.set(StatusCode.CONFLICT).code();
    }
    leaseMinimumSequences[slot] = request.minimumRequired().sequence();
    leaseExpiries[slot] = request.expiresAtNanos();
    lease.assign(leaseTokens[slot], nodeIncarnation, request.leaseId(), request.ownerKind(),
        request.minimumRequired(), request.expiresAtNanos());
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode reopenRetentionLease(
      DatabaseIncarnation database,
      NodeIncarnation node,
      long leaseId,
      long nowNanos,
      WalRetentionLease lease,
      StatusDetail detail) {
    lease.reset();
    detail.reset();
    StatusCode admission = admission(database, node);
    if (!admission.isOk()) {
      return detail.set(admission).code();
    }
    expireLeases(nowNanos);
    int slot = findLeaseId(leaseId);
    if (slot < 0) {
      return StatusCode.RETRY;
    }
    long token = ++providerToken;
    leaseTokens[slot] = token;
    RetentionOwnerKind owner = RetentionOwnerKind.values()[leaseOwnerKinds[slot]];
    lease.assign(
        token,
        nodeIncarnation,
        leaseIds[slot],
        owner,
        position(leaseMinimumSequences[slot]),
        leaseExpiries[slot]);
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode releaseRetentionLease(
      WalRetentionLease lease,
      StatusDetail detail) {
    detail.reset();
    StatusCode admission = checkOwnerAndFence();
    if (!admission.isOk()) {
      return detail.set(admission).code();
    }
    if (!lease.nodeIncarnation().equals(nodeIncarnation)) {
      return detail.set(StatusCode.FENCED).code();
    }
    int slot = findLease(lease);
    if (slot < 0) {
      return detail.set(StatusCode.CONFLICT).code();
    }
    clearLease(slot);
    lease.complete();
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode snapshotRetention(long nowNanos, RetentionSnapshot result) {
    int active = 0;
    long oldest = 0;
    long earliestExpiry = 0;
    for (int index = 0; index < leaseIds.length; index++) {
      if (leaseIds[index] == 0 || nowNanos >= leaseExpiries[index]) {
        continue;
      }
      active++;
      if (oldest == 0 || leaseMinimumSequences[index] < oldest) {
        oldest = leaseMinimumSequences[index];
      }
      if (earliestExpiry == 0 || leaseExpiries[index] < earliestExpiry) {
        earliestExpiry = leaseExpiries[index];
      }
    }
    result.set(active, oldest == 0 ? JournalPosition.NONE : position(oldest), earliestExpiry);
    return StatusCode.OK;
  }

  /** Reclaims only durable entries strictly below every active semantic retention consumer. */
  public synchronized StatusCode reclaimThrough(
      JournalPosition requestedInclusive,
      long nowNanos) {
    StatusCode admission = checkOwnerAndFence();
    if (!admission.isOk()) {
      return admission;
    }
    if (!sameJournalLineage(requestedInclusive)) {
      return StatusCode.FENCED;
    }
    expireLeases(nowNanos);
    long permitted = Math.min(requestedInclusive.sequence(), durableSequence);
    for (int index = 0; index < leaseIds.length; index++) {
      if (leaseIds[index] != 0) {
        permitted = Math.min(permitted, leaseMinimumSequences[index] - 1);
      }
    }
    for (int index = 0; index < states.length; index++) {
      if (states[index] == DURABLE && sequences[index] <= permitted) {
        clearSlot(index);
        retainedEntries--;
      }
    }
    return StatusCode.OK;
  }

  /** Completes deterministic write submission through an inclusive logical position. */
  public synchronized StatusCode writeThrough(JournalPosition inclusivePosition) {
    if (!sameJournalLineage(inclusivePosition)) {
      return StatusCode.FENCED;
    }
    return writeThrough(inclusivePosition.generation(), inclusivePosition.sequence());
  }

  /** Allocation-free test control equivalent of the cold position-based write hook. */
  public synchronized StatusCode writeThrough(long generation, long inclusiveSequence) {
    StatusCode admission = checkOwnerAndFence();
    if (!admission.isOk()) {
      return admission;
    }
    if (generation != journalGeneration || inclusiveSequence <= 0) {
      return StatusCode.FENCED;
    }
    for (int index = 0; index < states.length; index++) {
      if (sequences[index] <= inclusiveSequence && states[index] == PUBLISHED) {
        states[index] = WRITTEN;
      }
    }
    return StatusCode.OK;
  }

  /** Completes a force attempt through an inclusive logical position. */
  public synchronized StatusCode forceThrough(
      JournalPosition inclusivePosition,
      ForceCompletion completion) {
    if (!sameJournalLineage(inclusivePosition)) {
      return StatusCode.FENCED;
    }
    return forceThrough(inclusivePosition.generation(), inclusivePosition.sequence(), completion);
  }

  /** Allocation-free test control equivalent of the cold position-based force hook. */
  public synchronized StatusCode forceThrough(
      long generation,
      long inclusiveSequence,
      ForceCompletion completion) {
    StatusCode admission = checkOwnerAndFence();
    if (!admission.isOk()) {
      return admission;
    }
    if (generation != journalGeneration || inclusiveSequence <= 0) {
      return StatusCode.FENCED;
    }
    if (completion == ForceCompletion.FAILED) {
      return StatusCode.IO_FAILURE;
    }
    if (completion == ForceCompletion.UNKNOWN) {
      terminalWaitOutcome = DurabilityOutcome.UNKNOWN;
      for (int index = 0; index < states.length; index++) {
        if (states[index] != FREE && sequences[index] <= inclusiveSequence) {
          unknownOutcomes[index] = true;
        }
      }
      fatalState.fence(StatusCode.IO_FAILURE);
      return StatusCode.IO_FAILURE;
    }
    for (int index = 0; index < states.length; index++) {
      if (sequences[index] <= inclusiveSequence && states[index] == WRITTEN) {
        states[index] = DURABLE;
      }
    }
    advanceDurableFrontier();
    return durableSequence >= inclusiveSequence
        ? StatusCode.OK
        : StatusCode.RETRY;
  }

  /**
   * Simulates process loss and reopen. Only the validated contiguous durable prefix survives;
   * every pre-restart node token is fenced.
   */
  public synchronized StatusCode crashAndRestart(NodeIncarnation restartedNode) {
    StatusCode owner = checkOwner();
    if (!owner.isOk()) {
      return owner;
    }
    if (!restartedNode.isValid() || restartedNode.equals(nodeIncarnation)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < states.length; index++) {
      if (states[index] != FREE && sequences[index] > durableSequence) {
        clearSlot(index);
        retainedEntries--;
      }
    }
    preparedSequence = durableSequence;
    committedSequence = durableSequence;
    nextSequence = durableSequence + 1;
    nextWalStart = durableEndExclusive;
    nodeIncarnation = restartedNode;
    providerToken++;
    lifecycleToken++;
    return StatusCode.OK;
  }

  public synchronized int retainedEntries() {
    return retainedEntries;
  }

  public int capacity() {
    return states.length;
  }

  private StatusCode admission(DatabaseIncarnation database, NodeIncarnation node) {
    StatusCode status = checkOwnerAndFence();
    if (!status.isOk()) {
      return status;
    }
    if (!databaseIncarnation.equals(database) || !nodeIncarnation.equals(node)) {
      return StatusCode.FENCED;
    }
    return StatusCode.OK;
  }

  private StatusCode checkOwnerAndFence() {
    StatusCode owner = checkOwner();
    return owner.isOk() ? fatalState.admissionStatus() : owner;
  }

  private StatusCode checkOwner() {
    return Thread.currentThread() == ownerThread ? StatusCode.OK : StatusCode.NOT_OWNER;
  }

  private int validateReservation(JournalReservation reservation) {
    int slot = reservation.slot();
    if (!reservation.isActive()
        || slot < 0
        || slot >= states.length
        || states[slot] != RESERVED
        || sequences[slot] != reservation.sequence()
        || reservationTokens[slot] != reservation.providerToken()) {
      return -1;
    }
    return slot;
  }

  private int findIdempotency(long keyHigh, long keyLow) {
    for (int index = 0; index < states.length; index++) {
      if (states[index] != FREE
          && idempotencyHigh[index] == keyHigh
          && idempotencyLow[index] == keyLow) {
        return index;
      }
    }
    return -1;
  }

  private boolean validLeaseRequest(WalRetentionLeaseRequest request) {
    long duration = request.expiresAtNanos() - request.nowNanos();
    return request.leaseId() > 0
        && request.ownerKind() != null
        && sameJournalLineage(request.minimumRequired())
        && request.minimumRequired().sequence() > 0
        && request.minimumRequired().sequence() < nextSequence
        && request.nowNanos() >= 0
        && request.expiresAtNanos() > request.nowNanos()
        && duration > 0
        && duration <= maxLeaseDurationNanos;
  }

  private int findLeaseId(long leaseId) {
    for (int index = 0; index < leaseIds.length; index++) {
      if (leaseIds[index] == leaseId) {
        return index;
      }
    }
    return -1;
  }

  private int findFreeLease() {
    return findLeaseId(0);
  }

  private int findLease(WalRetentionLease lease) {
    int slot = findLeaseId(lease.leaseId());
    return lease.isActive()
            && slot >= 0
            && leaseTokens[slot] == lease.providerToken()
        ? slot
        : -1;
  }

  private void expireLeases(long nowNanos) {
    for (int index = 0; index < leaseIds.length; index++) {
      if (leaseIds[index] != 0 && nowNanos >= leaseExpiries[index]) {
        clearLease(index);
      }
    }
  }

  private void clearLease(int slot) {
    leaseIds[slot] = 0;
    leaseTokens[slot] = 0;
    leaseMinimumSequences[slot] = 0;
    leaseExpiries[slot] = 0;
    leaseOwnerKinds[slot] = 0;
  }

  private int findSequence(long sequence) {
    if (states.length == 0) {
      return -1;
    }
    int candidate = slot(sequence);
    return states[candidate] != FREE && sequences[candidate] == sequence ? candidate : -1;
  }

  private int slot(long sequence) {
    return (int) ((sequence - 1) % states.length);
  }

  private void advancePublishedFrontiers() {
    while (preparedSequence + 1 < nextSequence) {
      int slot = findSequence(preparedSequence + 1);
      if (slot < 0 || states[slot] < PUBLISHED) {
        break;
      }
      preparedSequence++;
      committedSequence = preparedSequence;
    }
  }

  private void advanceDurableFrontier() {
    while (durableSequence + 1 <= committedSequence) {
      int slot = findSequence(durableSequence + 1);
      if (slot < 0 || states[slot] != DURABLE) {
        break;
      }
      durableSequence++;
      durableEndExclusive = walEnds[slot];
    }
  }

  private boolean sameJournalLineage(JournalPosition position) {
    return position.isValid()
        && databaseIncarnation.equals(position.incarnation())
        && journalGeneration == position.generation();
  }

  private JournalPosition position(long sequence) {
    return JournalPosition.of(databaseIncarnation, journalGeneration, sequence);
  }

  private void setDurabilityResult(
      DurabilityResult result,
      DurabilityOutcome outcome,
      DurabilityRequirement requirement,
      long coveredSequence) {
    result.set(
        outcome,
        requirement,
        databaseIncarnation.high(),
        databaseIncarnation.low(),
        journalGeneration,
        coveredSequence,
        durableSequence == 0 ? 0 : walGeneration,
        durableEndExclusive);
  }

  private void clearSlot(int slot) {
    states[slot] = FREE;
    sequences[slot] = 0;
    reservationTokens[slot] = 0;
    payloadLengths[slot] = 0;
    walStarts[slot] = 0;
    walEnds[slot] = 0;
    requestHigh[slot] = 0;
    requestLow[slot] = 0;
    idempotencyHigh[slot] = 0;
    idempotencyLow[slot] = 0;
    transactionIds[slot] = 0;
    commitSequences[slot] = 0;
    formatIds[slot] = 0;
    formatVersions[slot] = 0;
    transactionDecisions[slot] = 0;
    requestedDurability[slot] = 0;
    unknownOutcomes[slot] = false;
  }
}
