package io.riverdb.bench.tpcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

final class TpccRetryTest {
  @Test
  void distinguishesExpectedBusinessRollbackFromRetryExhaustion() throws Exception {
    TpccConfig config = TpccConfig.parse(new String[] {
        "--url=jdbc:river://localhost:9", "--tiny"
    });
    TpccRetry.Result result = TpccRetry.execute(
        () -> false, config, System.nanoTime() + 5_000_000_000L);
    assertFalse(result.committed());
    assertFalse(result.retryExhausted());
    assertEquals(0, result.retries());
  }

  @Test
  void reportsSerializationExhaustionAsBoundedRollback() throws Exception {
    TpccConfig config = TpccConfig.parse(new String[] {
        "--url=jdbc:river://localhost:9", "--tiny", "--maximum-attempts=2"
    });
    int[] attempts = {0};
    TpccRetry.Result result = TpccRetry.execute(() -> {
      attempts[0]++;
      throw new SQLException("injected conflict", "40001");
    }, config, System.nanoTime() + 5_000_000_000L);
    assertFalse(result.committed());
    assertEquals(true, result.retryExhausted());
    assertEquals(1, result.retries());
    assertEquals(2, attempts[0]);
  }
}
