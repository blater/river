package io.riverdb.engine.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class DatabaseResourcePlanTest {
  @Test
  void compilesCheckedOpenInequalityAndImmutableCapacities() {
    DatabaseResourcePlan.Result result = new DatabaseResourcePlan.Result();
    DatabaseResourcePlanRequest request = request(1_000);
    assertEquals(StatusCode.OK, DatabaseResourcePlan.compile(request, result));
    DatabaseResourcePlan plan = result.plan();
    assertEquals(1_000, plan.maximumAccountedBytes());
    assertEquals(450, plan.accountedCapacityBytes());
    assertEquals(100, plan.minimumOwnerBytes());
    assertEquals(400, plan.maximumDeliveryAccountedBytes());
    assertEquals(4, plan.maximumOwners());
    assertEquals(900, plan.writeEntryCapacity());
    assertEquals(50, plan.lockProviderBytes());
    assertEquals(70, plan.stagedPageCapacity());
    assertEquals(700, plan.walByteCapacity());
    assertEquals(700, plan.maximumDeliveryWriteEntries());
    assertEquals(60, plan.maximumDeliveryStagedPages());
    assertEquals(650, plan.maximumDeliveryWalBytes());

    request.memory(999, 100, 50, 100, 400);
    assertEquals(1_000, plan.maximumAccountedBytes());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        DatabaseResourcePlan.compile(request, result));
    assertNull(result.plan());
  }

  @Test
  void rejectsArithmeticOverflowAndInvalidPhysicalInputs() {
    DatabaseResourcePlan.Result result = new DatabaseResourcePlan.Result();
    DatabaseResourcePlanRequest request = request(Long.MAX_VALUE)
        .memory(Long.MAX_VALUE, 1, 1, Long.MAX_VALUE, 1);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        DatabaseResourcePlan.compile(request, result));
    assertNull(result.plan());

    request.reset();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        DatabaseResourcePlan.compile(request, result));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        DatabaseResourcePlan.compile(request, null));

    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        DatabaseResourcePlan.compile(request(1_000).maximumDelivery(901, 60, 650), result));
  }

  @Test
  void rejectsCapacitiesThatCannotBeRepresentedByAnIndexedSession() {
    DatabaseResourcePlan.Result result = new DatabaseResourcePlan.Result();
    long unsupported = (long) Integer.MAX_VALUE + 1;

    assertEquals(StatusCode.OK,
        DatabaseResourcePlan.compile(request(1_000)
            .capacity(4, Integer.MAX_VALUE, 70, 700)
            .maximumDelivery(Integer.MAX_VALUE, 60, 650), result));

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        DatabaseResourcePlan.compile(request(1_000)
            .capacity(4, unsupported, 70, 700), result));
    assertNull(result.plan());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        DatabaseResourcePlan.compile(request(1_000)
            .capacity(4, unsupported, 70, 700)
            .maximumDelivery(unsupported, 60, 650), result));
    assertNull(result.plan());
  }

  static DatabaseResourcePlan plan(long maximumBytes) {
    DatabaseResourcePlan.Result result = new DatabaseResourcePlan.Result();
    assertEquals(StatusCode.OK,
        DatabaseResourcePlan.compile(request(maximumBytes), result));
    return result.plan();
  }

  private static DatabaseResourcePlanRequest request(long maximumBytes) {
    return new DatabaseResourcePlanRequest()
        .memory(maximumBytes, 100, 50, 100, 400)
        .lockProviderBytes(50)
        .capacity(4, 900, 70, 700)
        .maximumDelivery(700, 60, 650);
  }
}
