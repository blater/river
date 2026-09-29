package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class SqlCatalogRowPublisherTest {
  private static volatile long allocationGuard;

  @Test
  void publishesSupplementaryTextDirectlyIntoTheOwnedResult() {
    SqlCatalogRowPublisher rows = new SqlCatalogRowPublisher();
    SqlPhysicalPlan plan = objectPlan();
    SqlScanRowResult result = new SqlScanRowResult();
    String objectName = "🌊".repeat(64);
    char[] actual = new char[128];

    assertEquals(StatusCode.OK, rows.object(plan, objectName, "TABLE", result));
    assertEquals(128, result.copyTextAt(0, actual, 0));
    assertEquals(objectName, new String(actual));
    assertEquals(0, result.key());
    assertEquals(StatusCode.OK, rows.object(plan, "next", "VIEW", result));
    assertEquals(4, result.copyTextAt(0, actual, 0));
    assertEquals("next", new String(actual, 0, 4));
  }

  @Test
  void warmedPublicationDoesNotAllocatePerRow() {
    ThreadMXBean bean = allocationBean();
    SqlCatalogRowPublisher rows = new SqlCatalogRowPublisher();
    SqlPhysicalPlan plan = objectPlan();
    SqlScanRowResult result = new SqlScanRowResult();
    exercise(rows, plan, result, 10_000);

    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    exercise(rows, plan, result, 100_000);
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;
    assertTrue(allocated <= 256, "warmed catalog rows allocated: " + allocated);
  }

  private static SqlPhysicalPlan objectPlan() {
    SqlPhysicalPlan plan = new SqlPhysicalPlan();
    assertEquals(StatusCode.OK, plan.beginResult(2));
    plan.setResultColumn(0, 0, SqlTypeDescriptor.varchar(64), "name");
    plan.setResultColumn(1, 1, SqlTypeDescriptor.varchar(5), "type");
    return plan;
  }

  private static void exercise(
      SqlCatalogRowPublisher rows, SqlPhysicalPlan plan,
      SqlScanRowResult result, int iterations) {
    for (int index = 0; index < iterations; index++) {
      allocationGuard += rows.object(plan, "river", "TABLE", result).stableCode();
      allocationGuard += result.key();
    }
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
