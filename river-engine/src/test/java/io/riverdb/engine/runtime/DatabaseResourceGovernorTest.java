package io.riverdb.engine.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import java.lang.management.ManagementFactory;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class DatabaseResourceGovernorTest {
  @Test
  void grantsWholeVectorOrLeavesEveryCounterUnchanged() {
    Fixture fixture = fixture(1_000);
    ResourceDemand firstDemand = demand(300, 500, 40, 500, 400);
    ResourceLease first = new ResourceLease();
    assertEquals(StatusCode.OK, fixture.governor.reserve(11, 7, firstDemand, first));
    assertLive(fixture.governor, 1, 300, 500, 40, 500, 400);

    ResourceDemand blocked = demand(400, 401, 1, 401, 1);
    ResourceLease second = new ResourceLease();
    assertEquals(StatusCode.RETRY, fixture.governor.reserve(12, 7, blocked, second));
    assertFalse(second.active());
    assertLive(fixture.governor, 1, 300, 500, 40, 500, 400);

    ResourceDemand impossible = demand(451, 1, 1, 1, 1);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        fixture.governor.reserve(12, 7, impossible, second));
    assertLive(fixture.governor, 1, 300, 500, 40, 500, 400);
    assertEquals(StatusCode.OK, fixture.governor.release(11, 7, first));
    assertEquals(StatusCode.OK, fixture.governor.reserve(12, 7, blocked, second));
    assertEquals(StatusCode.OK, fixture.governor.release(12, 7, second));
    assertLive(fixture.governor, 0, 0, 0, 0, 0, 0);
  }

  @Test
  void versionOperationsAreAdmittedReleasedAndConservedIndependently() {
    Fixture fixture = fixture(1_000);
    long versionCapacity = fixture.governor.plan().versionOperationCapacity();
    ResourceLease occupying = new ResourceLease();
    ResourceDemand occupyingDemand = demand(100, 1, 1, versionCapacity - 1, 1);
    assertEquals(StatusCode.OK,
        fixture.governor.reserve(1, 1, occupyingDemand, occupying));
    assertLive(fixture.governor, 1, 100, 1, 1, versionCapacity - 1, 1);
    assertEquals(1, fixture.governor.availableVersionOperations());
    assertConservation(fixture.governor);

    ResourceLease blocked = new ResourceLease();
    ResourceDemand blockedDemand = demand(1, 1, 1, 2, 1);
    assertEquals(StatusCode.RETRY,
        fixture.governor.reserve(2, 1, blockedDemand, blocked));
    assertFalse(blocked.active());
    assertLive(fixture.governor, 1, 100, 1, 1, versionCapacity - 1, 1);
    assertConservation(fixture.governor);

    assertEquals(StatusCode.OK, fixture.governor.release(1, 1, occupying));
    assertEquals(StatusCode.OK,
        fixture.governor.reserve(2, 1, blockedDemand, blocked));
    assertLive(fixture.governor, 1, 1, 1, 1, 2, 1);
    assertConservation(fixture.governor);
    assertEquals(StatusCode.OK, fixture.governor.release(2, 1, blocked));
    assertLive(fixture.governor, 0, 0, 0, 0, 0, 0);
    assertConservation(fixture.governor);
  }

  @Test
  void maximumDeliveryUsesOnlyLendablePoolAndLeavesOwnerReserveUntouched() {
    Fixture fixture = fixture(1_000);
    DatabaseResourcePlan plan = fixture.governor.plan();
    ResourceLease delivery = new ResourceLease();
    ResourceDemand maximum = demand(
        plan.maximumDeliveryAccountedBytes(), plan.maximumDeliveryWriteEntries(),
        plan.maximumDeliveryStagedPages(), plan.versionOperationCapacity(),
        plan.maximumDeliveryWalBytes());
    assertEquals(StatusCode.OK, fixture.governor.reserve(1, 1, maximum, delivery));
    assertEquals(0, fixture.governor.availableAccountedBytes());
    ResourceLease borrower = new ResourceLease();
    assertEquals(StatusCode.RETRY,
        fixture.governor.reserve(2, 1, demand(1, 1, 1, 1, 1), borrower));
    assertFalse(borrower.active());
    assertEquals(StatusCode.OK, fixture.governor.release(1, 1, delivery));
    assertEquals(StatusCode.OK,
        fixture.governor.reserve(2, 1, demand(1, 1, 1, 1, 1), borrower));
    assertEquals(StatusCode.OK, fixture.governor.release(2, 1, borrower));
  }

  @Test
  void retryTicketsAreFifoAndCancellationAdvancesTheHead() {
    Fixture fixture = fixture(1_000);
    ResourceLease occupying = new ResourceLease();
    ResourceLease first = new ResourceLease();
    ResourceLease second = new ResourceLease();
    ResourceDemand firstDemand = demand(100, 1, 1, 1, 1);
    ResourceDemand secondDemand = demand(50, 1, 1, 1, 1);
    assertEquals(StatusCode.OK,
        fixture.governor.reserve(1, 1, demand(400, 1, 1, 1, 1), occupying));
    assertEquals(StatusCode.RETRY,
        fixture.governor.reserve(2, 4, firstDemand, first));
    assertEquals(StatusCode.RETRY,
        fixture.governor.reserve(3, 5, secondDemand, second));
    assertTrue(first.retryTicket() > 0);
    assertTrue(second.retryTicket() > first.retryTicket());
    assertEquals(2, fixture.governor.waitingLeaseCount());

    assertEquals(StatusCode.OK, fixture.governor.release(1, 1, occupying));
    assertEquals(StatusCode.RETRY,
        fixture.governor.reserve(3, 5, secondDemand, second));
    assertEquals(StatusCode.NOT_OWNER, fixture.governor.cancel(2, 3, first));
    assertEquals(StatusCode.OK, fixture.governor.cancel(2, 4, first));
    assertFalse(first.waiting());
    assertEquals(StatusCode.OK,
        fixture.governor.reserve(3, 5, secondDemand, second));
    assertEquals(StatusCode.OK, fixture.governor.release(3, 5, second));
    assertEquals(0, fixture.governor.waitingLeaseCount());
  }

  @Test
  void rejectsWrongGenerationDoubleReleaseAndCrossGovernorLease() {
    Fixture firstFixture = fixture(1_000);
    Fixture secondFixture = fixture(1_000);
    ResourceLease lease = new ResourceLease();
    assertEquals(StatusCode.OK,
        firstFixture.governor.reserve(19, 3, demand(100, 2, 4, 2, 5), lease));
    assertEquals(StatusCode.NOT_OWNER, firstFixture.governor.release(19, 2, lease));
    assertEquals(StatusCode.NOT_OWNER, secondFixture.governor.release(19, 3, lease));
    assertLive(firstFixture.governor, 1, 100, 2, 4, 2, 5);
    assertEquals(StatusCode.OK, firstFixture.governor.release(19, 3, lease));
    assertEquals(StatusCode.NOT_OWNER, firstFixture.governor.release(19, 3, lease));
    assertLive(firstFixture.governor, 0, 0, 0, 0, 0, 0);
    assertEquals(StatusCode.OK, lease.reset());
  }

  @Test
  void oneOwnerGenerationCannotSplitItsAtomicDemandAcrossLeases() {
    Fixture fixture = fixture(1_000);
    ResourceDemand demand = demand(100, 2, 4, 2, 5);
    ResourceLease first = new ResourceLease();
    ResourceLease duplicate = new ResourceLease();
    assertEquals(StatusCode.OK, fixture.governor.reserve(19, 3, demand, first));
    assertEquals(StatusCode.CONFLICT,
        fixture.governor.reserve(19, 3, demand, duplicate));
    assertLive(fixture.governor, 1, 100, 2, 4, 2, 5);
    assertEquals(StatusCode.OK, fixture.governor.release(19, 3, first));
    assertEquals(StatusCode.OK, fixture.governor.reserve(19, 3, demand, duplicate));
    assertEquals(StatusCode.OK, fixture.governor.release(19, 3, duplicate));
  }

  @Test
  void waitingOwnerGenerationAlsoCannotQueueTwice() {
    Fixture fixture = fixture(1_000);
    ResourceDemand occupyingDemand = demand(400, 1, 1, 1, 1);
    ResourceDemand waitingDemand = demand(100, 1, 1, 1, 1);
    ResourceLease occupying = new ResourceLease();
    ResourceLease waiting = new ResourceLease();
    ResourceLease duplicate = new ResourceLease();
    assertEquals(StatusCode.OK,
        fixture.governor.reserve(1, 1, occupyingDemand, occupying));
    assertEquals(StatusCode.RETRY,
        fixture.governor.reserve(2, 1, waitingDemand, waiting));
    assertEquals(StatusCode.CONFLICT,
        fixture.governor.reserve(2, 1, waitingDemand, duplicate));
    assertEquals(1, fixture.governor.waitingLeaseCount());
    assertEquals(StatusCode.OK, fixture.governor.cancel(2, 1, waiting));
    assertEquals(StatusCode.OK, fixture.governor.release(1, 1, occupying));
  }

  @Test
  void activeAndWaitingOwnersShareTheCompiledOwnerCeiling() {
    Fixture fixture = fixture(1_000);
    ResourceLease[] active = new ResourceLease[4];
    for (int index = 0; index < active.length; index++) {
      active[index] = new ResourceLease();
      assertEquals(StatusCode.OK,
          fixture.governor.reserve(index + 1, 1, demand(100, 1, 1, 1, 1), active[index]));
    }
    ResourceLease excess = new ResourceLease();
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        fixture.governor.reserve(5, 1, demand(100, 1, 1, 1, 1), excess));
    assertFalse(excess.waiting());
    for (int index = 0; index < active.length; index++) {
      assertEquals(StatusCode.OK,
          fixture.governor.release(index + 1, 1, active[index]));
    }
  }

  @Test
  void aggregateLeaseGrowsByAtomicTotalAndCannotShrink() {
    Fixture fixture = fixture(1_000);
    ResourceLease lease = new ResourceLease();
    assertEquals(StatusCode.OK,
        fixture.governor.ensure(1, 1, demand(100, 10, 1, 10, 20), lease, true));
    assertEquals(StatusCode.OK,
        fixture.governor.ensure(1, 1, demand(300, 30, 3, 30, 60), lease, true));
    assertLive(fixture.governor, 1, 300, 30, 3, 30, 60);
    assertEquals(StatusCode.CONFLICT,
        fixture.governor.ensure(1, 1, demand(299, 30, 3, 30, 60), lease, true));
    assertLive(fixture.governor, 1, 300, 30, 3, 30, 60);
    assertEquals(StatusCode.OK, fixture.governor.end(1, 1, lease));
    assertLive(fixture.governor, 0, 0, 0, 0, 0, 0);
  }

  @Test
  void activeGrowthNeverQueuesBehindAnInitialWaiter() {
    Fixture fixture = fixture(1_000);
    ResourceLease occupying = new ResourceLease();
    ResourceLease growing = new ResourceLease();
    assertEquals(StatusCode.OK,
        fixture.governor.ensure(1, 1, demand(300, 1, 1, 1, 1), occupying, true));
    assertEquals(StatusCode.OK,
        fixture.governor.ensure(2, 1, demand(100, 1, 1, 1, 1), growing, true));
    assertEquals(StatusCode.RETRY,
        fixture.governor.ensure(2, 1, demand(200, 2, 2, 2, 2), growing, true));
    assertTrue(growing.active());
    assertFalse(growing.waiting());
    assertLive(fixture.governor, 2, 400, 2, 2, 2, 2);
    assertEquals(StatusCode.OK, fixture.governor.end(2, 1, growing));
    assertFalse(growing.active());
    assertEquals(0, fixture.governor.waitingLeaseCount());
    assertLive(fixture.governor, 1, 300, 1, 1, 1, 1);
    assertEquals(StatusCode.OK, fixture.governor.end(1, 1, occupying));
  }

  @Test
  void activeGrowthUsesAvailableIndependentDimensionsDespiteInitialWaiter() {
    Fixture fixture = fixture(1_000);
    ResourceLease occupying = new ResourceLease();
    ResourceLease growing = new ResourceLease();
    assertEquals(StatusCode.OK,
        fixture.governor.ensure(1, 1, demand(300, 1, 1, 1, 1), occupying, true));
    assertEquals(StatusCode.OK,
        fixture.governor.ensure(2, 1, demand(100, 1, 1, 1, 1), growing, true));
    ResourceLease initialWaiter = new ResourceLease();
    assertEquals(StatusCode.RETRY,
        fixture.governor.ensure(3, 1, demand(100, 1, 1, 1, 1), initialWaiter, true));
    assertEquals(StatusCode.OK,
        fixture.governor.ensure(2, 1, demand(100, 2, 2, 2, 2), growing, false));
    assertTrue(growing.active());
    assertFalse(growing.waiting());
    assertEquals(1, fixture.governor.waitingLeaseCount());
    assertLive(fixture.governor, 2, 400, 3, 3, 3, 3);
    assertEquals(StatusCode.OK, fixture.governor.cancel(3, 1, initialWaiter));
    assertEquals(StatusCode.OK, fixture.governor.end(2, 1, growing));
    assertEquals(StatusCode.OK, fixture.governor.end(1, 1, occupying));
  }

  @Test
  void rootDistinguishesEverFitFromLiveChildPressureAndReclaimsExactly() {
    RuntimeResourceRoot.Result rootResult = new RuntimeResourceRoot.Result();
    assertEquals(StatusCode.OK, RuntimeResourceRoot.create(1_500, rootResult));
    RuntimeResourceRoot root = rootResult.root();
    RuntimeResourceRoot.DatabaseResult database = new RuntimeResourceRoot.DatabaseResult();
    assertEquals(StatusCode.OK, root.admit(DatabaseResourcePlanTest.plan(1_000), database));
    DatabaseResourceGovernor first = database.governor();
    assertEquals(1_000, root.admittedAccountedBytes());
    assertEquals(StatusCode.RETRY,
        root.admit(DatabaseResourcePlanTest.plan(1_000), database));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        root.admit(DatabaseResourcePlanTest.plan(2_000), database));
    assertEquals(1_000, root.admittedAccountedBytes());
    assertEquals(StatusCode.OK, first.close());
    assertEquals(StatusCode.CLOSED, first.close());
    assertEquals(0, root.admittedAccountedBytes());
    assertEquals(1_500, root.availableAccountedBytes());
  }

  @Test
  void retainedDatabaseStorageReducesOnlyTheLendableByteDimension() {
    Fixture fixture = fixture(1_000);
    DatabaseResourceGovernor governor = fixture.governor;
    DatabaseRetainedLease retained = new DatabaseRetainedLease();
    assertEquals(StatusCode.OK,
        governor.ensureRetainedDatabaseAccountedBytes(50, retained));
    assertEquals(100, governor.retainedDatabaseAccountedBytes());
    assertEquals(350, governor.availableAccountedBytes());

    ResourceLease lease = new ResourceLease();
    assertEquals(StatusCode.OK,
        governor.reserve(1, 1, demand(350, 1, 1, 1, 1), lease));
    assertEquals(StatusCode.OK,
        governor.ensureRetainedDatabaseAccountedBytes(50, retained));
    assertEquals(StatusCode.RETRY,
        governor.ensureRetainedDatabaseAccountedBytes(51, retained));
    assertEquals(StatusCode.OK, governor.release(1, 1, lease));
    assertEquals(StatusCode.OK,
        governor.ensureRetainedDatabaseAccountedBytes(51, retained));
    assertEquals(101, governor.retainedDatabaseAccountedBytes());
    assertEquals(StatusCode.CONFLICT, governor.close());
    assertEquals(StatusCode.OK,
        governor.releaseRetainedDatabaseAccountedBytes(retained));
    assertEquals(StatusCode.OK,
        governor.releaseRetainedDatabaseAccountedBytes(fixture.baseline));
    assertEquals(StatusCode.OK, governor.close());
    assertEquals(0, fixture.root.admittedAccountedBytes());
  }

  @Test
  void providerLeaseIsExclusiveAndFencesGovernorCloseUntilReleased() {
    RuntimeResourceRoot.Result rootResult = new RuntimeResourceRoot.Result();
    assertEquals(StatusCode.OK, RuntimeResourceRoot.create(20_000_000, rootResult));
    RuntimeResourceRoot.DatabaseResult database = new RuntimeResourceRoot.DatabaseResult();
    assertEquals(StatusCode.OK,
        rootResult.root().admit(DatabaseResourcePlanTest.plan(10_000_000), database));
    DatabaseProviderLease providers = new DatabaseProviderLease();
    DatabaseProviderLease duplicate = new DatabaseProviderLease();

    assertEquals(StatusCode.OK,
        database.governor().claimDatabaseProviders(0, providers));
    assertEquals(StatusCode.CONFLICT,
        database.governor().claimDatabaseProviders(0, duplicate));
    assertEquals(StatusCode.CONFLICT, database.governor().close());
    DatabaseStoreLease store = new DatabaseStoreLease();
    assertEquals(StatusCode.OK, providers.claimStore(1, 2, 1, store));
    assertEquals(StatusCode.CONFLICT,
        database.governor().releaseDatabaseProviders(providers));
    assertEquals(StatusCode.OK, providers.releaseStore(store));
    ResourceLease transaction = new ResourceLease();
    assertEquals(StatusCode.OK,
        database.governor().reserve(1, 1, demand(1, 1, 1, 1, 1), transaction));
    assertEquals(StatusCode.CONFLICT,
        database.governor().releaseDatabaseProviders(providers));
    assertEquals(StatusCode.OK, database.governor().release(1, 1, transaction));
    DatabaseRetainedLease component = new DatabaseRetainedLease();
    assertEquals(StatusCode.OK,
        database.governor().ensureRetainedDatabaseAccountedBytes(1, component));
    assertEquals(StatusCode.CONFLICT,
        database.governor().releaseDatabaseProviders(providers));
    assertEquals(StatusCode.OK,
        database.governor().releaseRetainedDatabaseAccountedBytes(component));
    assertEquals(StatusCode.OK,
        database.governor().releaseDatabaseProviders(providers));
    assertEquals(StatusCode.OK, database.governor().close());
    assertEquals(0, rootResult.root().admittedAccountedBytes());
  }

  @Test
  void retainedReceiptsAreOwnerAuthenticatedAndReleaseOnlyTheirComponent() {
    Fixture first = fixture(1_000);
    Fixture other = fixture(1_000);
    DatabaseRetainedLease left = new DatabaseRetainedLease();
    DatabaseRetainedLease right = new DatabaseRetainedLease();

    assertEquals(StatusCode.OK,
        first.governor.ensureRetainedDatabaseAccountedBytes(100, left));
    assertEquals(StatusCode.OK,
        first.governor.ensureRetainedDatabaseAccountedBytes(50, right));
    assertEquals(200, first.governor.retainedDatabaseAccountedBytes());
    assertEquals(StatusCode.NOT_OWNER,
        other.governor.releaseRetainedDatabaseAccountedBytes(left));
    assertEquals(200, first.governor.retainedDatabaseAccountedBytes());
    assertEquals(StatusCode.OK,
        first.governor.releaseRetainedDatabaseAccountedBytes(left));
    assertEquals(100, first.governor.retainedDatabaseAccountedBytes());
    assertEquals(StatusCode.OK,
        first.governor.releaseRetainedDatabaseAccountedBytes(right));
    assertEquals(50, first.governor.retainedDatabaseAccountedBytes());
  }

  @Test
  void oneReceiptCannotBeClaimedByTwoGovernorsConcurrently() throws InterruptedException {
    Fixture first = fixture(1_000);
    Fixture second = fixture(1_000);
    DatabaseRetainedLease shared = new DatabaseRetainedLease();
    StatusCode[] outcomes = new StatusCode[2];
    CountDownLatch start = new CountDownLatch(1);
    Thread left = Thread.startVirtualThread(() -> {
      await(start);
      outcomes[0] = first.governor.ensureRetainedDatabaseAccountedBytes(100, shared);
    });
    Thread right = Thread.startVirtualThread(() -> {
      await(start);
      outcomes[1] = second.governor.ensureRetainedDatabaseAccountedBytes(100, shared);
    });
    start.countDown();
    left.join();
    right.join();

    assertTrue(outcomes[0].isOk() ^ outcomes[1].isOk());
    DatabaseResourceGovernor owner = outcomes[0].isOk()
        ? first.governor : second.governor;
    DatabaseResourceGovernor loser = outcomes[0].isOk()
        ? second.governor : first.governor;
    assertEquals(150, owner.retainedDatabaseAccountedBytes());
    assertEquals(50, loser.retainedDatabaseAccountedBytes());
    assertEquals(StatusCode.OK, owner.releaseRetainedDatabaseAccountedBytes(shared));
  }

  @Test
  void oneProviderLeaseCannotBeClaimedByTwoGovernorsConcurrently()
      throws InterruptedException {
    DatabaseResourceGovernor first = unretainedGovernor(10_000_000);
    DatabaseResourceGovernor second = unretainedGovernor(10_000_000);
    DatabaseProviderLease shared = new DatabaseProviderLease();
    StatusCode[] outcomes = new StatusCode[2];
    CountDownLatch start = new CountDownLatch(1);
    Thread left = Thread.startVirtualThread(() -> {
      await(start);
      outcomes[0] = first.claimDatabaseProviders(0, shared);
    });
    Thread right = Thread.startVirtualThread(() -> {
      await(start);
      outcomes[1] = second.claimDatabaseProviders(0, shared);
    });
    start.countDown();
    left.join();
    right.join();

    assertTrue(outcomes[0].isOk() ^ outcomes[1].isOk());
    DatabaseResourceGovernor owner = outcomes[0].isOk() ? first : second;
    DatabaseResourceGovernor loser = outcomes[0].isOk() ? second : first;
    assertTrue(owner.retainedDatabaseAccountedBytes() > 0);
    assertEquals(0, loser.retainedDatabaseAccountedBytes());
    assertEquals(StatusCode.OK, owner.releaseDatabaseProviders(shared));
    assertEquals(StatusCode.OK, owner.close());
    assertEquals(StatusCode.OK, loser.close());
  }

  @Test
  void invalidDemandAssignmentClearsPriorVectorAndRetryRegistersNothing() {
    Fixture fixture = fixture(1_000);
    ResourceDemand demand = demand(100, 2, 4, 2, 5);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, demand.set(-1, 2, 4, 2, 5));
    assertEquals(0, demand.accountedBytes());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        fixture.governor.reserve(1, 1, demand, new ResourceLease()));
    assertLive(fixture.governor, 0, 0, 0, 0, 0, 0);
  }

  @Test
  void ownerMayBorrowBeyondGuaranteedDeliveryShapeWithinDatabaseCapacity() {
    Fixture fixture = fixture(1_000);
    ResourceLease lease = new ResourceLease();
    assertEquals(StatusCode.OK,
        fixture.governor.reserve(1, 1, demand(100, 701, 1, 701, 1), lease));
    assertLive(fixture.governor, 1, 100, 701, 1, 701, 1);
    assertEquals(StatusCode.OK, fixture.governor.release(1, 1, lease));
  }

  @Test
  void unpublishedAbandonmentRejectsLiveOwnersAndRequiresExplicitCleanup() {
    Fixture fixture = fixture(1_000);
    ResourceLease active = new ResourceLease();
    ResourceLease waiting = new ResourceLease();
    assertEquals(StatusCode.OK,
        fixture.governor.reserve(1, 1, demand(400, 1, 1, 1, 1), active));
    assertEquals(StatusCode.RETRY,
        fixture.governor.reserve(2, 1, demand(100, 1, 1, 1, 1), waiting));
    assertEquals(StatusCode.CONFLICT, fixture.governor.abandonAfterOpenFailure());
    assertTrue(waiting.waiting());
    assertEquals(StatusCode.OK, fixture.governor.cancel(2, 1, waiting));
    assertEquals(StatusCode.OK, fixture.governor.release(1, 1, active));
    assertEquals(StatusCode.OK,
        fixture.governor.releaseRetainedDatabaseAccountedBytes(fixture.baseline));
    assertEquals(StatusCode.OK, fixture.governor.abandonAfterOpenFailure());
    assertEquals(0, fixture.root.admittedAccountedBytes());
    assertEquals(StatusCode.CLOSED, fixture.governor.abandonAfterOpenFailure());
  }

  @Test
  void repeatedReserveReleaseAllocatesNothingAfterWarmup() {
    ThreadMXBean bean = allocationBean();
    Fixture fixture = fixture(1_000);
    ResourceDemand demand = demand(100, 2, 4, 2, 5);
    ResourceLease lease = new ResourceLease();
    for (int index = 0; index < 1_000; index++) cycle(fixture.governor, demand, lease);
    long thread = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(thread);
    for (int index = 0; index < 10_000; index++) cycle(fixture.governor, demand, lease);
    long allocated = bean.getThreadAllocatedBytes(thread) - before;
    assertEquals(0, allocated);
    assertLive(fixture.governor, 0, 0, 0, 0, 0, 0);
  }

  @Test
  void deterministicModelPreservesConservationAcrossMixedVectors() {
    Fixture fixture = fixture(1_000);
    ResourceLease[] leases = new ResourceLease[4];
    ResourceDemand[] demands = new ResourceDemand[4];
    for (int index = 0; index < 4; index++) {
      leases[index] = new ResourceLease();
      demands[index] = demand(
          100 + index * 10L, 20 + index, 2 + index, 20 + index, 30 + index);
    }
    long state = 91;
    for (int step = 0; step < 2_000; step++) {
      state = state * 6_364_136_223_846_793_005L + 1;
      int slot = (int) ((state >>> 62) & 3);
      if (leases[slot].active()) {
        assertEquals(StatusCode.OK, fixture.governor.release(slot + 1, 1, leases[slot]));
      } else {
        StatusCode status = fixture.governor.reserve(slot + 1, 1, demands[slot], leases[slot]);
        assertTrue(status == StatusCode.OK || status == StatusCode.RETRY);
      }
      assertConservation(fixture.governor);
    }
    for (int slot = 0; slot < 4; slot++) {
      if (leases[slot].active()) {
        assertEquals(StatusCode.OK, fixture.governor.release(slot + 1, 1, leases[slot]));
      } else if (leases[slot].waiting()) {
        assertEquals(StatusCode.OK, fixture.governor.cancel(slot + 1, 1, leases[slot]));
      }
    }
    assertLive(fixture.governor, 0, 0, 0, 0, 0, 0);
  }

  private static void cycle(
      DatabaseResourceGovernor governor, ResourceDemand demand, ResourceLease lease) {
    if (governor.reserve(7, 9, demand, lease) != StatusCode.OK
        || governor.release(7, 9, lease) != StatusCode.OK) throw new AssertionError();
  }

  private static void assertConservation(DatabaseResourceGovernor governor) {
    DatabaseResourcePlan plan = governor.plan();
    assertEquals(plan.accountedCapacityBytes(),
        governor.retainedDatabaseAccountedBytes()
            + governor.liveAccountedBytes() + governor.availableAccountedBytes());
    assertEquals(plan.writeEntryCapacity(),
        governor.liveWriteEntries() + governor.availableWriteEntries());
    assertEquals(plan.stagedPageCapacity(),
        governor.liveStagedPages() + governor.availableStagedPages());
    assertEquals(plan.versionOperationCapacity(),
        governor.liveVersionOperations() + governor.availableVersionOperations());
    assertEquals(plan.walByteCapacity(),
        governor.liveWalBytes() + governor.availableWalBytes());
  }

  private static void assertLive(
      DatabaseResourceGovernor governor, int leases, long memory,
      long writes, long pages, long versions, long wal) {
    assertEquals(leases, governor.liveLeaseCount());
    assertEquals(memory, governor.liveAccountedBytes());
    assertEquals(writes, governor.liveWriteEntries());
    assertEquals(pages, governor.liveStagedPages());
    assertEquals(versions, governor.liveVersionOperations());
    assertEquals(wal, governor.liveWalBytes());
  }

  private static ResourceDemand demand(
      long memory, long writes, long pages, long versions, long wal) {
    ResourceDemand demand = new ResourceDemand();
    assertEquals(StatusCode.OK, demand.set(memory, writes, pages, versions, wal));
    return demand;
  }

  private static Fixture fixture(long maximumBytes) {
    RuntimeResourceRoot.Result rootResult = new RuntimeResourceRoot.Result();
    assertEquals(StatusCode.OK, RuntimeResourceRoot.create(maximumBytes, rootResult));
    RuntimeResourceRoot.DatabaseResult database = new RuntimeResourceRoot.DatabaseResult();
    assertEquals(StatusCode.OK,
        rootResult.root().admit(DatabaseResourcePlanTest.plan(maximumBytes), database));
    DatabaseResourceGovernor governor = database.governor();
    DatabaseRetainedLease baseline = new DatabaseRetainedLease();
    assertEquals(StatusCode.OK,
        governor.ensureRetainedDatabaseAccountedBytes(
            governor.plan().lockProviderBytes(), baseline));
    return new Fixture(rootResult.root(), governor, baseline);
  }

  private static DatabaseResourceGovernor unretainedGovernor(long maximumBytes) {
    RuntimeResourceRoot.Result rootResult = new RuntimeResourceRoot.Result();
    assertEquals(StatusCode.OK, RuntimeResourceRoot.create(maximumBytes, rootResult));
    RuntimeResourceRoot.DatabaseResult database = new RuntimeResourceRoot.DatabaseResult();
    assertEquals(StatusCode.OK,
        rootResult.root().admit(DatabaseResourcePlanTest.plan(maximumBytes), database));
    return database.governor();
  }

  private static ThreadMXBean allocationBean() {
    java.lang.management.ThreadMXBean standard = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standard instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standard;
    Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
    return bean;
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private record Fixture(
      RuntimeResourceRoot root,
      DatabaseResourceGovernor governor,
      DatabaseRetainedLease baseline) {}
}
