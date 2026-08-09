package io.riverdb.testkit.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.concurrent.CancellationToken;
import io.riverdb.base.concurrent.MutableCancellationToken;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.CommitSequence;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.IdempotencyKey;
import io.riverdb.base.id.JournalPosition;
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
import io.riverdb.journal.api.frontier.JournalFrontierSnapshot;
import io.riverdb.journal.api.mapping.JournalPositionMapping;
import io.riverdb.journal.api.outcome.OutcomeRetentionSnapshot;
import io.riverdb.journal.api.outcome.RequestOutcomeResult;
import io.riverdb.journal.api.outcome.RequestOutcomeState;
import io.riverdb.journal.api.outcome.TransactionDecision;
import io.riverdb.journal.api.retention.RetentionOwnerKind;
import io.riverdb.journal.api.retention.RetentionSnapshot;
import io.riverdb.journal.api.retention.WalRetentionLease;
import io.riverdb.journal.api.retention.WalRetentionLeaseRequest;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Reusable semantic suite for every local or replicated {@link JournalProvider}. Provider-specific
 * mechanics may add tests but must not weaken these scenarios.
 */
public abstract class JournalProviderContractTest {
  protected static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(11, 12);
  protected static final NodeIncarnation NODE = NodeIncarnation.of(21, 22);
  protected static final long JOURNAL_GENERATION = 3;

  protected abstract JournalProviderHarness openHarness(
      int entryCapacity,
      int maxEntryBytes,
      int retentionLeaseCapacity,
      long maxLeaseDurationNanos);

  /** Provider mode under test; replicated implementations override these expectations. */
  protected DurabilityRequirement contractRequirement() {
    return DurabilityRequirement.LOCAL_DURABLE;
  }

  protected DurabilityRequirement unsupportedRequirement() {
    return DurabilityRequirement.QUORUM_DURABLE;
  }

  protected boolean expectsSupport(DurabilityRequirement requirement) {
    return requirement == DurabilityRequirement.LOCAL_DURABLE;
  }

  protected boolean expectsConsensus() {
    return false;
  }

  protected boolean expectsStateSync() {
    return false;
  }

  protected boolean expectsFollowerServing() {
    return false;
  }

  protected abstract JournalProviderHarness openHarnessWithOutcomePolicy(
      int entryCapacity,
      int maxEntryBytes,
      int outcomeCapacity,
      long outcomeRetentionNanos);

  @Test
  final void localCapabilityAndJournalOwnedFrontiersAreExplicit() {
    JournalProvider provider = openHarness(4).provider();

    for (DurabilityRequirement requirement : DurabilityRequirement.values()) {
      assertEquals(expectsSupport(requirement), provider.capabilities().supports(requirement));
    }
    assertEquals(expectsConsensus(), provider.capabilities().hasConsensus());
    assertEquals(expectsStateSync(), provider.capabilities().hasStateSync());
    assertEquals(expectsFollowerServing(), provider.capabilities().canServeFollowers());

    JournalFrontierSnapshot snapshot = frontiers(provider);
    assertEquals(DATABASE.high(), snapshot.databaseIncarnationHigh());
    assertEquals(DATABASE.low(), snapshot.databaseIncarnationLow());
    assertEquals(JOURNAL_GENERATION, snapshot.journalGeneration());
    assertFrontiers(snapshot, 0, 0, 0, 0, 0);

    assertTrue(Arrays.stream(JournalFrontierSnapshot.class.getMethods())
        .noneMatch(method -> method.getName().equals("visibleCsn")
            || method.getName().equals("durableRecovery")
            || method.getName().equals("safeTruncate")));
  }

  @Test
  final void unsupportedDurabilityIsRejectedBeforeReservationConsumesOrder() {
    JournalProvider provider = openHarness(2).provider();
    JournalReservation rejected = new JournalReservation();
    StatusCode status = provider.reserve(
        reserveRequest(1, unsupportedRequirement()),
        rejected,
        detail());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, status);
    assertFalse(rejected.isActive());

