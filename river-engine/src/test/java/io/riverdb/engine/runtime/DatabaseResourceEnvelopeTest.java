package io.riverdb.engine.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class DatabaseResourceEnvelopeTest {
  @Test
  void admitsOnlyTheCallerSuppliedPhysicalBudgets() {
    DatabaseResourceEnvelope.Result result = new DatabaseResourceEnvelope.Result();
    DatabaseResourcePlanRequest request = new DatabaseResourcePlanRequest()
        .memory(128_000_000L, 0, 0, 0, 40_000_000L)
        .lockProviderBytes(8_000_000L)
        .versionWorkspaceBytes(4_000_000L)
        .indexedPageCache(32_000_000L, 8_000_000L)
        .capacity(8, Integer.MAX_VALUE, 600, 40_000_000L)
        .maximumDelivery(Integer.MAX_VALUE, 512, 40_000_000L);
    assertEquals(StatusCode.OK,
        DatabaseResourceEnvelope.create(request, 0, result));

    assertEquals(Integer.MAX_VALUE, result.plan().writeEntryCapacity());
    assertEquals(8_000_000L, result.plan().lockProviderBytes());
    assertTrue(result.plan().lockProviderBytes() < result.plan().accountedCapacityBytes());
    assertEquals(600, result.plan().stagedPageCapacity());
    assertEquals(40_000_000L, result.plan().walByteCapacity());
    assertEquals(128_000_000L, result.root().maximumAccountedBytes());
  }

  @Test
  void rejectsAnEnvelopeWhoseExplicitProvidersAndDeliveryExceedTheRoot() {
    DatabaseResourceEnvelope.Result result = new DatabaseResourceEnvelope.Result();
    DatabaseResourcePlanRequest request = new DatabaseResourcePlanRequest()
        .memory(32_000_000L, 0, 0, 0, 20_000_000L)
        .lockProviderBytes(8_000_000L)
        .versionWorkspaceBytes(4_000_000L)
        .indexedPageCache(16_000_000L, 4_000_000L)
        .capacity(8, 1_000, 600, 20_000_000L)
        .maximumDelivery(1_000, 128, 20_000_000L);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        DatabaseResourceEnvelope.create(request, 0, result));
    assertNull(result.plan());
    assertNull(result.root());
  }

  @Test
  void chargesPageFramesMetadataAndBothRetainedSqlCaches() {
    RiverRuntimeConfig config = new RiverRuntimeConfig(
        4_000_000L, 8_000_000L, 12_000_000L,
        40_000, 100, 80_000, 2, 100, 128, 2,
        5_000_000_000L, Path.of("scratch"));

    assertEquals(24_025_600L,
        DatabaseResourceEnvelope.retainedSqlRuntimeBytes(config));
  }
}
