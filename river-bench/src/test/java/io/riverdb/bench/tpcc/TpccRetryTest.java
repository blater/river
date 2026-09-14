package io.riverdb.bench.tpcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class TpccRetryTest {
  @Test
  void distinguishesExpectedBusinessRollbackFromRetryExhaustion() throws Exception {
    TpccConfig config = TpccConfig.parse(new String[] {
        "--url=jdbc:river:client-file:/tmp/client.properties", "--tiny"
    });
    TpccRetry.Result result = TpccRetry.execute(
        () -> false, config, System.nanoTime() + 5_000_000_000L,
        TpccRetryObserver.NONE);
    assertFalse(result.committed());
    assertFalse(result.retryExhausted());
    assertEquals(0, result.retries());
  }

  @Test
  void reportsSerializationExhaustionAsBoundedRollback() throws Exception {
    TpccConfig config = TpccConfig.parse(new String[] {
        "--url=jdbc:river:client-file:/tmp/client.properties", "--tiny", "--maximum-attempts=2"
    });
    int[] attempts = {0};
    TpccRetry.Result result = TpccRetry.execute(() -> {
      attempts[0]++;
      throw new SQLException(
          "injected conflict", "40001",
          io.riverdb.base.error.StatusCode.CONFLICT.stableCode());
    }, config, System.nanoTime() + 5_000_000_000L, TpccRetryObserver.NONE);
    assertFalse(result.committed());
    assertEquals(true, result.retryExhausted());
    assertEquals(1, result.retries());
    assertEquals(2, attempts[0]);
  }

  @Test
  void doesNotMergeUnknownSerializationSqlStateIntoRiverRetry() throws Exception {
    TpccConfig config = TpccConfig.parse(new String[] {
        "--url=jdbc:river:client-file:/tmp/client.properties", "--tiny", "--maximum-attempts=2"
    });
    SQLException failure = org.junit.jupiter.api.Assertions.assertThrows(
        SQLException.class,
        () -> TpccRetry.execute(
            () -> { throw new SQLException("unknown serialization", "40001"); },
            config,
            System.nanoTime() + 5_000_000_000L,
            TpccRetryObserver.NONE));
    assertEquals(0, failure.getErrorCode());
  }

  @Test
  void preservesPrimaryFailureWhenRollbackFails() {
    SQLException primary = new SQLException("primary failure");
    SQLException rollback = new SQLException("rollback failure");
    Connection connection = (Connection) Proxy.newProxyInstance(
        Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
        (ignored, method, arguments) -> {
          if (method.getName().equals("rollback")) throw rollback;
          return null;
        });

    SQLException actual = org.junit.jupiter.api.Assertions.assertThrows(
        SQLException.class, () -> TpccRetry.rollbackAfterFailure(connection, primary));
    assertSame(primary, actual);
    assertSame(rollback, actual.getSuppressed()[0]);
  }

  @Test
  void timeoutAndLostResponseRemainTerminalWhenRollbackReportsConflict() throws Exception {
    for (StatusCode primaryStatus : new StatusCode[] {
        StatusCode.TIMEOUT, StatusCode.IO_FAILURE, StatusCode.CANCELLED}) {
      SQLException primary = new SQLException(
          "injected terminal request outcome", "HYT00", primaryStatus.stableCode());
      SQLException rollback = new SQLException(
          "injected rollback conflict", "40001", StatusCode.CONFLICT.stableCode());
      SQLException actual = runWithFailedRollback(primary, rollback);
      assertSame(primary, actual);
      assertSame(rollback, actual.getSuppressed()[0]);
    }
  }

  @Test
  void failedRollbackPreventsReplayEvenWhenBothFailuresAreRetryable() throws Exception {
    SQLException primary = new SQLException(
        "injected serialization conflict", "40001", StatusCode.CONFLICT.stableCode());
    SQLException rollback = new SQLException(
        "injected rollback conflict", "40001", StatusCode.CONFLICT.stableCode());
    SQLException actual = runWithFailedRollback(primary, rollback);
    assertEquals(StatusCode.IO_FAILURE, TpccStatusCodes.decode(actual));
    assertSame(rollback, actual.getCause());
    assertSame(primary, actual.getSuppressed()[0]);
  }

  @Test
  void successfulRollbackStillAllowsTheNextAttemptToCommit() throws Exception {
    TpccConfig config = TpccConfig.parse(new String[] {
        "--url=jdbc:river:client-file:/tmp/client.properties", "--tiny", "--maximum-attempts=2"
    });
    SQLException conflict = new SQLException(
        "injected serialization conflict", "40001", StatusCode.CONFLICT.stableCode());
    int[] attempts = {0};
    int[] rollbacks = {0};
    Connection connection = (Connection) Proxy.newProxyInstance(
        Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
        (ignored, method, arguments) -> {
          if (method.getName().equals("rollback")) rollbacks[0]++;
          return null;
        });
    TpccRetry.Result result = TpccRetry.execute(() -> {
      if (++attempts[0] == 1) throw TpccRetry.rollbackAfterFailure(connection, conflict);
      return true;
    }, config, System.nanoTime() + 5_000_000_000L, TpccRetryObserver.NONE);
    assertTrue(result.committed());
    assertFalse(result.retryExhausted());
    assertEquals(1, result.retries());
    assertEquals(2, attempts[0]);
    assertEquals(1, rollbacks[0]);
    assertEquals(0, conflict.getSuppressed().length);
  }

  private static SQLException runWithFailedRollback(
      SQLException primary, SQLException rollback) throws Exception {
    TpccConfig config = TpccConfig.parse(new String[] {
        "--url=jdbc:river:client-file:/tmp/client.properties", "--tiny", "--maximum-attempts=2"
    });
    int[] attempts = {0};
    int[] rollbacks = {0};
    Connection connection = (Connection) Proxy.newProxyInstance(
        Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
        (ignored, method, arguments) -> {
          if (method.getName().equals("rollback")) {
            rollbacks[0]++;
            throw rollback;
          }
          return null;
        });
    SQLException actual = assertThrows(SQLException.class, () -> TpccRetry.execute(() -> {
      attempts[0]++;
      throw TpccRetry.rollbackAfterFailure(connection, primary);
    }, config, System.nanoTime() + 5_000_000_000L, TpccRetryObserver.NONE));
    assertEquals(1, attempts[0]);
    assertEquals(1, rollbacks[0]);
    return actual;
  }

  @Test
  void exponentialDelayUsesConfiguredMaximumInsteadOfArbitraryExponent() {
    assertEquals(1L << 30, TpccRetry.exponentialDelayCap(1, 1L << 40, 31));
    assertEquals(1L << 40, TpccRetry.exponentialDelayCap(1, 1L << 40, 41));
    assertEquals(1L << 40, TpccRetry.exponentialDelayCap(1, 1L << 40, Integer.MAX_VALUE));
  }

  @Test
  void exponentialDelayAndJitterHandleLongAddressabilityBoundary() {
    assertEquals(Long.MAX_VALUE,
        TpccRetry.exponentialDelayCap(3, Long.MAX_VALUE, Integer.MAX_VALUE));
    assertEquals(Long.MAX_VALUE,
        TpccRetry.saturatedNanos(Duration.ofSeconds(Long.MAX_VALUE)));
    for (int sample = 0; sample < 100; sample++) {
      long delay = TpccRetry.randomDelay(Long.MAX_VALUE);
      assertTrue(delay >= 0);
    }
  }
}
