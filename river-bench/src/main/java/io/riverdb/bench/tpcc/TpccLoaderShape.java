package io.riverdb.bench.tpcc;

/** Loader SQL marker admission catches drift before the first database mutation. */
final class TpccLoaderShape {
  private TpccLoaderShape() {}

  static void validate(String table, String sql) {
    int expected = switch (table) {
      case "warehouse" -> 9;
      case "district" -> 11;
      case "item" -> 5;
      case "stock" -> 17;
      case "customer" -> 21;
      case "history" -> 8;
      case "orders" -> 8;
      case "new_order" -> 3;
      case "order_line" -> 10;
      default -> throw new IllegalArgumentException("unknown TPC-C load table " + table);
    };
    int markers = 0;
    for (int index = 0; index < sql.length(); index++) {
      if (sql.charAt(index) == '?') markers++;
    }
    if (markers != expected) {
      throw new IllegalArgumentException(table + " loader expected " + expected
          + " parameters, found " + markers);
    }
  }
}
