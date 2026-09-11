package io.riverdb.engine.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import java.lang.management.ManagementFactory;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Assumptions;

final class DatabaseResourceGovernorTestSupport {
  static void cycle(
      DatabaseResourceGovernor governor, ResourceDemand demand, ResourceLease lease) {
    if (governor.reserve(7, 9, demand, lease) != StatusCode.OK
        || governor.release(7, 9, lease) != StatusCode.OK) throw new AssertionError();
  }

  static void assertConservation(DatabaseResourceGovernor governor) {
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

  static void assertLive(
      DatabaseResourceGovernor governor, int leases, long memory,
      long writes, long pages, long versions, long wal) {
    assertEquals(leases, governor.liveLeaseCount());
    assertEquals(memory, governor.liveAccountedBytes());
    assertEquals(writes, governor.liveWriteEntries());
    assertEquals(pages, governor.liveStagedPages());
    assertEquals(versions, governor.liveVersionOperations());
    assertEquals(wal, governor.liveWalBytes());
  }

  static ResourceDemand demand(
      long memory, long writes, long pages, long versions, long wal) {
    ResourceDemand demand = new ResourceDemand();
    assertEquals(StatusCode.OK, demand.set(memory, writes, pages, versions, wal));
    return demand;
  }

  static Fixture fixture(long maximumBytes) {
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

  static DatabaseResourceGovernor unretainedGovernor(long maximumBytes) {
    RuntimeResourceRoot.Result rootResult = new RuntimeResourceRoot.Result();
    assertEquals(StatusCode.OK, RuntimeResourceRoot.create(maximumBytes, rootResult));
    RuntimeResourceRoot.DatabaseResult database = new RuntimeResourceRoot.DatabaseResult();
    assertEquals(StatusCode.OK,
        rootResult.root().admit(DatabaseResourcePlanTest.plan(maximumBytes), database));
    return database.governor();
  }

  static ThreadMXBean allocationBean() {
    java.lang.management.ThreadMXBean standard = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standard instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standard;
    Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
    return bean;
  }

  static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  record Fixture(
      RuntimeResourceRoot root,
      DatabaseResourceGovernor governor,
      DatabaseRetainedLease baseline) {}
}
