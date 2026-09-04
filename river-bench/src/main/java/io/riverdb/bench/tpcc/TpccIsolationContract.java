package io.riverdb.bench.tpcc;

import java.sql.Connection;
import java.util.Locale;

/** Declared benchmark isolation, including one explicitly non-promotable reproducer. */
enum TpccIsolationContract {
  REPEATABLE_READ,
  SERIALIZABLE,
  MIXED_DIAGNOSTIC;

  static TpccIsolationContract parse(String value) {
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "repeatable-read" -> REPEATABLE_READ;
      case "serializable" -> SERIALIZABLE;
      case "mixed-diagnostic" -> MIXED_DIAGNOSTIC;
      default -> throw new IllegalArgumentException("unknown isolation contract: " + value);
    };
  }

  int jdbcLevel(TpccTransactionType type) {
    return switch (this) {
      case REPEATABLE_READ -> Connection.TRANSACTION_REPEATABLE_READ;
      case SERIALIZABLE -> Connection.TRANSACTION_SERIALIZABLE;
      case MIXED_DIAGNOSTIC -> type == TpccTransactionType.NEW_ORDER
          || type == TpccTransactionType.STOCK_LEVEL
              ? Connection.TRANSACTION_SERIALIZABLE
              : Connection.TRANSACTION_REPEATABLE_READ;
    };
  }

  boolean common() {
    return this != MIXED_DIAGNOSTIC;
  }

  String jdbcLabel() {
    return this == SERIALIZABLE ? "SERIALIZABLE" : "REPEATABLE_READ";
  }

  String programLabel() {
    return this == REPEATABLE_READ ? "REPEATABLE_READ" : "SERIALIZABLE";
  }
}
