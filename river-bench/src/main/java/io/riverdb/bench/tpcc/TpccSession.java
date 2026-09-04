package io.riverdb.bench.tpcc;

import io.riverdb.jdbc.RiverConnectionMetrics;
import io.riverdb.jdbc.RiverTransactionDiagnostics;
import java.sql.Connection;
import java.sql.SQLException;

/** One terminal's JDBC connection and reusable transaction statement sets. */
final class TpccSession implements AutoCloseable {
  final TpccRiverNewOrder newOrder;
  final TpccPayment payment;
  final TpccOrderStatus orderStatus;
  final TpccDelivery delivery;
  final TpccRiverStockLevel stockLevel;
  private final TpccConfig configuration;
  private final Connection connection;
  private final RiverConnectionMetrics metrics;
  private final RiverTransactionDiagnostics diagnostics;

  TpccSession(Connection owner, TpccConfig config, int homeDistrict) throws SQLException {
    configuration = config;
    connection = owner;
    TpccRiverNewOrder openedNewOrder = null;
    TpccPayment openedPayment = null;
    TpccOrderStatus openedOrderStatus = null;
    TpccDelivery openedDelivery = null;
    TpccRiverStockLevel openedStockLevel = null;
    RiverConnectionMetrics openedMetrics;
    RiverTransactionDiagnostics openedDiagnostics;
    try {
      openedMetrics = owner.unwrap(RiverConnectionMetrics.class);
      openedDiagnostics = owner.unwrap(RiverTransactionDiagnostics.class);
      owner.setAutoCommit(false);
      openedNewOrder = new TpccRiverNewOrder(owner, homeDistrict, config.itemCount());
      openedPayment = new TpccPayment(owner, openedDiagnostics);
      openedOrderStatus = new TpccOrderStatus(owner, openedDiagnostics);
      openedDelivery = new TpccDelivery(owner, openedDiagnostics, config.districts());
      openedStockLevel = new TpccRiverStockLevel(owner);
    } catch (SQLException failure) {
      failure = rollback(failure);
      failure = close(openedStockLevel, failure);
      failure = close(openedDelivery, failure);
      failure = close(openedOrderStatus, failure);
      failure = close(openedPayment, failure);
      failure = close(openedNewOrder, failure);
      failure = close(owner, failure);
      throw failure;
    }
    metrics = openedMetrics;
    diagnostics = openedDiagnostics;
    newOrder = openedNewOrder;
    payment = openedPayment;
    orderStatus = openedOrderStatus;
    delivery = openedDelivery;
    stockLevel = openedStockLevel;
  }

  long completedRequests() { return metrics.completedRequests(); }

  long bytesSent() { return metrics.bytesSent(); }

  long bytesReceived() { return metrics.bytesReceived(); }

  void beginDiagnosticAttempt(long diagnosticTag, long metricsEpoch) throws SQLException {
    diagnostics.beginDiagnosticAttempt(diagnosticTag, metricsEpoch);
  }

  void diagnosticStep(long diagnosticStepTag) throws SQLException {
    diagnostics.diagnosticStep(diagnosticStepTag);
  }

  long diagnosticStepTag() throws SQLException { return diagnostics.diagnosticStepTag(); }

  void prepareAttempt(TpccTransactionType type) throws SQLException {
    int isolation = configIsolation(type);
    connection.setTransactionIsolation(isolation);
    if (connection.getTransactionIsolation() != isolation) {
      throw new SQLException("effective JDBC isolation differs from benchmark contract");
    }
  }

  private int configIsolation(TpccTransactionType type) {
    return configuration.isolation().jdbcLevel(type);
  }

  @Override
  public void close() throws SQLException {
    SQLException failure = rollback(null);
    failure = close(stockLevel, failure);
    failure = close(delivery, failure);
    failure = close(orderStatus, failure);
    failure = close(payment, failure);
    failure = close(newOrder, failure);
    failure = close(connection, failure);
    if (failure != null) throw failure;
  }

  private SQLException rollback(SQLException failure) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      failure = append(failure, rollbackFailure);
    }
    return failure;
  }

  private static SQLException close(AutoCloseable owner, SQLException failure) {
    if (owner == null) return failure;
    try {
      owner.close();
    } catch (SQLException closeFailure) {
      failure = append(failure, closeFailure);
    } catch (Exception closeFailure) {
      failure = append(failure, new SQLException("session owner close failed", closeFailure));
    }
    return failure;
  }

  private static SQLException append(SQLException primary, SQLException added) {
    if (primary == null) return added;
    primary.addSuppressed(added);
    return primary;
  }
}
