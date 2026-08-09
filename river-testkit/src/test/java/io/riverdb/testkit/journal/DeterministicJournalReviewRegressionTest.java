package io.riverdb.testkit.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.IdempotencyKey;
import io.riverdb.base.id.JournalPosition;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.journal.api.JournalAppendRequest;
import io.riverdb.journal.api.JournalAppendResult;
import io.riverdb.journal.api.JournalReservation;
import io.riverdb.journal.api.JournalReserveRequest;
import io.riverdb.journal.api.NodeIncarnation;
import io.riverdb.journal.api.durability.DurabilityRequirement;
import io.riverdb.journal.api.mapping.JournalPositionMapping;
import io.riverdb.journal.api.outcome.OutcomeRetentionSnapshot;
import io.riverdb.journal.api.outcome.RequestOutcomeResult;
import io.riverdb.journal.api.outcome.RequestOutcomeState;
import io.riverdb.journal.api.outcome.TransactionDecision;
import io.riverdb.journal.api.retention.RetentionOwnerKind;
import io.riverdb.journal.api.retention.WalRetentionLease;
import io.riverdb.journal.api.retention.WalRetentionLeaseRequest;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class DeterministicJournalReviewRegressionTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(101, 102);
  private static final NodeIncarnation NODE = NodeIncarnation.of(103, 104);
  private static final long GENERATION = 5;

  @Test
  void localLsnOverflowDoesNotConsumeLogicalSequenceOrMutateOutput() {
    DeterministicJournalProvider provider = provider();
    assertEquals(StatusCode.OK, provider.setNextWalStartForTest(Long.MAX_VALUE - 8));
    JournalReservation reservation = new JournalReservation();

    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        provider.reserve(reserveRequest(1), reservation, detail()));
    assertFalse(reservation.isActive());
    assertEquals(0, reservation.sequence());

    assertEquals(StatusCode.OK, provider.setNextWalStartForTest(0));
    assertEquals(StatusCode.OK, provider.reserve(reserveRequest(1), reservation, detail()));
    assertEquals(1, reservation.sequence());
  }

  @Test
  void retentionAcquireAndReopenFailAtomicallyWhenRequiredHistoryIsUnavailable() {
    DeterministicJournalProvider reclaimedProvider = provider();
    appendAndForce(reclaimedProvider, 1);
    assertEquals(
        StatusCode.OK,
        reclaimedProvider.reclaimThrough(
            JournalPosition.of(DATABASE, GENERATION, 1), 0));
    WalRetentionLease rejectedAcquire = new WalRetentionLease();
    assertEquals(
        StatusCode.CONFLICT,
        reclaimedProvider.acquireRetentionLease(
            leaseRequest(1), rejectedAcquire, detail()));
    assertFalse(rejectedAcquire.isActive());

    DeterministicJournalProvider damagedProvider = provider();
    appendAndForce(damagedProvider, 1);
    WalRetentionLease original = new WalRetentionLease();
    assertEquals(
        StatusCode.OK,
        damagedProvider.acquireRetentionLease(leaseRequest(1), original, detail()));
    assertEquals(StatusCode.OK, damagedProvider.discardHistoryForTest(1));
    WalRetentionLease rejectedReopen = new WalRetentionLease();
    assertEquals(
        StatusCode.CONFLICT,
        damagedProvider.reopenRetentionLease(
            DATABASE, NODE, 1, 1, rejectedReopen, detail()));
    assertFalse(rejectedReopen.isActive());
    assertEquals(1, original.minimumRequired().sequence());
  }

  @Test
  void outcomeSlotReuseCannotCorruptAnUnreclaimedWalEntryOrItsMapping() {
    DeterministicJournalProvider provider = new DeterministicJournalProvider(
        DATABASE, NODE, GENERATION, WalGeneration.of(7), 2, 32, 1, 100, 1, 10,
        new FatalStateFence());
    JournalReservation cancelled = new JournalReservation();
    assertEquals(StatusCode.OK, provider.reserve(reserveRequest(1), cancelled, detail()));
    assertEquals(StatusCode.CANCELLED, provider.cancelReservation(cancelled, detail()));
    OutcomeRetentionSnapshot retention = new OutcomeRetentionSnapshot();
    assertEquals(StatusCode.OK, provider.forgetExpiredOutcomes(10, retention, detail()));
    assertEquals(0, retention.retainedOutcomes());

    JournalReservation second = new JournalReservation();
    assertEquals(StatusCode.OK, provider.reserve(reserveRequest(2), second, detail()));
    JournalAppendResult append = new JournalAppendResult();
    assertEquals(
        StatusCode.OK,
        provider.publish(
            second,
            new JournalAppendRequest().set(
                ByteBuffer.wrap(new byte[] {2}), 1, 1, 202, 302,
                TransactionDecision.COMMITTED),
            append,
            detail()));
    assertEquals(StatusCode.OK, provider.writeThrough(GENERATION, 2));
    assertEquals(
        StatusCode.OK,
        provider.forceThrough(GENERATION, 2, ForceCompletion.SUCCEEDED, 20));

    JournalPositionMapping cancelledMapping = new JournalPositionMapping();
    assertEquals(
        StatusCode.OK,
        provider.inspectMapping(
            JournalPosition.of(DATABASE, GENERATION, 1), cancelledMapping));
    assertEquals(0, cancelledMapping.transactionId());
    JournalPositionMapping secondMapping = new JournalPositionMapping();
    assertEquals(
        StatusCode.OK,
        provider.inspectMapping(
            JournalPosition.of(DATABASE, GENERATION, 2), secondMapping));
    assertEquals(202, secondMapping.transactionId());
    assertEquals(302, secondMapping.commitSequence());

    RequestOutcomeResult outcome = new RequestOutcomeResult();
    assertEquals(
        StatusCode.OK,
        provider.lookupOutcome(
            DATABASE, NODE, IdempotencyKey.of(22, 32), 20, outcome, detail()));
    assertEquals(RequestOutcomeState.DURABLE, outcome.state());
    assertEquals(2, outcome.sequence());
  }

  private static DeterministicJournalProvider provider() {
    return new DeterministicJournalProvider(
        DATABASE, NODE, GENERATION, WalGeneration.of(7), 4, 32, new FatalStateFence());
  }

  private static void appendAndForce(DeterministicJournalProvider provider, long identity) {
    JournalReservation reservation = new JournalReservation();
    assertEquals(StatusCode.OK, provider.reserve(reserveRequest(identity), reservation, detail()));
    JournalAppendRequest append = new JournalAppendRequest().set(
        ByteBuffer.wrap(new byte[] {1}),
        1,
        1,
        identity,
        identity + 100,
        TransactionDecision.COMMITTED);
    assertEquals(
        StatusCode.OK,
        provider.publish(reservation, append, new JournalAppendResult(), detail()));
    assertEquals(StatusCode.OK, provider.writeThrough(GENERATION, identity));
    assertEquals(
        StatusCode.OK,
        provider.forceThrough(GENERATION, identity, ForceCompletion.SUCCEEDED));
  }

  private static JournalReserveRequest reserveRequest(long identity) {
    return new JournalReserveRequest().set(
        DATABASE,
        NODE,
        identity,
        identity + 10,
        identity + 20,
        identity + 30,
        DurabilityRequirement.LOCAL_DURABLE,
        1,
        0);
  }

  private static WalRetentionLeaseRequest leaseRequest(long leaseId) {
    return new WalRetentionLeaseRequest().set(
        DATABASE,
        NODE,
        leaseId,
        RetentionOwnerKind.RECOVERY,
        JournalPosition.of(DATABASE, GENERATION, 1),
        0,
        100);
  }

  private static StatusDetail detail() {
    return new StatusDetail(64);
  }
}
