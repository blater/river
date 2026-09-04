package io.riverdb.engine.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class DatabaseResourcePlanTest {
  private static final long MAXIMUM_BYTES = 64_000_000L;

  @Test
  void compilesOnePhysicalCacheAuthorityAndImmutableCapacities() {
    DatabaseResourcePlan.Result result = new DatabaseResourcePlan.Result();
    DatabaseResourcePlanRequest request = request(MAXIMUM_BYTES);
    assertEquals(StatusCode.OK, DatabaseResourcePlan.compile(request, result));
    DatabaseResourcePlan plan = result.plan();

    assertEquals(MAXIMUM_BYTES, plan.maximumAccountedBytes());
    assertEquals(4, plan.maximumOwners());
    assertEquals(900, plan.writeEntryCapacity());
    assertEquals(2_000_000, plan.lockProviderBytes());
    assertEquals(plan.indexedPageCache().activeStagedPages(), plan.stagedPageCapacity());
    assertTrue(plan.indexedPageCache().maximumRetainedBytes() <= 16_000_000L);
    assertTrue(plan.indexedPageCache().stagingRetainedBytes() <= 4_000_000L);
    assertEquals(20_000_000, plan.walByteCapacity());

    request.memory(30_000_000, 100, 50, 0, 20_000_000);
    assertEquals(MAXIMUM_BYTES, plan.maximumAccountedBytes());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        DatabaseResourcePlan.compile(request, result));
    assertNull(result.plan());
  }

  @Test
  void rejectsOverflowInvalidBudgetsAndDeliveryBeyondPhysicalStaging() {
    DatabaseResourcePlan.Result result = new DatabaseResourcePlan.Result();
    DatabaseResourcePlanRequest overflow = request(Long.MAX_VALUE)
        .memory(Long.MAX_VALUE, 1, 1, Long.MAX_VALUE, 1);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        DatabaseResourcePlan.compile(overflow, result));
    assertNull(result.plan());

    DatabaseResourcePlanRequest empty = new DatabaseResourcePlanRequest();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        DatabaseResourcePlan.compile(empty, result));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        DatabaseResourcePlan.compile(empty, null));

    DatabaseResourcePlanRequest beyondStaging = request(MAXIMUM_BYTES)
        .maximumDelivery(700, (1L << 29) + 1, 10_000_000);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        DatabaseResourcePlan.compile(beyondStaging, result));
    assertNull(result.plan());
  }

  @Test
  void rejectsWriteCapacityBeyondSessionAddressability() {
    DatabaseResourcePlan.Result result = new DatabaseResourcePlan.Result();
    long unsupported = (long) Integer.MAX_VALUE + 1;

    assertEquals(StatusCode.OK,
        DatabaseResourcePlan.compile(request(MAXIMUM_BYTES)
            .capacity(4, Integer.MAX_VALUE, 300, 20_000_000)
            .maximumDelivery(
                Integer.MAX_VALUE, 300, 10_000_000), result));

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        DatabaseResourcePlan.compile(request(MAXIMUM_BYTES)
            .capacity(4, unsupported, 300, 20_000_000), result));
    assertNull(result.plan());
  }

  static DatabaseResourcePlan plan(long maximumBytes) {
    DatabaseResourcePlanRequest request = new DatabaseResourcePlanRequest()
        .memory(maximumBytes, 100, 50, 100, 400)
        .lockProviderBytes(50)
        .versionWorkspaceBytes(100_000)
        .indexedPageCache(1, 1)
        .capacity(4, 900, 70, 700)
        .maximumDelivery(700, 60, 650);
    DatabaseVersionWorkspacePlan.Result versionResult =
        new DatabaseVersionWorkspacePlan.Result();
    assertEquals(StatusCode.OK,
        DatabaseVersionWorkspacePlan.compile(100_000, versionResult));
    return new DatabaseResourcePlan(
        request,
        maximumBytes - 550,
        DatabasePageCachePlan.testingGeometry(2, 2, 70),
        versionResult.plan());
  }

  private static DatabaseResourcePlanRequest request(long maximumBytes) {
    return new DatabaseResourcePlanRequest()
        .memory(maximumBytes, 100, 50, 0, 20_000_000)
        .lockProviderBytes(2_000_000)
        .versionWorkspaceBytes(1_000_000)
        .indexedPageCache(16_000_000, 4_000_000)
        .capacity(4, 900, 600, 20_000_000)
        .maximumDelivery(700, 300, 10_000_000);
  }
}
