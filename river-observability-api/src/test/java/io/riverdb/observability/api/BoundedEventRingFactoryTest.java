package io.riverdb.observability.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.observability.api.event.BoundedEventRing;
import io.riverdb.observability.api.event.BoundedEventRingFactory;
import io.riverdb.observability.api.event.ConsumerAccess;
import io.riverdb.observability.api.event.ObservabilityBuildMode;
import io.riverdb.observability.api.event.SaturationPolicy;
import io.riverdb.observability.api.event.Severity;
import org.junit.jupiter.api.Test;

class BoundedEventRingFactoryTest {
  @Test
  void diagnosticAndTestModesGuardTheSingleConsumerInvariant() {
    assertEquals(ConsumerAccess.GUARDED, ring(ObservabilityBuildMode.DIAGNOSTIC).consumerAccess());
    assertEquals(ConsumerAccess.GUARDED, ring(ObservabilityBuildMode.DIAGNOSTIC).consumerAccess());
  }

  @Test
  void productionModeUsesEstablishedOwnerFastPath() {
    assertEquals(ConsumerAccess.UNCHECKED, ring(ObservabilityBuildMode.PRODUCTION).consumerAccess());
  }

  private static BoundedEventRing ring(ObservabilityBuildMode buildMode) {
    return BoundedEventRingFactory.create(
        2,
        Severity.INFO,
        SaturationPolicy.DROP_AND_COUNT,
        buildMode);
  }
}
