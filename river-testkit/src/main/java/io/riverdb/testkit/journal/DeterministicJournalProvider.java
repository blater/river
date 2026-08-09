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
import io.riverdb.journal.api.outcome.OutcomeRetentionSnapshot;
import io.riverdb.journal.api.outcome.TransactionDecision;
import io.riverdb.journal.api.retention.RetentionOwnerKind;
import io.riverdb.journal.api.retention.RetentionSnapshot;
import io.riverdb.journal.api.retention.WalRetentionLease;
import io.riverdb.journal.api.retention.WalRetentionLeaseRequest;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

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
  private static final AtomicLong PROVIDER_IDENTITIES = new AtomicLong();
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
  private final long capabilityOwnerHigh;
  private final long capabilityOwnerLow;
  private FatalStateFence fatalState;
  private final Thread ownerThread;
  private final byte[] states;
  private final long[] sequences;
  private final long[] reservationTokens;
  private final int[] payloadLengths;
  private final byte[][] payloads;
  private final long[] walStarts;
  private final long[] walEnds;
  private final int[] outcomeSlots;
  private final long[] ringOutcomeTokens;
  private final boolean[] ringUnknownOutcomes;
  private final long[] ringTransactionIds;
  private final long[] ringCommitSequences;
  private final byte[] ringTransactionDecisions;
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
  private final boolean[] cancelledOutcomes;
  private final byte[] outcomeStates;
  private final long[] outcomeSequences;
  private final long[] outcomeAdmittedAtNanos;
  private final long[] outcomeForgetAtNanos;
  private final long[] outcomeTokens;
  private final long outcomeRetentionNanos;
  private final long[] leaseIds;
  private final long[] leaseTokens;
  private final long[] leaseMinimumSequences;
  private final long[] leaseExpiries;
  private final byte[] leaseOwnerKinds;
  private final long maxLeaseDurationNanos;
  private NodeIncarnation nodeIncarnation;
  private long providerToken = 1;
  private long nextOutcomeToken = 1;
  private long lifecycleToken = 1;
  private long nextSequence = 1;
  private long nextWalStart;
  private long preparedSequence;
  private long committedSequence;
  private long durableSequence;
  private long durableEndExclusive;
  private int retainedEntries;
  private int retainedOutcomes;
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
        1_000_000_000L, Math.max(8, capacity * 2), 1_000_000_000L, fence);
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
    this(
        database,
        node,
        generation,
        localWalGeneration,
        capacity,
        maxEntryBytes,
        maxRetentionLeases,
        maximumLeaseDurationNanos,
        Math.max(8, capacity * 2),
        1_000_000_000L,
        fence);
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
      int outcomeCapacity,
      long requestOutcomeRetentionNanos,
      FatalStateFence fence) {
    databaseIncarnation = database;
    nodeIncarnation = node;
    journalGeneration = generation;
    walGeneration = localWalGeneration;
    capabilities = JournalCapabilities.LOCAL_ONLY;
    long providerIdentity = PROVIDER_IDENTITIES.incrementAndGet();
    capabilityOwnerHigh = mix(
        database.high() ^ node.high() ^ generation ^ providerIdentity ^ 0x52495645524a4e4cL);
    capabilityOwnerLow = mix(
        database.low() ^ node.low() ^ localWalGeneration ^ providerIdentity ^ 0x57414c4f574e4552L);
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
    outcomeSlots = new int[boundedCapacity];
    java.util.Arrays.fill(outcomeSlots, -1);
    ringOutcomeTokens = new long[boundedCapacity];
    ringUnknownOutcomes = new boolean[boundedCapacity];
    ringTransactionIds = new long[boundedCapacity];
    ringCommitSequences = new long[boundedCapacity];
    ringTransactionDecisions = new byte[boundedCapacity];
    int boundedOutcomeCapacity = Math.max(0, outcomeCapacity);
    requestHigh = new long[boundedOutcomeCapacity];
    requestLow = new long[boundedOutcomeCapacity];
    idempotencyHigh = new long[boundedOutcomeCapacity];
    idempotencyLow = new long[boundedOutcomeCapacity];
    transactionIds = new long[boundedOutcomeCapacity];
    commitSequences = new long[boundedOutcomeCapacity];
    formatIds = new int[boundedOutcomeCapacity];
    formatVersions = new int[boundedOutcomeCapacity];
    transactionDecisions = new byte[boundedOutcomeCapacity];
    requestedDurability = new byte[boundedOutcomeCapacity];
    unknownOutcomes = new boolean[boundedOutcomeCapacity];
    cancelledOutcomes = new boolean[boundedOutcomeCapacity];
    outcomeStates = new byte[boundedOutcomeCapacity];
    outcomeSequences = new long[boundedOutcomeCapacity];
    outcomeAdmittedAtNanos = new long[boundedOutcomeCapacity];
    outcomeForgetAtNanos = new long[boundedOutcomeCapacity];
    outcomeTokens = new long[boundedOutcomeCapacity];
    outcomeRetentionNanos = Math.max(0, requestOutcomeRetentionNanos);
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
    detail.reset();
    if (reservation.isActive()) {
      return detail.set(StatusCode.CONFLICT).append("active reservation output").code();
    }
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
        || request.admittedAtNanos() < 0
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
    long frameBytes = FRAME_OVERHEAD_BYTES + request.payloadBytes();
    if (Long.MAX_VALUE - nextWalStart < frameBytes || nextSequence == Long.MAX_VALUE) {
      return detail.set(StatusCode.RESOURCE_EXHAUSTED).append("local LSN overflow").code();
    }
    long end = nextWalStart + frameBytes;
    int outcomeSlot = findFreeOutcome();
    if (outcomeSlot < 0) {
      return detail.set(StatusCode.RESOURCE_EXHAUSTED).append("outcome capacity").code();
    }
    long sequence = nextSequence;
    long reservationToken = providerToken + 1;
    StatusCode claimStatus = reservation.claim(
        capabilityOwnerHigh,
        capabilityOwnerLow,
        databaseIncarnation.high(),
        databaseIncarnation.low(),
        journalGeneration,
        sequence,
        reservationToken,
        slot,
        request.payloadBytes());
    if (!claimStatus.isOk()) {
      return detail.set(claimStatus).append("reservation output claim").code();
    }
    nextSequence++;
    providerToken = reservationToken;
    states[slot] = RESERVED;
    sequences[slot] = sequence;
    reservationTokens[slot] = reservationToken;
    payloadLengths[slot] = request.payloadBytes();
    walStarts[slot] = nextWalStart;
    walEnds[slot] = end;
    outcomeSlots[slot] = outcomeSlot;
    long outcomeToken = nextOutcomeToken++;
    ringOutcomeTokens[slot] = outcomeToken;
    outcomeTokens[outcomeSlot] = outcomeToken;
    requestHigh[outcomeSlot] = request.requestIdHigh();
    requestLow[outcomeSlot] = request.requestIdLow();
    idempotencyHigh[outcomeSlot] = request.idempotencyKeyHigh();
    idempotencyLow[outcomeSlot] = request.idempotencyKeyLow();
    outcomeSequences[outcomeSlot] = sequence;
    outcomeAdmittedAtNanos[outcomeSlot] = request.admittedAtNanos();
    outcomeStates[outcomeSlot] = (byte) RequestOutcomeState.RESERVED.ordinal();
    requestedDurability[outcomeSlot] = (byte) request.durabilityRequirement().ordinal();
    retainedOutcomes++;
    nextWalStart = end;
    retainedEntries++;
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
    int outcomeSlot = outcomeSlots[slot];
    transactionIds[outcomeSlot] = request.transactionId();
    commitSequences[outcomeSlot] = request.commitSequence();
    formatIds[outcomeSlot] = request.formatId();
    formatVersions[outcomeSlot] = request.formatVersion();
    transactionDecisions[outcomeSlot] = (byte) request.transactionDecision().ordinal();
    ringTransactionIds[slot] = request.transactionId();
    ringCommitSequences[slot] = request.commitSequence();
    ringTransactionDecisions[slot] = (byte) request.transactionDecision().ordinal();
    outcomeStates[outcomeSlot] = (byte) (request.transactionDecision() == TransactionDecision.NONE
        ? RequestOutcomeState.PUBLISHED.ordinal()
        : RequestOutcomeState.DECIDED.ordinal());
    states[slot] = PUBLISHED;
    reservation.complete(capabilityOwnerHigh, capabilityOwnerLow);
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
    int outcomeSlot = outcomeSlots[slot];
    transactionIds[outcomeSlot] = 0;
    commitSequences[outcomeSlot] = 0;
    transactionDecisions[outcomeSlot] = 0;
    outcomeStates[outcomeSlot] = (byte) RequestOutcomeState.CANCELLED_TOMBSTONE.ordinal();
    cancelledOutcomes[outcomeSlot] = true;
    outcomeForgetAtNanos[outcomeSlot] = forgetAt(outcomeAdmittedAtNanos[outcomeSlot]);
    states[slot] = PUBLISHED;
    reservation.complete(capabilityOwnerHigh, capabilityOwnerLow);
    advancePublishedFrontiers();
    return StatusCode.CANCELLED;
  }

  @Override
  public synchronized StatusCode beginDurabilityWait(
      DurabilityWaitRequest request,
      DurabilityTicket ticket,
      DurabilityResult result,
      StatusDetail detail) {
    result.reset();
    detail.reset();
    if (ticket.isActive()) {
      return detail.set(StatusCode.CONFLICT).append("active durability ticket output").code();
    }
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
    StatusCode claimStatus = ticket.claim(
        capabilityOwnerHigh,
        capabilityOwnerLow,
        lifecycleToken,
        journalGeneration,
        request.requiredSequence(),
        request.requirement(),
        request.deadlineNanos());
    if (!claimStatus.isOk()) {
      return detail.set(claimStatus).code();
    }
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
    if (!ticket.isOwnedBy(capabilityOwnerHigh, capabilityOwnerLow)) {
      return detail.set(StatusCode.CONFLICT).append("foreign durability ticket").code();
    }
    if (ticket.providerToken() != lifecycleToken
        || ticket.journalGeneration() != journalGeneration) {
      ticket.complete(capabilityOwnerHigh, capabilityOwnerLow);
      setDurabilityResult(result, DurabilityOutcome.FENCED, ticket.requirement(), 0);
      return detail.set(StatusCode.FENCED).code();
    }
    if (fatalState.isFenced()) {
      ticket.complete(capabilityOwnerHigh, capabilityOwnerLow);
      DurabilityOutcome outcome = terminalWaitOutcome == DurabilityOutcome.UNKNOWN
          ? DurabilityOutcome.UNKNOWN
          : DurabilityOutcome.FENCED;
      setDurabilityResult(result, outcome, ticket.requirement(), 0);
      return detail.set(StatusCode.FENCED).code();
    }
    if (cancellation.isCancellationRequested()) {
      ticket.complete(capabilityOwnerHigh, capabilityOwnerLow);
      setDurabilityResult(result, DurabilityOutcome.CANCELLED, ticket.requirement(), 0);
      return detail.set(StatusCode.CANCELLED).code();
    }
    if (durableSequence >= ticket.requiredSequence()) {
      ticket.complete(capabilityOwnerHigh, capabilityOwnerLow);
      setDurabilityResult(
          result, DurabilityOutcome.SATISFIED, ticket.requirement(), ticket.requiredSequence());
      return StatusCode.OK;
    }
    if (ticket.deadlineNanos() != 0 && nowNanos >= ticket.deadlineNanos()) {
      ticket.complete(capabilityOwnerHigh, capabilityOwnerLow);
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
    if (!ticket.isOwnedBy(capabilityOwnerHigh, capabilityOwnerLow)) {
      return detail.set(StatusCode.CONFLICT).append("foreign durability ticket").code();
    }
    ticket.complete(capabilityOwnerHigh, capabilityOwnerLow);
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
        ringTransactionIds[slot],
        ringCommitSequences[slot],
        ringTransactionDecisions[slot] != 0);
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode lookupOutcome(
      DatabaseIncarnation database,
      NodeIncarnation node,
      IdempotencyKey idempotencyKey,
      long nowNanos,
      RequestOutcomeResult result,
      StatusDetail detail) {
    result.reset();
    detail.reset();
    if (!databaseIncarnation.equals(database) || !nodeIncarnation.equals(node)) {
      return detail.set(StatusCode.FENCED).code();
    }
    if (!idempotencyKey.isValid()) {
      return detail.set(StatusCode.INVALID_EXTERNAL_INPUT).code();
    }
    int slot = findIdempotency(idempotencyKey.high(), idempotencyKey.low());
    if (slot < 0
        || (outcomeForgetAtNanos[slot] != 0 && nowNanos >= outcomeForgetAtNanos[slot])) {
      return StatusCode.OK;
    }
    TransactionDecision decision = TransactionDecision.values()[transactionDecisions[slot]];
    RequestOutcomeState outcomeState = RequestOutcomeState.values()[outcomeStates[slot]];
    boolean finalOutcome = outcomeState == RequestOutcomeState.DURABLE
        || outcomeState == RequestOutcomeState.NOT_DURABLE
        || outcomeState == RequestOutcomeState.CANCELLED_TOMBSTONE;
    result.set(
        outcomeState,
        RequestId.of(requestHigh[slot], requestLow[slot]),
        IdempotencyKey.of(idempotencyHigh[slot], idempotencyLow[slot]),
        databaseIncarnation.high(),
        databaseIncarnation.low(),
        journalGeneration,
        outcomeSequences[slot],
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
  public synchronized StatusCode forgetExpiredOutcomes(
      long nowNanos,
      OutcomeRetentionSnapshot result,
      StatusDetail detail) {
    detail.reset();
    StatusCode owner = checkOwner();
    if (!owner.isOk()) {
      return detail.set(owner).code();
    }
    long earliest = 0;
    for (int index = 0; index < outcomeStates.length; index++) {
      if (outcomeStates[index] == RequestOutcomeState.NOT_FOUND.ordinal()) {
        continue;
      }
      if (outcomeForgetAtNanos[index] != 0 && nowNanos >= outcomeForgetAtNanos[index]) {
        clearOutcome(index);
        retainedOutcomes--;
      } else if (outcomeForgetAtNanos[index] != 0
          && (earliest == 0 || outcomeForgetAtNanos[index] < earliest)) {
        earliest = outcomeForgetAtNanos[index];
      }
    }
    result.set(retainedOutcomes, outcomeStates.length, earliest);
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode acquireRetentionLease(
      WalRetentionLeaseRequest request,
      WalRetentionLease lease,
      StatusDetail detail) {
    detail.reset();
    if (lease.isActive()) {
      return detail.set(StatusCode.CONFLICT).append("active retention lease output").code();
    }
    StatusCode admission = admission(request.databaseIncarnation(), request.nodeIncarnation());
    if (!admission.isOk()) {
      return detail.set(admission).code();
    }
    if (!validLeaseRequest(request)) {
      return detail.set(StatusCode.INVALID_EXTERNAL_INPUT).code();
    }
    if (!historyRetained(request.minimumRequired().sequence())) {
      return detail.set(StatusCode.CONFLICT).append("retention history unavailable").code();
    }
    expireLeases(request.nowNanos());
    if (findLeaseId(request.leaseId()) >= 0) {
      return detail.set(StatusCode.INVALID_EXTERNAL_INPUT).code();
    }
    int slot = findFreeLease();
    if (slot < 0) {
      return detail.set(StatusCode.RESOURCE_EXHAUSTED).append("retention lease capacity").code();
    }
    long token = providerToken + 1;
    StatusCode claimStatus = lease.claim(
        capabilityOwnerHigh,
        capabilityOwnerLow,
        token,
        nodeIncarnation,
        request.leaseId(),
        request.ownerKind(),
        request.minimumRequired(),
        request.expiresAtNanos());
    if (!claimStatus.isOk()) {
      return detail.set(claimStatus).code();
    }
    providerToken = token;
    leaseIds[slot] = request.leaseId();
    leaseTokens[slot] = token;
    leaseMinimumSequences[slot] = request.minimumRequired().sequence();
    leaseExpiries[slot] = request.expiresAtNanos();
    leaseOwnerKinds[slot] = (byte) request.ownerKind().ordinal();
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
    if (!lease.isOwnedBy(capabilityOwnerHigh, capabilityOwnerLow)) {
      return detail.set(StatusCode.CONFLICT).append("foreign retention lease").code();
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
    if (!historyRetained(request.minimumRequired().sequence())) {
      return detail.set(StatusCode.CONFLICT).append("retention history unavailable").code();
    }
    leaseMinimumSequences[slot] = request.minimumRequired().sequence();
    leaseExpiries[slot] = request.expiresAtNanos();
    lease.renew(
        capabilityOwnerHigh,
        capabilityOwnerLow,
        request.minimumRequired(),
        request.expiresAtNanos());
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
    detail.reset();
    if (lease.isActive()) {
      return detail.set(StatusCode.CONFLICT).append("active retention lease output").code();
    }
    StatusCode admission = admission(database, node);
    if (!admission.isOk()) {
      return detail.set(admission).code();
    }
    expireLeases(nowNanos);
    int slot = findLeaseId(leaseId);
    if (slot < 0) {
      return StatusCode.RETRY;
    }
    if (!historyRetained(leaseMinimumSequences[slot])) {
      return detail.set(StatusCode.CONFLICT).append("retention history unavailable").code();
    }
    long token = providerToken + 1;
    RetentionOwnerKind owner = RetentionOwnerKind.values()[leaseOwnerKinds[slot]];
    StatusCode claimStatus = lease.claim(
        capabilityOwnerHigh,
        capabilityOwnerLow,
        token,
        nodeIncarnation,
        leaseIds[slot],
        owner,
        position(leaseMinimumSequences[slot]),
        leaseExpiries[slot]);
    if (!claimStatus.isOk()) {
      return detail.set(claimStatus).code();
    }
    providerToken = token;
    leaseTokens[slot] = token;
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
    if (!lease.isOwnedBy(capabilityOwnerHigh, capabilityOwnerLow)) {
      return detail.set(StatusCode.CONFLICT).append("foreign retention lease").code();
    }
    int slot = findLease(lease);
    if (slot < 0) {
      return detail.set(StatusCode.CONFLICT).code();
    }
    clearLease(slot);
    lease.complete(capabilityOwnerHigh, capabilityOwnerLow);
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
    return forceThrough(generation, inclusiveSequence, completion, 0);
  }

  /** Allocation-free force completion with an explicit outcome-retention timestamp. */
  public synchronized StatusCode forceThrough(
      long generation,
      long inclusiveSequence,
      ForceCompletion completion,
      long nowNanos) {
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
        if (states[index] == WRITTEN && sequences[index] <= inclusiveSequence) {
          ringUnknownOutcomes[index] = true;
          int outcomeSlot = activeOutcomeSlot(index);
          if (outcomeSlot >= 0) {
            unknownOutcomes[outcomeSlot] = true;
            outcomeStates[outcomeSlot] = (byte) RequestOutcomeState.UNKNOWN.ordinal();
          }
        }
      }
      fatalState.fence(StatusCode.IO_FAILURE);
      return StatusCode.IO_FAILURE;
    }
    for (int index = 0; index < states.length; index++) {
      if (sequences[index] <= inclusiveSequence && states[index] == WRITTEN) {
        states[index] = DURABLE;
        int outcomeSlot = activeOutcomeSlot(index);
        if (outcomeSlot >= 0) {
          unknownOutcomes[outcomeSlot] = false;
          outcomeStates[outcomeSlot] = (byte) (cancelledOutcomes[outcomeSlot]
              ? RequestOutcomeState.CANCELLED_TOMBSTONE.ordinal()
              : RequestOutcomeState.DURABLE.ordinal());
          outcomeForgetAtNanos[outcomeSlot] = forgetAt(nowNanos);
        }
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
    return crashAndRestart(restartedNode, UnknownRecoveryResolution.NOT_DURABLE, 0);
  }

  /** Reopens under a fresh node/fence after stable scanning resolves any unknown force. */
  public synchronized StatusCode crashAndRestart(
      NodeIncarnation restartedNode,
      UnknownRecoveryResolution unknownResolution,
      long nowNanos) {
    StatusCode owner = checkOwner();
    if (!owner.isOk()) {
      return owner;
    }
    if (!restartedNode.isValid() || restartedNode.equals(nodeIncarnation)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (terminalWaitOutcome == DurabilityOutcome.UNKNOWN
        && unknownResolution == UnknownRecoveryResolution.DURABLE) {
      for (int index = 0; index < states.length; index++) {
        if (ringUnknownOutcomes[index]) {
          states[index] = DURABLE;
          ringUnknownOutcomes[index] = false;
          int outcomeSlot = activeOutcomeSlot(index);
          if (outcomeSlot >= 0) {
            unknownOutcomes[outcomeSlot] = false;
            outcomeStates[outcomeSlot] = (byte) (cancelledOutcomes[outcomeSlot]
                ? RequestOutcomeState.CANCELLED_TOMBSTONE.ordinal()
                : RequestOutcomeState.DURABLE.ordinal());
            outcomeForgetAtNanos[outcomeSlot] = forgetAt(nowNanos);
          }
        }
      }
      advanceDurableFrontier();
    }
    for (int index = 0; index < states.length; index++) {
      if (states[index] != FREE && sequences[index] > durableSequence) {
        int outcomeSlot = activeOutcomeSlot(index);
        if (outcomeSlot >= 0) {
          unknownOutcomes[outcomeSlot] = false;
          outcomeStates[outcomeSlot] = (byte) RequestOutcomeState.NOT_DURABLE.ordinal();
          outcomeForgetAtNanos[outcomeSlot] = forgetAt(nowNanos);
        }
        clearSlot(index);
        retainedEntries--;
      }
    }
    preparedSequence = durableSequence;
    committedSequence = durableSequence;
    nextSequence = durableSequence + 1;
    nextWalStart = durableEndExclusive;
    nodeIncarnation = restartedNode;
    fatalState = new FatalStateFence();
    terminalWaitOutcome = DurabilityOutcome.PENDING;
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

  synchronized StatusCode setNextWalStartForTest(long walStart) {
    if (walStart < 0 || retainedEntries != 0) {
      return StatusCode.CONFLICT;
    }
    nextWalStart = walStart;
    return StatusCode.OK;
  }

  synchronized StatusCode discardHistoryForTest(long sequence) {
    int slot = findSequence(sequence);
    if (slot < 0) {
      return StatusCode.RETRY;
    }
    clearSlot(slot);
    retainedEntries--;
    return StatusCode.OK;
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

  private static long mix(long value) {
    value ^= value >>> 33;
    value *= 0xff51afd7ed558ccdL;
    value ^= value >>> 33;
    value *= 0xc4ceb9fe1a85ec53L;
    value ^= value >>> 33;
    return value == 0 ? 1 : value;
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
        || !reservation.isOwnedBy(capabilityOwnerHigh, capabilityOwnerLow)
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
    for (int index = 0; index < outcomeStates.length; index++) {
      if (outcomeStates[index] != RequestOutcomeState.NOT_FOUND.ordinal()
          && idempotencyHigh[index] == keyHigh
          && idempotencyLow[index] == keyLow) {
        return index;
      }
    }
    return -1;
  }

  private int findFreeOutcome() {
    for (int index = 0; index < outcomeStates.length; index++) {
      if (outcomeStates[index] == RequestOutcomeState.NOT_FOUND.ordinal()) {
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

  private boolean historyRetained(long sequence) {
    int slot = findSequence(sequence);
    return slot >= 0 && states[slot] == DURABLE && sequence <= durableSequence;
  }

  private int findSequence(long sequence) {
    if (states.length == 0) {
      return -1;
    }
    int candidate = slot(sequence);
    return states[candidate] != FREE && sequences[candidate] == sequence ? candidate : -1;
  }

  private int activeOutcomeSlot(int ringSlot) {
    int outcomeSlot = outcomeSlots[ringSlot];
    return outcomeSlot >= 0
            && outcomeTokens[outcomeSlot] == ringOutcomeTokens[ringSlot]
        ? outcomeSlot
        : -1;
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
        outcome == DurabilityOutcome.SATISFIED ? 1L << requirement.ordinal() : 0,
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
    outcomeSlots[slot] = -1;
    ringOutcomeTokens[slot] = 0;
    ringUnknownOutcomes[slot] = false;
    ringTransactionIds[slot] = 0;
    ringCommitSequences[slot] = 0;
    ringTransactionDecisions[slot] = 0;
  }

  private long forgetAt(long nowNanos) {
    return Long.MAX_VALUE - nowNanos < outcomeRetentionNanos
        ? Long.MAX_VALUE
        : nowNanos + outcomeRetentionNanos;
  }

  private void clearOutcome(int slot) {
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
    cancelledOutcomes[slot] = false;
    outcomeStates[slot] = (byte) RequestOutcomeState.NOT_FOUND.ordinal();
    outcomeSequences[slot] = 0;
    outcomeAdmittedAtNanos[slot] = 0;
    outcomeForgetAtNanos[slot] = 0;
    outcomeTokens[slot] = 0;
  }
}
