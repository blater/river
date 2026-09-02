package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class SqlCatalogRowBufferTest {
  private static volatile long allocationGuard;

  @Test
  void admitsOnlyTheBoundedCatalogShape() {
    SqlCatalogRowBuffer row = new SqlCatalogRowBuffer();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, row.reserve(-1, 0));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, row.reserve(0, -1));
    assertEquals(StatusCode.OK, row.reserve(5, 512));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, row.reserve(6, 512));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, row.reserve(5, 513));
  }

  @Test
  void publishesTheFullSupplementaryCharacterBridge() {
    SqlCatalogRowBuffer row = new SqlCatalogRowBuffer();
    SqlPhysicalPlan plan = objectPlan();
    SqlScanRowResult result = new SqlScanRowResult();
    String objectName = "🌊".repeat(64);
    char[] actual = new char[128];

    assertEquals(StatusCode.OK, row.reserve(2, 261));
    assertEquals(StatusCode.OK, row.loadObject(plan, objectName, "TABLE"));
    assertEquals(StatusCode.OK, row.publish(7, result));
    assertEquals(128, result.copyTextAt(0, actual, 0));
    assertEquals(objectName, new String(actual));
    assertEquals(7, result.key());
  }

  @Test
  void warmedMaterializationAndPublicationDoNotAllocatePerRow() {
    ThreadMXBean bean = allocationBean();
    SqlCatalogRowBuffer row = new SqlCatalogRowBuffer();
    SqlPhysicalPlan plan = objectPlan();
    SqlScanRowResult result = new SqlScanRowResult();
    assertEquals(StatusCode.OK, row.reserve(2, 261));
    exercise(row, plan, result, 10_000);

    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    exercise(row, plan, result, 100_000);
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
      SqlCatalogRowBuffer row,
      SqlPhysicalPlan plan,
      SqlScanRowResult result,
      int iterations) {
    for (int index = 0; index < iterations; index++) {
      allocationGuard += row.loadObject(plan, "river", "TABLE").stableCode();
      allocationGuard += row.publish(index, result).stableCode();
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