    JournalReservation accepted = reserve(provider, 2);
    assertEquals(1, accepted.sequence());
    publish(provider, accepted, 2, TransactionDecision.COMMITTED);
    DurabilityTicket unsupportedTicket = new DurabilityTicket();
    DurabilityResult unsupportedResult = new DurabilityResult();
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        provider.beginDurabilityWait(
            waitRequest(accepted.sequence(), unsupportedRequirement(), 0),
            unsupportedTicket,
            unsupportedResult,
            detail()));
    assertFalse(unsupportedTicket.isActive());
    assertEquals(DurabilityOutcome.UNSUPPORTED, unsupportedResult.outcome());
    assertEquals(unsupportedRequirement(), unsupportedResult.requestedRequirement());
    assertEquals(0, unsupportedResult.satisfiedDurabilityMask());
  }

  @Test
  final void outOfOrderPublicationNeverSkipsAHole() {
    JournalProviderHarness harness = openHarness(4);
    JournalProvider provider = harness.provider();
    JournalReservation first = reserve(provider, 1);
    JournalReservation second = reserve(provider, 2);
    JournalReservation third = reserve(provider, 3);

    publish(provider, second, 2, TransactionDecision.NONE);
    publish(provider, third, 3, TransactionDecision.NONE);
    assertFrontiers(frontiers(provider), 0, 0, 0, 0, 0);

    assertEquals(StatusCode.OK, harness.writeThrough(JOURNAL_GENERATION, 3));
    assertEquals(
        StatusCode.RETRY,
        harness.forceThrough(JOURNAL_GENERATION, 3, ForceCompletion.SUCCEEDED));
    assertFrontiers(frontiers(provider), 0, 0, 0, 0, 0);

    publish(provider, first, 1, TransactionDecision.NONE);
    assertFrontiers(frontiers(provider), 3, 0, 3, 0, 0);

    assertEquals(StatusCode.OK, harness.writeThrough(JOURNAL_GENERATION, 1));
    assertEquals(
        StatusCode.OK,
        harness.forceThrough(JOURNAL_GENERATION, 3, ForceCompletion.SUCCEEDED));
    assertFrontiers(frontiers(provider), 3, 0, 3, 3, 0);
  }

  @Test
  final void reservationCancellationPublishesATombstoneAndClosesTheHole() {
    JournalProviderHarness harness = openHarness(3);
    JournalProvider provider = harness.provider();
    JournalReservation first = reserve(provider, 1);
    JournalReservation second = reserve(provider, 2);
    publish(provider, second, 2, TransactionDecision.NONE);
    assertEquals(0, frontiers(provider).preparedSequence());

    assertEquals(StatusCode.CANCELLED, provider.cancelReservation(first, detail()));
    assertFalse(first.isActive());
    assertEquals(2, frontiers(provider).preparedSequence());
    RequestOutcomeResult cancelled = lookup(provider, NODE, 1);
    assertEquals(RequestOutcomeState.CANCELLED_TOMBSTONE, cancelled.state());
    assertTrue(cancelled.isFinal());
    JournalReservation duplicate = new JournalReservation();
    assertEquals(
        StatusCode.CONFLICT,
        provider.reserve(reserveRequest(1, contractRequirement()), duplicate, detail()));
    assertEquals(StatusCode.OK, harness.writeThrough(JOURNAL_GENERATION, 2));
    assertEquals(
        StatusCode.OK,
        harness.forceThrough(JOURNAL_GENERATION, 2, ForceCompletion.SUCCEEDED));
    NodeIncarnation restarted = NodeIncarnation.of(23, 24);
    assertEquals(StatusCode.OK, harness.crashAndRestart(restarted));
    assertEquals(
        RequestOutcomeState.CANCELLED_TOMBSTONE,
        lookup(provider, restarted, 1).state());
  }

  @Test
  final void boundedCapacityReturnsBackpressureBeforeRingOverwrite() {
    JournalProvider provider = openHarness(2).provider();
    reserve(provider, 1);
    reserve(provider, 2);

    JournalReservation rejected = new JournalReservation();
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        provider.reserve(reserveRequest(3, contractRequirement()),
            rejected, detail()));
    assertFalse(rejected.isActive());
  }

  @Test
  final void durabilityWaitCoversPendingSuccessTimeoutCancellationAndNoDeadline() {
    JournalProviderHarness harness = openHarness(5);
    JournalProvider provider = harness.provider();
    JournalAppendResult append = append(provider, 1, TransactionDecision.COMMITTED);

    DurabilityTicket noDeadlineTicket = new DurabilityTicket();
    DurabilityResult durability = new DurabilityResult();
    assertEquals(
        StatusCode.RETRY,
        provider.beginDurabilityWait(
            waitRequest(append.sequence(), contractRequirement(), 0),
            noDeadlineTicket,
            durability,
            detail()));
    assertEquals(
        StatusCode.RETRY,
        provider.pollDurability(
            noDeadlineTicket, Long.MAX_VALUE, CancellationToken.NONE, durability, detail()));
    assertTrue(noDeadlineTicket.isActive());

    assertEquals(StatusCode.OK, harness.writeThrough(JOURNAL_GENERATION, append.sequence()));
    assertEquals(
        StatusCode.OK,
        harness.forceThrough(
            JOURNAL_GENERATION, append.sequence(), ForceCompletion.SUCCEEDED));
    assertEquals(
        StatusCode.OK,
        provider.pollDurability(
            noDeadlineTicket, Long.MAX_VALUE, CancellationToken.NONE, durability, detail()));
    assertEquals(DurabilityOutcome.SATISFIED, durability.outcome());
    assertEquals(append.sequence(), durability.coveredSequence());
    assertEquals(append.recordEndLsnExclusive(), durability.durableEndLsnExclusive());

    JournalAppendResult second = append(provider, 2, TransactionDecision.COMMITTED);
    DurabilityTicket timeoutTicket = beginPending(provider, second.sequence(), 10);
    assertEquals(
        StatusCode.TIMEOUT,
        provider.pollDurability(
            timeoutTicket, 10, CancellationToken.NONE, durability, detail()));
    assertEquals(DurabilityOutcome.TIMED_OUT, durability.outcome());
    assertEquals(0, durability.satisfiedDurabilityMask());
    assertFalse(durability.satisfies(contractRequirement()));

    DurabilityTicket cancelledTicket = beginPending(provider, second.sequence(), 100);
    MutableCancellationToken cancellation = new MutableCancellationToken();
    cancellation.cancel();
    assertEquals(
        StatusCode.CANCELLED,
        provider.pollDurability(cancelledTicket, 1, cancellation, durability, detail()));
    assertEquals(DurabilityOutcome.CANCELLED, durability.outcome());
    assertEquals(0, durability.satisfiedDurabilityMask());

    DurabilityTicket explicitTicket = beginPending(provider, second.sequence(), 100);
    assertEquals(
        StatusCode.CANCELLED,
        provider.cancelDurabilityWait(explicitTicket, durability, detail()));
    assertEquals(DurabilityOutcome.CANCELLED, durability.outcome());
    assertEquals(0, durability.satisfiedDurabilityMask());
  }

  @Test
  final void unknownForceOutcomeFencesAndNeverInventsAbort() {
    JournalProviderHarness harness = openHarness(3);
    JournalProvider provider = harness.provider();
    JournalAppendResult append = append(provider, 1, TransactionDecision.COMMITTED);
    DurabilityTicket ticket = beginPending(provider, append.sequence(), 100);
    assertEquals(StatusCode.OK, harness.writeThrough(JOURNAL_GENERATION, append.sequence()));

    assertEquals(
        StatusCode.IO_FAILURE,
        harness.forceThrough(JOURNAL_GENERATION, append.sequence(), ForceCompletion.UNKNOWN));
    DurabilityResult durability = new DurabilityResult();
    assertEquals(
        StatusCode.FENCED,
        provider.pollDurability(ticket, 1, CancellationToken.NONE, durability, detail()));
    assertEquals(DurabilityOutcome.UNKNOWN, durability.outcome());
    assertEquals(0, durability.satisfiedDurabilityMask());

    RequestOutcomeResult outcome = lookup(provider, NODE, 1);
    assertEquals(RequestOutcomeState.UNKNOWN, outcome.state());
    assertEquals(TransactionDecision.COMMITTED, outcome.decision());
    assertFalse(outcome.isFinal());

    JournalReservation rejected = new JournalReservation();
    assertEquals(
        StatusCode.FENCED,
        provider.reserve(reserveRequest(2, contractRequirement()),
            rejected, detail()));
  }

  @Test
  final void certainForceFailureCanBeRetriedWithoutFencing() {
    JournalProviderHarness harness = openHarness(2);
    JournalProvider provider = harness.provider();
    JournalAppendResult append = append(provider, 1, TransactionDecision.COMMITTED);
    DurabilityTicket ticket = beginPending(provider, append.sequence(), 100);
    assertEquals(StatusCode.OK, harness.writeThrough(JOURNAL_GENERATION, append.sequence()));
    assertEquals(
        StatusCode.IO_FAILURE,
        harness.forceThrough(JOURNAL_GENERATION, append.sequence(), ForceCompletion.FAILED));
    assertEquals(
        StatusCode.RETRY,
        provider.pollDurability(
            ticket, 1, CancellationToken.NONE, new DurabilityResult(), detail()));

    assertEquals(
        StatusCode.OK,
        harness.forceThrough(JOURNAL_GENERATION, append.sequence(), ForceCompletion.SUCCEEDED));
    DurabilityResult durability = new DurabilityResult();
    assertEquals(
        StatusCode.OK,
        provider.pollDurability(
            ticket, 2, CancellationToken.NONE, durability, detail()));
    assertEquals(DurabilityOutcome.SATISFIED, durability.outcome());
  }

  @Test
  final void restartPreservesDurablePrefixDropsVolatileSuffixAndFencesOldNode() {
    JournalProviderHarness harness = openHarness(5);
    JournalProvider provider = harness.provider();
    JournalAppendResult durable = append(provider, 1, TransactionDecision.COMMITTED);
    JournalAppendResult volatileAppend = append(provider, 2, TransactionDecision.COMMITTED);
    DurabilityTicket staleTicket = beginPending(provider, volatileAppend.sequence(), 0);
    assertEquals(StatusCode.OK, harness.writeThrough(JOURNAL_GENERATION, 1));
    assertEquals(
        StatusCode.OK,
        harness.forceThrough(JOURNAL_GENERATION, 1, ForceCompletion.SUCCEEDED));
    WalRetentionLease staleLease = new WalRetentionLease();
    assertEquals(
        StatusCode.OK,
        provider.acquireRetentionLease(
            leaseRequest(8, 1, 0, 100), staleLease, detail()));

    NodeIncarnation restarted = NodeIncarnation.of(31, 32);
    assertEquals(StatusCode.OK, harness.crashAndRestart(restarted));
    assertFrontiers(frontiers(provider), 1, 0, 1, 1, 0);
    assertEquals(RequestOutcomeState.DURABLE, lookup(provider, restarted, 1).state());
    assertEquals(RequestOutcomeState.NOT_DURABLE, lookup(provider, restarted, 2).state());
    DurabilityResult staleWaitResult = new DurabilityResult();
    assertEquals(
        StatusCode.FENCED,
        provider.pollDurability(
            staleTicket, 1, CancellationToken.NONE, staleWaitResult, detail()));
    assertEquals(DurabilityOutcome.FENCED, staleWaitResult.outcome());
    assertEquals(StatusCode.FENCED, provider.releaseRetentionLease(staleLease, detail()));
    WalRetentionLease reopenedLease = new WalRetentionLease();
    assertEquals(
        StatusCode.OK,
        provider.reopenRetentionLease(
            DATABASE, restarted, 8, 1, reopenedLease, detail()));
    assertEquals(1, reopenedLease.minimumRequired().sequence());
    assertEquals(StatusCode.OK, provider.releaseRetentionLease(reopenedLease, detail()));

    RequestOutcomeResult stale = new RequestOutcomeResult();
    assertEquals(
        StatusCode.FENCED,
        provider.lookupOutcome(DATABASE, NODE, IdempotencyKey.of(1, 2), 0, stale, detail()));
    assertEquals(
        StatusCode.FENCED,
        provider.lookupOutcome(
            DatabaseIncarnation.of(99, 100), restarted,
            IdempotencyKey.of(3001, 4001), 0, stale, detail()));
    JournalReservation next = reserve(provider, restarted, 3);
    assertEquals(durable.sequence() + 1, next.sequence());
  }

  @Test
  final void idempotencyLookupIsStableBeforeAndAfterDecisionDurabilityAndRestart() {
    JournalProviderHarness harness = openHarness(4);
    JournalProvider provider = harness.provider();
    JournalReservation reservation = reserve(provider, 1);
    assertEquals(RequestOutcomeState.RESERVED, lookup(provider, NODE, 1).state());

    JournalReservation duplicate = new JournalReservation();
    assertEquals(
        StatusCode.CONFLICT,
        provider.reserve(reserveRequest(1, contractRequirement()),
            duplicate, detail()));
    JournalAppendResult append = publish(
        provider, reservation, 1, TransactionDecision.COMMITTED);
    RequestOutcomeResult decided = lookup(provider, NODE, 1);
    assertEquals(RequestOutcomeState.DECIDED, decided.state());
    assertEquals(101, decided.commitSequence().value());
    assertFalse(decided.isFinal());

    assertEquals(StatusCode.OK, harness.writeThrough(JOURNAL_GENERATION, append.sequence()));
    assertEquals(
        StatusCode.OK,
        harness.forceThrough(JOURNAL_GENERATION, append.sequence(), ForceCompletion.SUCCEEDED));
    RequestOutcomeResult responseLost = lookup(provider, NODE, 1);
    assertEquals(RequestOutcomeState.DURABLE, responseLost.state());
    assertTrue(responseLost.isFinal());

    NodeIncarnation restarted = NodeIncarnation.of(41, 42);
    assertEquals(StatusCode.OK, harness.crashAndRestart(restarted));
    RequestOutcomeResult afterRestart = lookup(provider, restarted, 1);
    assertEquals(RequestOutcomeState.DURABLE, afterRestart.state());
    assertEquals(responseLost.sequence(), afterRestart.sequence());
    assertEquals(responseLost.commitSequence(), afterRestart.commitSequence());
  }

  @Test
  final void mappingKeepsLogicalPositionWalRangeTransactionAndCsnDistinct() {
    JournalProvider provider = openHarness(3).provider();
    JournalAppendResult append = append(provider, 7, TransactionDecision.COMMITTED);
    JournalPositionMapping mapping = new JournalPositionMapping();

    assertEquals(
        StatusCode.OK,
        provider.inspectMapping(
            JournalPosition.of(DATABASE, JOURNAL_GENERATION, append.sequence()), mapping));
    assertEquals(append.sequence(), mapping.sequence());
    assertEquals(append.recordStartLsn(), mapping.recordStartLsn());
    assertEquals(append.recordEndLsnExclusive(), mapping.recordEndLsnExclusive());
    assertEquals(7, mapping.transactionId());
    assertEquals(107, mapping.commitSequence());
    assertTrue(mapping.isTransactionDecision());
  }

  @Test
  final void retentionLeasesAreBoundedExpireAndPreventUnsafeRelease() {
    JournalProviderHarness harness = openHarness(4, 32, 1, 100);
    JournalProvider provider = harness.provider();
    for (int index = 1; index <= 3; index++) {
      append(provider, index, TransactionDecision.NONE);
    }
    assertEquals(StatusCode.OK, harness.writeThrough(JOURNAL_GENERATION, 3));
    assertEquals(
        StatusCode.OK,
        harness.forceThrough(JOURNAL_GENERATION, 3, ForceCompletion.SUCCEEDED));

    WalRetentionLease lease = new WalRetentionLease();
    assertEquals(
        StatusCode.OK,
        provider.acquireRetentionLease(
            leaseRequest(1, 2, 0, 50), lease, detail()));
    WalRetentionLease tooLong = new WalRetentionLease();
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        provider.acquireRetentionLease(
            leaseRequest(9, 2, 0, 101), tooLong, detail()));
    WalRetentionLease rejected = new WalRetentionLease();
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        provider.acquireRetentionLease(
            leaseRequest(2, 3, 0, 50), rejected, detail()));

    assertEquals(
        StatusCode.OK,
        harness.reclaimThrough(JournalPosition.of(DATABASE, JOURNAL_GENERATION, 3), 1));
    assertEquals(2, harness.retainedEntries());
    RetentionSnapshot snapshot = new RetentionSnapshot();
    assertEquals(StatusCode.OK, provider.snapshotRetention(1, snapshot));
    assertEquals(1, snapshot.activeLeases());
    assertEquals(2, snapshot.oldestRequired().sequence());

    assertEquals(
        StatusCode.OK,
        provider.renewRetentionLease(lease, leaseRequest(1, 3, 2, 60), detail()));
    assertEquals(StatusCode.OK, provider.snapshotRetention(2, snapshot));
    assertEquals(3, snapshot.oldestRequired().sequence());

    assertEquals(StatusCode.OK, provider.releaseRetentionLease(lease, detail()));
    assertEquals(
        StatusCode.OK,
        harness.reclaimThrough(JournalPosition.of(DATABASE, JOURNAL_GENERATION, 3), 2));
    assertEquals(0, harness.retainedEntries());

    WalRetentionLease expiring = new WalRetentionLease();
    JournalReservation fourth = reserve(provider, 4);
    publish(provider, fourth, 4, TransactionDecision.NONE);
    assertEquals(StatusCode.OK, harness.writeThrough(JOURNAL_GENERATION, 4));
    assertEquals(
        StatusCode.OK,
        harness.forceThrough(JOURNAL_GENERATION, 4, ForceCompletion.SUCCEEDED));
    assertEquals(
        StatusCode.OK,
        provider.acquireRetentionLease(leaseRequest(3, 4, 10, 20), expiring, detail()));
    assertEquals(StatusCode.OK, provider.snapshotRetention(20, snapshot));
    assertEquals(0, snapshot.activeLeases());
  }

  @Test
  final void retentionPressureBackpressuresInsteadOfOverwritingPinnedEntries() {
    JournalProviderHarness harness = openHarness(2, 32, 1, 100);
    JournalProvider provider = harness.provider();
    append(provider, 1, TransactionDecision.NONE);
    append(provider, 2, TransactionDecision.NONE);
    assertEquals(StatusCode.OK, harness.writeThrough(JOURNAL_GENERATION, 2));
    assertEquals(
        StatusCode.OK,
        harness.forceThrough(JOURNAL_GENERATION, 2, ForceCompletion.SUCCEEDED));
    WalRetentionLease lease = new WalRetentionLease();
    assertEquals(
        StatusCode.OK,
        provider.acquireRetentionLease(leaseRequest(1, 1, 0, 50), lease, detail()));
    assertEquals(
        StatusCode.OK,
        harness.reclaimThrough(JournalPosition.of(DATABASE, JOURNAL_GENERATION, 2), 1));

    JournalReservation rejected = new JournalReservation();
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        provider.reserve(reserveRequest(3, contractRequirement()),
            rejected, detail()));
    assertEquals(StatusCode.OK, provider.releaseRetentionLease(lease, detail()));
    assertEquals(
        StatusCode.OK,
        harness.reclaimThrough(JournalPosition.of(DATABASE, JOURNAL_GENERATION, 2), 2));
    assertEquals(3, reserve(provider, 3).sequence());
  }

  @Test
  final void outcomesSurviveWalReclaimSlotReuseAndRestartUntilExplicitForgetHorizon() {
    JournalProviderHarness harness = openHarnessWithOutcomePolicy(1, 32, 2, 10);
    JournalProvider provider = harness.provider();
    JournalAppendResult first = append(provider, 1, TransactionDecision.COMMITTED);
    assertEquals(StatusCode.OK, harness.writeThrough(JOURNAL_GENERATION, first.sequence()));
    assertEquals(
        StatusCode.OK,
        harness.forceThrough(
            JOURNAL_GENERATION, first.sequence(), ForceCompletion.SUCCEEDED, 0));
    assertEquals(
        StatusCode.OK,
        harness.reclaimThrough(
            JournalPosition.of(DATABASE, JOURNAL_GENERATION, first.sequence()), 1));
    assertEquals(0, harness.retainedEntries());
    assertEquals(RequestOutcomeState.DURABLE, lookup(provider, NODE, 1, 9).state());

    JournalAppendResult second = append(provider, 2, TransactionDecision.COMMITTED);
    assertEquals(StatusCode.OK, harness.writeThrough(JOURNAL_GENERATION, second.sequence()));
    assertEquals(
        StatusCode.OK,
        harness.forceThrough(
            JOURNAL_GENERATION, second.sequence(), ForceCompletion.SUCCEEDED, 1));
    assertEquals(RequestOutcomeState.DURABLE, lookup(provider, NODE, 1, 9).state());
    assertEquals(RequestOutcomeState.DURABLE, lookup(provider, NODE, 2, 9).state());

    NodeIncarnation restarted = NodeIncarnation.of(51, 52);
    assertEquals(StatusCode.OK, harness.crashAndRestart(restarted));
    assertEquals(RequestOutcomeState.DURABLE, lookup(provider, restarted, 1, 9).state());
    assertEquals(RequestOutcomeState.DURABLE, lookup(provider, restarted, 2, 9).state());
    assertEquals(
        StatusCode.OK,
        harness.reclaimThrough(
            JournalPosition.of(DATABASE, JOURNAL_GENERATION, second.sequence()), 2));

    assertEquals(RequestOutcomeState.NOT_FOUND, lookup(provider, restarted, 1, 10).state());
    OutcomeRetentionSnapshot retention = new OutcomeRetentionSnapshot();
    assertEquals(
        StatusCode.OK,
        provider.forgetExpiredOutcomes(10, retention, detail()));
    assertEquals(1, retention.retainedOutcomes());
    assertEquals(2, retention.capacity());
    assertEquals(11, retention.earliestForgetAtNanos());
    assertEquals(3, reserve(provider, restarted, 3).sequence());
  }

  @Test
  final void activeAndForeignCapabilitiesAreRejectedWithoutMutation() {
    JournalProviderHarness firstHarness = openHarness(5);
    JournalProviderHarness secondHarness = openHarness(5);
    JournalProvider firstProvider = firstHarness.provider();
    JournalProvider secondProvider = secondHarness.provider();
    JournalReservation reservation = reserve(firstProvider, 1);
    long sequence = reservation.sequence();
    long token = reservation.providerToken();
    assertEquals(StatusCode.CONFLICT, reservation.reset());
    assertEquals(sequence, reservation.sequence());
    assertEquals(token, reservation.providerToken());

    assertEquals(
        StatusCode.CONFLICT,
        firstProvider.reserve(
            reserveRequest(2, contractRequirement()), reservation, detail()));
    assertTrue(reservation.isActive());
    assertEquals(sequence, reservation.sequence());
    assertEquals(token, reservation.providerToken());
    JournalAppendResult foreignResult = new JournalAppendResult();
    assertEquals(
        StatusCode.CONFLICT,
        secondProvider.publish(
            reservation, appendRequest(1, TransactionDecision.COMMITTED),
            foreignResult, detail()));
    assertTrue(reservation.isActive());
    JournalReservation forgedClone = new JournalReservation();
    assertEquals(
        StatusCode.CONFLICT,
        secondProvider.publish(
            forgedClone, appendRequest(1, TransactionDecision.COMMITTED),
            foreignResult, detail()));
    publish(firstProvider, reservation, 1, TransactionDecision.COMMITTED);
    assertEquals(
        StatusCode.CONFLICT,
        firstProvider.publish(
            reservation, appendRequest(1, TransactionDecision.COMMITTED),
            foreignResult, detail()));

    JournalAppendResult pending = append(firstProvider, 2, TransactionDecision.COMMITTED);
    DurabilityTicket ticket = beginPending(firstProvider, pending.sequence(), 100);
    long requiredSequence = ticket.requiredSequence();
    assertEquals(StatusCode.CONFLICT, ticket.reset());
    assertEquals(requiredSequence, ticket.requiredSequence());
    assertEquals(
        StatusCode.CONFLICT,
        firstProvider.beginDurabilityWait(
            waitRequest(pending.sequence(), contractRequirement(), 100),
            ticket, new DurabilityResult(), detail()));
    assertEquals(requiredSequence, ticket.requiredSequence());
    assertEquals(
        StatusCode.CONFLICT,
        secondProvider.pollDurability(
            ticket, 1, CancellationToken.NONE, new DurabilityResult(), detail()));
    assertTrue(ticket.isActive());
    assertEquals(
        StatusCode.CANCELLED,
        firstProvider.cancelDurabilityWait(ticket, new DurabilityResult(), detail()));
    assertEquals(
        StatusCode.CONFLICT,
        firstProvider.cancelDurabilityWait(ticket, new DurabilityResult(), detail()));

    assertEquals(StatusCode.OK, firstHarness.writeThrough(JOURNAL_GENERATION, 2));
    assertEquals(
        StatusCode.OK,
        firstHarness.forceThrough(JOURNAL_GENERATION, 2, ForceCompletion.SUCCEEDED));
    WalRetentionLease lease = new WalRetentionLease();
    assertEquals(
        StatusCode.OK,
        firstProvider.acquireRetentionLease(leaseRequest(7, 1, 0, 50), lease, detail()));
    long leaseToken = lease.providerToken();
    assertEquals(StatusCode.CONFLICT, lease.reset());
    assertEquals(leaseToken, lease.providerToken());
    assertEquals(
        StatusCode.CONFLICT,
        firstProvider.acquireRetentionLease(leaseRequest(8, 2, 0, 50), lease, detail()));
    assertEquals(leaseToken, lease.providerToken());
    assertEquals(StatusCode.CONFLICT, secondProvider.releaseRetentionLease(lease, detail()));
    assertTrue(lease.isActive());
    assertEquals(StatusCode.OK, firstProvider.releaseRetentionLease(lease, detail()));
    assertEquals(StatusCode.CONFLICT, firstProvider.releaseRetentionLease(lease, detail()));
  }

  @Test
  final void unknownForceReopenPreservesPriorDurabilityAndResolvesAttemptDeterministically() {
    JournalProviderHarness durableHarness = openHarness(4);
    JournalProvider durableProvider = durableHarness.provider();
    append(durableProvider, 1, TransactionDecision.COMMITTED);
    assertEquals(StatusCode.OK, durableHarness.writeThrough(JOURNAL_GENERATION, 1));
    assertEquals(
        StatusCode.OK,
        durableHarness.forceThrough(JOURNAL_GENERATION, 1, ForceCompletion.SUCCEEDED, 1));
    append(durableProvider, 2, TransactionDecision.COMMITTED);
    assertEquals(StatusCode.OK, durableHarness.writeThrough(JOURNAL_GENERATION, 2));
    assertEquals(
        StatusCode.IO_FAILURE,
        durableHarness.forceThrough(JOURNAL_GENERATION, 2, ForceCompletion.UNKNOWN, 2));
    assertEquals(RequestOutcomeState.DURABLE, lookup(durableProvider, NODE, 1, 2).state());
    assertEquals(RequestOutcomeState.UNKNOWN, lookup(durableProvider, NODE, 2, 2).state());
    assertEquals(1, frontiers(durableProvider).localWalDurableSequence());
    NodeIncarnation durableRestart = NodeIncarnation.of(61, 62);
    assertEquals(
        StatusCode.OK,
        durableHarness.crashAndRestart(
            durableRestart, UnknownRecoveryResolution.DURABLE, 3));
    assertEquals(2, frontiers(durableProvider).localWalDurableSequence());
    assertEquals(
        RequestOutcomeState.DURABLE,
        lookup(durableProvider, durableRestart, 2, 3).state());
    assertEquals(3, reserve(durableProvider, durableRestart, 3).sequence());

    JournalProviderHarness lostHarness = openHarness(4);
    JournalProvider lostProvider = lostHarness.provider();
    append(lostProvider, 1, TransactionDecision.COMMITTED);
    assertEquals(StatusCode.OK, lostHarness.writeThrough(JOURNAL_GENERATION, 1));
    assertEquals(
        StatusCode.OK,
        lostHarness.forceThrough(JOURNAL_GENERATION, 1, ForceCompletion.SUCCEEDED, 1));
    append(lostProvider, 2, TransactionDecision.COMMITTED);
    assertEquals(StatusCode.OK, lostHarness.writeThrough(JOURNAL_GENERATION, 2));
    assertEquals(
        StatusCode.IO_FAILURE,
        lostHarness.forceThrough(JOURNAL_GENERATION, 2, ForceCompletion.UNKNOWN, 2));
    NodeIncarnation lostRestart = NodeIncarnation.of(71, 72);
    assertEquals(
        StatusCode.OK,
        lostHarness.crashAndRestart(
            lostRestart, UnknownRecoveryResolution.NOT_DURABLE, 3));
    assertEquals(1, frontiers(lostProvider).localWalDurableSequence());
    assertEquals(
        RequestOutcomeState.DURABLE,
        lookup(lostProvider, lostRestart, 1, 3).state());
    assertEquals(
        RequestOutcomeState.NOT_DURABLE,
        lookup(lostProvider, lostRestart, 2, 3).state());
    assertEquals(2, reserve(lostProvider, lostRestart, 3).sequence());
  }

  @Test
  final void frontierSnapshotsRemainAtomicAndMonotonicForConcurrentReaders() throws Exception {
    JournalProviderHarness harness = openHarness(128);
    JournalProvider provider = harness.provider();
    JournalAppendResult first = append(provider, 1, TransactionDecision.COMMITTED);
    assertEquals(StatusCode.OK, harness.writeThrough(JOURNAL_GENERATION, first.sequence()));
    assertEquals(
        StatusCode.OK,
        harness.forceThrough(JOURNAL_GENERATION, first.sequence(), ForceCompletion.SUCCEEDED));
    AtomicBoolean running = new AtomicBoolean(true);
    AtomicReference<AssertionError> failure = new AtomicReference<>();
    CountDownLatch started = new CountDownLatch(1);
    Thread reader = Thread.ofPlatform().name("journal-frontier-reader").unstarted(() -> {
      long prepared = 0;
      long committed = 0;
      long durable = 0;
      JournalFrontierSnapshot snapshot = new JournalFrontierSnapshot();
      RequestOutcomeResult outcome = new RequestOutcomeResult();
      JournalPositionMapping mapping = new JournalPositionMapping();
      RetentionSnapshot retention = new RetentionSnapshot();
      StatusDetail readDetail = detail();
      IdempotencyKey key = IdempotencyKey.of(3001, 4001);
      JournalPosition position = JournalPosition.of(DATABASE, JOURNAL_GENERATION, 1);
      started.countDown();
      while (running.get()) {
        StatusCode frontierStatus = provider.snapshotFrontiers(snapshot);
        StatusCode outcomeStatus = provider.lookupOutcome(
            DATABASE, NODE, key, 0, outcome, readDetail);
        StatusCode mappingStatus = provider.inspectMapping(position, mapping);
        StatusCode retentionStatus = provider.snapshotRetention(0, retention);
        if (frontierStatus != StatusCode.OK
            || outcomeStatus != StatusCode.OK
            || mappingStatus != StatusCode.OK
            || retentionStatus != StatusCode.OK
            || outcome.state() != RequestOutcomeState.DURABLE
            || mapping.sequence() != 1
            || snapshot.preparedSequence() < prepared
            || snapshot.journalCommittedSequence() < committed
            || snapshot.localWalDurableSequence() < durable
            || snapshot.localWalDurableSequence() > snapshot.journalCommittedSequence()
            || snapshot.journalCommittedSequence() > snapshot.preparedSequence()) {
          failure.compareAndSet(null, new AssertionError("non-monotonic or torn snapshot"));
          return;
        }
        prepared = snapshot.preparedSequence();
        committed = snapshot.journalCommittedSequence();
        durable = snapshot.localWalDurableSequence();
      }
    });
    reader.start();
    started.await();
    for (int index = 2; index <= 100; index++) {
      append(provider, index, TransactionDecision.NONE);
      assertEquals(StatusCode.OK, harness.writeThrough(JOURNAL_GENERATION, index));
      assertEquals(
          StatusCode.OK,
          harness.forceThrough(JOURNAL_GENERATION, index, ForceCompletion.SUCCEEDED));
    }
    running.set(false);
    reader.join();
    if (failure.get() != null) {
      throw failure.get();
    }
    assertFrontiers(frontiers(provider), 100, 0, 100, 100, 0);
  }

  private JournalProviderHarness openHarness(int capacity) {
    return openHarness(capacity, 32, 4, 1_000_000_000L);
  }

  private JournalReservation reserve(JournalProvider provider, long identity) {
    return reserve(provider, NODE, identity);
  }

  private JournalReservation reserve(
      JournalProvider provider,
      NodeIncarnation node,
      long identity) {
    JournalReservation reservation = new JournalReservation();
    assertEquals(
        StatusCode.OK,
        provider.reserve(
            reserveRequest(node, identity, contractRequirement()),
            reservation,
            detail()));
    return reservation;
  }

  private JournalAppendResult append(
      JournalProvider provider,
      long identity,
      TransactionDecision decision) {
    return publish(provider, reserve(provider, identity), identity, decision);
  }

  private JournalAppendResult publish(
      JournalProvider provider,
      JournalReservation reservation,
      long identity,
      TransactionDecision decision) {
    JournalAppendResult result = new JournalAppendResult();
    assertEquals(
        StatusCode.OK,
        provider.publish(reservation, appendRequest(identity, decision), result, detail()));
    return result;
  }

  private JournalAppendRequest appendRequest(
      long identity,
      TransactionDecision decision) {
    long transactionId = decision == TransactionDecision.NONE ? identity : identity;
    long commitSequence = decision == TransactionDecision.COMMITTED ? identity + 100 : 0;
    return new JournalAppendRequest().set(
        ByteBuffer.wrap(new byte[] {(byte) identity}),
        1,
        1,
        transactionId,
        commitSequence,
        decision);
  }

  private JournalReserveRequest reserveRequest(
      long identity,
      DurabilityRequirement requirement) {
    return reserveRequest(NODE, identity, requirement);
  }

  private JournalReserveRequest reserveRequest(
      NodeIncarnation node,
      long identity,
      DurabilityRequirement requirement) {
    return new JournalReserveRequest().set(
        DATABASE,
        node,
        1000 + identity,
        2000 + identity,
        3000 + identity,
        4000 + identity,
        requirement,
        1,
        0);
  }

  private DurabilityTicket beginPending(
      JournalProvider provider,
      long sequence,
      long deadline) {
    DurabilityTicket ticket = new DurabilityTicket();
    assertEquals(
        StatusCode.RETRY,
        provider.beginDurabilityWait(
            waitRequest(sequence, contractRequirement(), deadline),
            ticket,
            new DurabilityResult(),
            detail()));
    return ticket;
  }

  private DurabilityWaitRequest waitRequest(
      long sequence,
      DurabilityRequirement requirement,
      long deadline) {
    return new DurabilityWaitRequest().set(
        DATABASE, NODE, JOURNAL_GENERATION, sequence, requirement, deadline);
  }

  private RequestOutcomeResult lookup(
      JournalProvider provider,
      NodeIncarnation node,
      long identity) {
    return lookup(provider, node, identity, 0);
  }

  private RequestOutcomeResult lookup(
      JournalProvider provider,
      NodeIncarnation node,
      long identity,
      long nowNanos) {
    RequestOutcomeResult result = new RequestOutcomeResult();
    assertEquals(
        StatusCode.OK,
        provider.lookupOutcome(
            DATABASE, node, IdempotencyKey.of(3000 + identity, 4000 + identity),
            nowNanos, result, detail()));
    return result;
  }

  private WalRetentionLeaseRequest leaseRequest(
      long leaseId,
      long minimumSequence,
      long now,
      long expiry) {
    return new WalRetentionLeaseRequest().set(
        DATABASE,
        NODE,
        leaseId,
        RetentionOwnerKind.BACKUP,
        JournalPosition.of(DATABASE, JOURNAL_GENERATION, minimumSequence),
        now,
        expiry);
  }

  private JournalFrontierSnapshot frontiers(JournalProvider provider) {
    JournalFrontierSnapshot snapshot = new JournalFrontierSnapshot();
    assertEquals(StatusCode.OK, provider.snapshotFrontiers(snapshot));
    return snapshot;
  }

  private void assertFrontiers(
      JournalFrontierSnapshot snapshot,
      long prepared,
      long memoryReplicated,
      long committed,
      long localDurable,
      long quorumDurable) {
    assertEquals(prepared, snapshot.preparedSequence());
    assertEquals(memoryReplicated, snapshot.memoryReplicatedSequence());
    assertEquals(committed, snapshot.journalCommittedSequence());
    assertEquals(localDurable, snapshot.localWalDurableSequence());
    assertEquals(quorumDurable, snapshot.quorumWalDurableSequence());
  }

  private StatusDetail detail() {
    return new StatusDetail(128);
  }
}
