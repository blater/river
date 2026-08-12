package io.riverdb.testkit.journal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.concurrent.CancellationToken;
import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.journal.api.JournalAppendRequest;
import io.riverdb.journal.api.JournalAppendResult;
import io.riverdb.journal.api.JournalReservation;
import io.riverdb.journal.api.JournalReserveRequest;
import io.riverdb.journal.api.NodeIncarnation;
import io.riverdb.journal.api.durability.DurabilityRequirement;
import io.riverdb.journal.api.durability.DurabilityOutcome;
import io.riverdb.journal.api.durability.DurabilityResult;
import io.riverdb.journal.api.durability.DurabilityTicket;
import io.riverdb.journal.api.durability.DurabilityWaitRequest;
import io.riverdb.journal.api.outcome.TransactionDecision;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class DeterministicJournalAllocationTest {
  private static volatile long allocationGuard;

  @Test
  void warmedReservePublishWaitAndPollUseOnlyCallerOwnedCarriers() {
    ThreadMXBean bean = allocationBean();
    DatabaseIncarnation database = DatabaseIncarnation.of(1, 2);
    NodeIncarnation node = NodeIncarnation.of(3, 4);
    long journalGeneration = 5;
    DeterministicJournalProvider provider = new DeterministicJournalProvider(
        database,
        node,
        journalGeneration,
        WalGeneration.of(6),
        6_000,
        1,
        new FatalStateFence());
    JournalReserveRequest reserveRequest = new JournalReserveRequest();
    JournalReservation reservation = new JournalReservation();
    JournalAppendRequest appendRequest = new JournalAppendRequest().set(
        1, 1, 0, 0, TransactionDecision.NONE);
    JournalAppendResult appendResult = new JournalAppendResult();
    DurabilityWaitRequest waitRequest = new DurabilityWaitRequest();
    DurabilityTicket ticket = new DurabilityTicket();
    DurabilityResult durabilityResult = new DurabilityResult();
    StatusDetail detail = new StatusDetail(0);

    for (int index = 1; index <= 500; index++) {
      exercise(
          provider,
          database,
          node,
          journalGeneration,
          index,
          reserveRequest,
          reservation,
          appendRequest,
          appendResult,
          waitRequest,
          ticket,
          durabilityResult,
          detail);
    }

    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 501; index <= 5_500; index++) {
      exercise(
          provider,
          database,
          node,
          journalGeneration,
          index,
          reserveRequest,
          reservation,
          appendRequest,
          appendResult,
          waitRequest,
          ticket,
          durabilityResult,
          detail);
    }
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;

    assertTrue(durabilityResult.outcome() == DurabilityOutcome.SATISFIED);
    assertTrue(
        allocated <= 256,
        "warmed journal common path allocated more than measurement noise: " + allocated);
  }

  private static void exercise(
      DeterministicJournalProvider provider,
      DatabaseIncarnation database,
      NodeIncarnation node,
      long journalGeneration,
      long identity,
      JournalReserveRequest reserveRequest,
      JournalReservation reservation,
      JournalAppendRequest appendRequest,
      JournalAppendResult appendResult,
      DurabilityWaitRequest waitRequest,
      DurabilityTicket ticket,
      DurabilityResult durabilityResult,
      StatusDetail detail) {
    reserveRequest.set(
        database,
        node,
        identity,
        identity + 1,
        identity + 2,
        identity + 3,
        DurabilityRequirement.LOCAL_DURABLE,
        1,
        0);
    allocationGuard += provider.reserve(reserveRequest, reservation, detail).ordinal();
    reservation.writablePayload().put((byte) identity);
    allocationGuard += provider.publish(
        reservation, appendRequest, appendResult, detail).ordinal();
    allocationGuard += appendResult.walGeneration().value();
    waitRequest.set(
        database,
        node,
        journalGeneration,
        appendResult.sequence(),
        DurabilityRequirement.LOCAL_DURABLE,
        0);
    allocationGuard += provider.beginDurabilityWait(
        waitRequest, ticket, durabilityResult, detail).ordinal();
    allocationGuard += provider.pollDurability(
        ticket, 1, CancellationToken.NONE, durabilityResult, detail).ordinal();
    allocationGuard += durabilityResult.walGeneration().value();
    allocationGuard += provider.writeThrough(journalGeneration, appendResult.sequence()).ordinal();
    allocationGuard += provider.forceThrough(
        journalGeneration, appendResult.sequence(), ForceCompletion.SUCCEEDED).ordinal();
    allocationGuard += provider.pollDurability(
        ticket, 1, CancellationToken.NONE, durabilityResult, detail).ordinal();
    allocationGuard += durabilityResult.walGeneration().value();
  }

  private static ThreadMXBean allocationBean() {
    java.lang.management.ThreadMXBean standardBean = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standardBean instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standardBean;
    Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    bean.setThreadAllocatedMemoryEnabled(true);
    return bean;
  }
}
