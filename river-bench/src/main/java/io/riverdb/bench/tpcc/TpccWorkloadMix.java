package io.riverdb.bench.tpcc;

import java.util.Locale;

/** Exact transaction-family selection for standard and diagnostic runs. */
enum TpccWorkloadMix {
  STANDARD,
  NEW_ORDER,
  PAYMENT,
  NEW_ORDER_PAYMENT_50_50,
  NEW_ORDER_DELIVERY_50_50,
  NEW_ORDER_STOCK_LEVEL_50_50;

  static TpccWorkloadMix parse(String value) {
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "standard" -> STANDARD;
      case "new-order" -> NEW_ORDER;
      case "payment" -> PAYMENT;
      case "new-order-payment-50-50" -> NEW_ORDER_PAYMENT_50_50;
      case "new-order-delivery-50-50" -> NEW_ORDER_DELIVERY_50_50;
      case "new-order-stock-level-50-50" -> NEW_ORDER_STOCK_LEVEL_50_50;
      default -> throw new IllegalArgumentException("unknown workload mix: " + value);
    };
  }

  TpccTransactionType choose(int percentile) {
    if (percentile < 1 || percentile > 100) {
      throw new IllegalArgumentException("transaction percentile outside bound");
    }
    return switch (this) {
      case NEW_ORDER -> TpccTransactionType.NEW_ORDER;
      case PAYMENT -> TpccTransactionType.PAYMENT;
      case NEW_ORDER_PAYMENT_50_50 -> percentile <= 50
          ? TpccTransactionType.NEW_ORDER : TpccTransactionType.PAYMENT;
      case NEW_ORDER_DELIVERY_50_50 -> percentile <= 50
          ? TpccTransactionType.NEW_ORDER : TpccTransactionType.DELIVERY;
      case NEW_ORDER_STOCK_LEVEL_50_50 -> percentile <= 50
          ? TpccTransactionType.NEW_ORDER : TpccTransactionType.STOCK_LEVEL;
      case STANDARD -> percentile <= 45 ? TpccTransactionType.NEW_ORDER
          : percentile <= 88 ? TpccTransactionType.PAYMENT
          : percentile <= 92 ? TpccTransactionType.ORDER_STATUS
          : percentile <= 96 ? TpccTransactionType.DELIVERY
          : TpccTransactionType.STOCK_LEVEL;
    };
  }

  boolean includes(TpccTransactionType type) {
    return switch (this) {
      case STANDARD -> true;
      case NEW_ORDER -> type == TpccTransactionType.NEW_ORDER;
      case PAYMENT -> type == TpccTransactionType.PAYMENT;
      case NEW_ORDER_PAYMENT_50_50 ->
          type == TpccTransactionType.NEW_ORDER || type == TpccTransactionType.PAYMENT;
      case NEW_ORDER_DELIVERY_50_50 ->
          type == TpccTransactionType.NEW_ORDER || type == TpccTransactionType.DELIVERY;
      case NEW_ORDER_STOCK_LEVEL_50_50 ->
          type == TpccTransactionType.NEW_ORDER || type == TpccTransactionType.STOCK_LEVEL;
    };
  }
}
