package io.riverdb.bench.tpcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class TpccWorkloadMixTest {
  @Test
  void diagnosticMixesContainNoForcedFamilies() {
    assertSelection(TpccWorkloadMix.STANDARD, 45, 43, 4, 4, 4);
    assertSelection(TpccWorkloadMix.NEW_ORDER, 100, 0, 0, 0, 0);
    assertSelection(TpccWorkloadMix.PAYMENT, 0, 100, 0, 0, 0);
    assertSelection(TpccWorkloadMix.NEW_ORDER_PAYMENT_50_50, 50, 50, 0, 0, 0);
    assertSelection(TpccWorkloadMix.NEW_ORDER_DELIVERY_50_50, 50, 0, 0, 50, 0);
    assertSelection(TpccWorkloadMix.NEW_ORDER_STOCK_LEVEL_50_50, 50, 0, 0, 0, 50);

    assertFalse(TpccWorkloadMix.NEW_ORDER_PAYMENT_50_50.includes(
        TpccTransactionType.DELIVERY));
    assertTrue(TpccWorkloadMix.NEW_ORDER_DELIVERY_50_50.includes(
        TpccTransactionType.DELIVERY));
    assertFalse(TpccWorkloadMix.NEW_ORDER_DELIVERY_50_50.includes(
        TpccTransactionType.STOCK_LEVEL));
    assertTrue(TpccWorkloadMix.NEW_ORDER_STOCK_LEVEL_50_50.includes(
        TpccTransactionType.STOCK_LEVEL));
    assertFalse(TpccWorkloadMix.NEW_ORDER_STOCK_LEVEL_50_50.includes(
        TpccTransactionType.DELIVERY));
  }

  @Test
  void parsesEverySupportedDiagnosticMix() {
    assertEquals(
        TpccWorkloadMix.NEW_ORDER_PAYMENT_50_50,
        TpccWorkloadMix.parse("new-order-payment-50-50"));
    assertEquals(
        TpccWorkloadMix.NEW_ORDER_DELIVERY_50_50,
        TpccWorkloadMix.parse("new-order-delivery-50-50"));
    assertEquals(
        TpccWorkloadMix.NEW_ORDER_STOCK_LEVEL_50_50,
        TpccWorkloadMix.parse("new-order-stock-level-50-50"));
  }

  @Test
  void isolationContractMatchesProgramAndJdbcExceptForLabelledReproducer() {
    for (TpccTransactionType type : TpccTransactionType.values()) {
      assertEquals(
          java.sql.Connection.TRANSACTION_SERIALIZABLE,
          TpccIsolationContract.SERIALIZABLE.jdbcLevel(type));
    }
    assertTrue(TpccIsolationContract.SERIALIZABLE.common());
    assertFalse(TpccIsolationContract.MIXED_DIAGNOSTIC.common());
    assertEquals(
        java.sql.Connection.TRANSACTION_SERIALIZABLE,
        TpccIsolationContract.MIXED_DIAGNOSTIC.jdbcLevel(TpccTransactionType.NEW_ORDER));
    assertEquals(
        java.sql.Connection.TRANSACTION_REPEATABLE_READ,
        TpccIsolationContract.MIXED_DIAGNOSTIC.jdbcLevel(TpccTransactionType.PAYMENT));
  }

  private static void assertSelection(
      TpccWorkloadMix mix,
      int newOrder,
      int payment,
      int orderStatus,
      int delivery,
      int stockLevel) {
    Map<TpccTransactionType, Integer> counts = new EnumMap<>(TpccTransactionType.class);
    for (TpccTransactionType type : TpccTransactionType.values()) counts.put(type, 0);
    for (int percentile = 1; percentile <= 100; percentile++) {
      counts.compute(mix.choose(percentile), (type, count) -> count + 1);
    }
    assertEquals(newOrder, counts.get(TpccTransactionType.NEW_ORDER));
    assertEquals(payment, counts.get(TpccTransactionType.PAYMENT));
    assertEquals(orderStatus, counts.get(TpccTransactionType.ORDER_STATUS));
    assertEquals(delivery, counts.get(TpccTransactionType.DELIVERY));
    assertEquals(stockLevel, counts.get(TpccTransactionType.STOCK_LEVEL));
  }
}
