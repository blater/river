package io.riverdb.platform.fault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class FaultPointRegistryTest {
  @Test
  void duplicateNameResolvesToStableIdentityAndCapacityIsBounded() {
    FaultPointRegistry registry = new FaultPointRegistry(1);
    FaultPointSlot first = new FaultPointSlot();
    FaultPointSlot duplicate = new FaultPointSlot();
    FaultPointSlot overflow = new FaultPointSlot();

    assertEquals(StatusCode.OK, registry.register("wal.before-force", first));
    assertEquals(StatusCode.OK, registry.register("wal.before-force", duplicate));
    assertSame(first.value(), duplicate.value());
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        registry.register("wal.after-force", overflow));
  }

  @Test
  void rejectsInvalidExternallyAuthoredNames() {
    FaultPointRegistry registry = new FaultPointRegistry(1);
    FaultPointSlot slot = new FaultPointSlot();

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, registry.register("", slot));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, registry.register("  ", slot));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, registry.register(null, slot));
  }
}
