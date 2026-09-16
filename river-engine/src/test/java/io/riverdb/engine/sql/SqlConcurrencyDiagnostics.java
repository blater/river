package io.riverdb.engine.sql;

import static io.riverdb.engine.sql.SqlConcurrencyFixture.read;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.sql.SqlConcurrencyFixture.Edge;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Verifies deadlock events, edges, and scheduler resource identity from the real database. */
final class SqlConcurrencyDiagnostics {
  private SqlConcurrencyDiagnostics() { }

  static void cycle(RelationalDatabase database, String text, int rollbacks,
      long[] pendingSteps, int victim, Edge... expected) {
    validDiagnostics(text);
    assertTrue(text.contains("server_deadlock_victim_selections=1\n"), text);
    assertTrue(text.contains("server_deadlock_victim_outcomes=1\n"), text);
    assertTrue(text.contains("server_deadlock_queued_requests_cancelled=1\n"), text);
    assertEquals(1, rollbacks);
    List<String> events = text.lines().filter(s -> s.startsWith("deadlock_event ")).toList();
    assertEquals(1, events.size(), text);
    var event = fields(events.getFirst());
    assertEquals("" + (101 + victim), event.get("attempt_tag"));
    assertEquals("" + pendingSteps[victim], event.get("step_tag"));
    assertEquals("DEADLOCK", event.get("outcome"));
    assertEquals("1", event.get("queued_cancelled"));
    assertEquals("true", event.get("cleanup_valid"));
    assertTrue(Long.parseLong(event.get("holdings_released")) > 0);
    assertTrue(text.contains("server_deadlock_holdings_released=" + event.get("holdings_released") + "\n"));
    var edges = text.lines().filter(s -> s.startsWith("deadlock_edge ")).map(SqlConcurrencyDiagnostics::fields).toList();
    assertEquals(expected.length, edges.size());
    for (Edge want : expected) {
      var matches = edges.stream().filter(e -> e.get("waiter_attempt_tag").equals("" + (101 + want.waiter()))).toList();
      assertEquals(1, matches.size(), text);
      var edge = matches.getFirst();
      assertEquals("" + (101 + want.blocker()), edge.get("blocker_attempt_tag"), text);
      assertEquals("" + pendingSteps[want.waiter()], edge.get("waiter_step_tag"), text);
      assertEquals("" + pendingSteps[want.blocker()], edge.get("blocker_step_tag"), text);
      assertEquals(want.scope(), edge.get("scope"), text);
      assertEquals(want.requested(), edge.get("requested_mode"), text);
      assertEquals(want.held(), edge.get("held_mode"), text);
      assertEquals(want.queue(), edge.get("waiter_queue"), text);
      assertEquals("" + want.namespace(), edge.get("resource_namespace"), text);
      assertEquals("" + want.key(), edge.get("resource_lower"), text);
      assertEquals("" + want.namespace(), edge.get("resource_upper_namespace"), text);
      assertEquals("" + want.key(), edge.get("resource_upper"), text);
      assertEquals("false", edge.get("grant_predicate"), text);
      boolean fifo = want.held().equals("null");
      assertEquals(fifo ? "FIFO_FAIRNESS" : "ACTIVE_OWNER", edge.get("kind"), text);
      assertEquals(fifo ? "NO_EARLIER_INCOMPATIBLE_WAITER" : "NO_INCOMPATIBLE_ACTIVE_OWNER", edge.get("precondition"), text);
      assertEquals(fifo ? "ORDINARY" : "ACTIVE_OWNER", edge.get("blocker_queue"), text);
      assertEquals(fifo ? "EXCLUSIVE" : "null", edge.get("blocker_requested_mode"), text);
      if (fifo) assertTrue(Long.parseLong(edge.get("blocker_order")) < Long.parseLong(edge.get("waiter_order")));
    }
    try {
      var manager = (io.riverdb.tx.TransactionManager) read(read(database, "embedded"), "transactions");
      var snapshot = manager.newDeadlockDiagnosticsSnapshot();
      assertEquals(StatusCode.OK, manager.snapshotDeadlockDiagnostics(snapshot));
      assertEquals(expected.length, snapshot.exemplars().edgeCountAt(0));
      var digests = new HashMap<Integer, Long>();
      for (int i = 0; i < expected.length; i++) {
        int edge = snapshot.exemplars().edgeIndex(0, i);
        int owner = (int) snapshot.edges().waiterDiagnosticTagAt(edge) - 101;
        long digest = snapshot.edges().resourceDigestAt(edge);
        digests.put(owner, digest);
        System.out.println("attempt_tag=" + (101 + owner) + " resource_digest=" + digest
            + " blocker_resource_digest=" + snapshot.edges().blockingResourceDigestAt(edge));
      }
      if (expected.length == 3 && expected[2].held().equals("null")) {
        assertEquals(digests.get(1), digests.get(2), "same row1 KEY resource");
        assertNotEquals(digests.get(0), digests.get(1), "row2 tuple resource differs");
      } else {
        assertEquals(expected.length, new java.util.HashSet<>(digests.values()).size(),
            "distinct scheduler resources (including distinct KEY/TUPLE_KEY scopes for conversion)");
      }
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("cannot read existing scheduler resource snapshot", failure);
    }
    System.out.println("victims=1 queued_cancellations=1 explicit_rollbacks=" + rollbacks + " retries=0");
  }

  static void validDiagnostics(String text) {
    assertTrue(text.contains("server_deadlock_diagnostics_valid=true\n"), text);
    text.lines().filter(s -> s.contains("_overflows=")).forEach(s -> assertTrue(s.endsWith("=0"), s));
  }

  private static Map<String, String> fields(String line) {
    var result = new HashMap<String, String>();
    for (String word : line.split(" ")) {
      int equal = word.indexOf('=');
      if (equal > 0) result.put(word.substring(0, equal), word.substring(equal + 1));
    }
    return result;
  }

}
