package io.riverdb.bench.tpcc;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.jdbc.RiverConnectionMetrics;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

final class TpccRunPhaseTest {
  @Test
  void reportsWhetherCheckpointFailureHadOneCompleteServerResponse() throws Exception {
    assertFailureEvidence(0, "completed_requests_delta=0", "response=missing");
    assertFailureEvidence(1, "completed_requests_delta=1", "response=received");
    assertFailureEvidence(2, "completed_requests_delta=2", "response=ambiguous");
  }

  private static void assertFailureEvidence(
      long completedResponses, String delta, String response) throws Exception {
    MutableMetrics metrics = new MutableMetrics();
    SQLException expected = new SQLException("injected checkpoint failure", "58030");
    Connection connection = connection(metrics);
    Statement statement = failingStatement(metrics, completedResponses, expected);
    PrintStream previous = System.err;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
      System.setErr(capture);
      assertSame(expected, assertThrows(
          SQLException.class,
          () -> TpccRunPhase.executeCheckpoint(connection, statement, "load")));
    } finally {
      System.setErr(previous);
    }
    String evidence = output.toString(StandardCharsets.UTF_8);
    assertTrue(evidence.contains("phase=load"), evidence);
    assertTrue(evidence.matches("(?s).*elapsed_nanos=\\d+.*"), evidence);
    assertTrue(evidence.contains(delta), evidence);
    assertTrue(evidence.contains(response), evidence);
    assertTrue(evidence.contains("sql_state=58030"), evidence);
  }

  private static Connection connection(RiverConnectionMetrics metrics) {
    return (Connection) Proxy.newProxyInstance(
        Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
        (ignored, method, arguments) -> {
          if (method.getName().equals("unwrap")) return metrics;
          throw new UnsupportedOperationException(method.getName());
        });
  }

  private static Statement failingStatement(
      MutableMetrics metrics, long completedResponses, SQLException failure) {
    return (Statement) Proxy.newProxyInstance(
        Statement.class.getClassLoader(), new Class<?>[] {Statement.class},
        (ignored, method, arguments) -> {
          if (!method.getName().equals("executeUpdate")) {
            throw new UnsupportedOperationException(method.getName());
          }
          assertTrue(arguments[0].equals("CHECKPOINT"));
          metrics.requests += completedResponses;
          throw failure;
        });
  }

  private static final class MutableMetrics implements RiverConnectionMetrics {
    private long requests;

    @Override public long completedRequests() { return requests; }
    @Override public long bytesSent() { return 0; }
    @Override public long bytesReceived() { return 0; }
  }
}
