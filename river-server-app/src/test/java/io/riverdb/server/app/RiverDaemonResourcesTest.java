package io.riverdb.server.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.page.PageCodec;
import io.riverdb.protocol.ProtocolMemoryBudget;
import org.junit.jupiter.api.Test;

final class RiverDaemonResourcesTest {
  @Test
  void defaultDevelopmentProfileCompilesBeforeOpening() {
    RiverDaemonResources.Result result = new RiverDaemonResources.Result();
    assertEquals(StatusCode.OK, RiverDaemonResources.compile(16, result));
    assertNotNull(result.plan());
    assertEquals(16, result.maximumActiveTransactions());
    assertEquals(64_000_000L / PageCodec.PAGE_BYTES, result.plan().stagedPageCapacity());
    assertTrue(result.protocolBytes() <= 512_000_000L);
  }

  @Test
  void protocolBudgetRejectsAnExcessiveConnectionRequest() {
    assertTrue(ProtocolMemoryBudget.supportsServerConnections(16));
    int connections = Integer.MAX_VALUE;
    RiverDaemonResources.Result result = new RiverDaemonResources.Result();
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        RiverDaemonResources.compile(connections, result));
  }
}
