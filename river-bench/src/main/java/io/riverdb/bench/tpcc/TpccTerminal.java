package io.riverdb.bench.tpcc;

import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.locks.LockSupport;

/** Closed-loop terminal using the standard 45/43/4/4/4 transaction mix. */
final class TpccTerminal implements AutoCloseable {
  private final TpccConfig config;
  private final TpccValues values;
  private final TpccSession session;
  private final int homeWarehouse;
  private final int homeDistrict;
  private final TpccInputs.NewOrder newOrder = new TpccInputs.NewOrder();
  private final TpccInputs.Payment payment = new TpccInputs.Payment();
  private final TpccInputs.CustomerOrder orderStatus = new TpccInputs.CustomerOrder();
  private final TpccInputs.Delivery delivery = new TpccInputs.Delivery();
  private final TpccInputs.StockLevel stockLevel = new TpccInputs.StockLevel();

  TpccTerminal(TpccConfig configuration, int terminal) throws SQLException {
    config = configuration;
    TpccTerminalHome home = TpccTerminalHome.at(configuration, terminal);
    homeWarehouse = home.warehouse();
    homeDistrict = home.district();
    values = new TpccValues(configuration.seed() + 0x9E37_79B9_7F4A_7C15L * (terminal + 1));
    Connection connection = DriverManager.getConnection(configuration.url());
    session = new TpccSession(connection, configuration, homeDistrict);
  }

  TpccMetrics run(long deadline, boolean measured) throws SQLException {
    TpccMetrics metrics = new TpccMetrics();
    if (measured) metrics.beginProgramFailures(session.newOrder);
    long allocatedBefore = allocatedBytes();
    boolean forceFamily = measured && config.scheduling() == TpccScheduling.NO_WAIT_STRESS;
    while (System.nanoTime() < deadline) {
      executeOne(measured ? metrics : null, deadline, forceFamily);
      forceFamily = false;
    }
    long allocatedAfter = allocatedBytes();
    if (measured && allocatedBefore >= 0 && allocatedAfter >= allocatedBefore) {
      metrics.allocated(allocatedAfter - allocatedBefore);
    }
    if (measured) metrics.completeProgramFailures(session.newOrder);
    return metrics;
  }

  private void executeOne(TpccMetrics metrics, long deadline, boolean forceFamily)
      throws SQLException {
    TpccTransactionType type = forceFamily
        ? TpccTransactionType.values()[(homeDistrict - 1) % 5] : choose();
    waitFor(type, true, deadline);
    if (System.nanoTime() >= deadline) return;
    TpccAttempt attempt = prepare(type);
    long requestsBefore = metrics == null ? 0 : session.completedRequests();
    long sentBefore = metrics == null ? 0 : session.bytesSent();
    long receivedBefore = metrics == null ? 0 : session.bytesReceived();
    long start = System.nanoTime();
    try {
      TpccRetry.Result result = TpccRetry.execute(attempt, config, deadline);
      long completed = System.nanoTime();
      if (metrics != null && completed <= deadline) {
        metrics.record(type, completed - start, result);
        recordProtocol(metrics, type, requestsBefore, sentBefore, receivedBefore);
      }
    } catch (SQLException failure) {
      if ("40001".equals(failure.getSQLState()) && System.nanoTime() >= deadline) return;
      if (metrics != null) {
        metrics.failure(type, System.nanoTime() - start);
        recordProtocol(metrics, type, requestsBefore, sentBefore, receivedBefore);
      }
      throw failure;
    }
    waitFor(type, false, deadline);
  }

  private void recordProtocol(
      TpccMetrics metrics, TpccTransactionType type,
      long requestsBefore, long sentBefore, long receivedBefore) {
    metrics.protocol(
        type,
        session.completedRequests() - requestsBefore,
        session.bytesSent() - sentBefore,
        session.bytesReceived() - receivedBefore);
  }

  private TpccTransactionType choose() {
    int choice = values.number(1, 100);
    if (choice <= 45) return TpccTransactionType.NEW_ORDER;
    if (choice <= 88) return TpccTransactionType.PAYMENT;
    if (choice <= 92) return TpccTransactionType.ORDER_STATUS;
    if (choice <= 96) return TpccTransactionType.DELIVERY;
    return TpccTransactionType.STOCK_LEVEL;
  }

  private TpccAttempt prepare(TpccTransactionType type) {
    return switch (type) {
      case NEW_ORDER -> {
        newOrder.generate(values, config, homeWarehouse, homeDistrict);
        yield () -> session.newOrder.execute(newOrder);
      }
      case PAYMENT -> {
        payment.generate(values, config, homeWarehouse, homeDistrict);
        yield () -> session.payment.execute(payment);
      }
      case ORDER_STATUS -> {
        orderStatus.generate(values, config, homeWarehouse, homeDistrict);
        yield () -> session.orderStatus.execute(orderStatus);
      }
      case DELIVERY -> {
        delivery.generate(values, homeWarehouse);
        yield () -> session.delivery.execute(delivery);
      }
      case STOCK_LEVEL -> {
        stockLevel.generate(values, homeWarehouse, homeDistrict);
        yield () -> session.stockLevel.execute(stockLevel);
      }
    };
  }

  private void waitFor(TpccTransactionType type, boolean keying, long deadline)
      throws SQLException {
    if (config.scheduling() == TpccScheduling.NO_WAIT_STRESS) return;
    int seconds = keying ? keyingSeconds(type) : thinkSeconds(type);
    double unit = values.number(1, 1_000_000) / 1_000_001.0;
    long mean = seconds * 1_000_000_000L;
    long delay = keying ? mean : Math.min(mean * 10, (long) (-Math.log(unit) * mean));
    long remaining = deadline - System.nanoTime();
    if (remaining > 0) LockSupport.parkNanos(Math.min(delay, remaining));
    if (Thread.interrupted()) throw new SQLException("TPC-C terminal interrupted", "57014");
  }

  private static int keyingSeconds(TpccTransactionType type) {
    return type == TpccTransactionType.NEW_ORDER ? 18
        : type == TpccTransactionType.PAYMENT ? 3 : 2;
  }

  private static int thinkSeconds(TpccTransactionType type) {
    return switch (type) {
      case NEW_ORDER, PAYMENT -> 12;
      case ORDER_STATUS -> 10;
      case DELIVERY, STOCK_LEVEL -> 5;
    };
  }

  private static long allocatedBytes() {
    java.lang.management.ThreadMXBean base = ManagementFactory.getThreadMXBean();
    if (base instanceof com.sun.management.ThreadMXBean extended
        && extended.isThreadAllocatedMemorySupported()
        && extended.isThreadAllocatedMemoryEnabled()) {
      return extended.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }
    return -1;
  }

  @Override
  public void close() throws SQLException {
    session.close();
  }
}
