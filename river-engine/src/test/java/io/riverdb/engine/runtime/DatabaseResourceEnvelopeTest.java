package io.riverdb.engine.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class DatabaseResourceEnvelopeTest {
  @Test
  void derivesTypedCapacityBeyondTheLegacyTransactionLimit() {
    DatabaseResourceEnvelope.Result result = new DatabaseResourceEnvelope.Result();
    assertEquals(StatusCode.OK,
        DatabaseResourceEnvelope.create(128_000_000L, 8, 40_000_000L, result));

    assertTrue(result.plan().writeEntryCapacity() > 384);
    assertTrue(result.plan().lockProviderBytes() >= 1L << 20);
    assertTrue(result.plan().lockProviderBytes() < result.plan().accountedCapacityBytes());
    assertEquals(result.plan().accountedCapacityBytes(),
        40_000_000L + result.plan().lockProviderBytes()
            + result.plan().maximumDeliveryAccountedBytes());
    assertTrue(result.plan().stagedPageCapacity() > 0);
    assertTrue(result.plan().walByteCapacity() > 0);
    assertEquals(128_000_000L, result.root().maximumAccountedBytes());
  }

  @Test
  void rejectsAnEnvelopeWhoseRetainedRuntimeConsumesDeliveryProgress() {
    DatabaseResourceEnvelope.Result result = new DatabaseResourceEnvelope.Result();
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        DatabaseResourceEnvelope.create(32_000_000L, 8, 31_000_000L, result));
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
