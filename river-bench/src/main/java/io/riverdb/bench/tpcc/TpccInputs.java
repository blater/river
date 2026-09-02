package io.riverdb.bench.tpcc;

import java.math.BigDecimal;
import java.sql.Timestamp;

/** Terminal-owned mutable carriers; values stay fixed across transaction retries. */
final class TpccInputs {
  static final class NewOrder {
    final int[] item = new int[15];
    final int[] quantity = new int[15];
    final int[] supplyWarehouse = new int[15];
    int warehouse;
    int district;
    int customer;
    int lines;
    Timestamp entry;

    void generate(
        TpccValues values, TpccConfig config, int homeWarehouse, int homeDistrict) {
      warehouse = homeWarehouse;
      district = homeDistrict;
      customer = values.nurand(1_023, 1, config.customersPerDistrict(),
          TpccNurandConstants.STANDARD.customerId());
      lines = values.number(5, 15);
      entry = new Timestamp(System.currentTimeMillis());
      boolean invalid = values.number(1, 100) == 1;
      for (int line = 0; line < lines; line++) {
        int candidate;
        do {
          candidate = values.nurand(8_191, 1, config.itemCount(),
              TpccNurandConstants.STANDARD.itemId());
        } while (contains(item, line, candidate));
        item[line] = invalid && line == lines - 1 ? config.itemCount() + 1 : candidate;
        quantity[line] = values.number(1, 10);
        supplyWarehouse[line] = remoteSupply(values, config.warehouses(), homeWarehouse);
      }
    }

    private static int remoteSupply(TpccValues values, int warehouses, int home) {
      if (warehouses == 1 || values.number(1, 100) != 1) return home;
      int remote = values.number(1, warehouses - 1);
      return remote >= home ? remote + 1 : remote;
    }
  }

  static final class Payment {
    int warehouse;
    int district;
    int customerWarehouse;
    int customerDistrict;
    int customer;
    String last;
    BigDecimal amount;
    Timestamp date;

    void generate(
        TpccValues values, TpccConfig config, int homeWarehouse, int homeDistrict) {
      warehouse = homeWarehouse;
      district = homeDistrict;
      customerWarehouse = homeWarehouse;
      customerDistrict = homeDistrict;
      if (config.warehouses() > 1 && values.number(1, 100) <= 15) {
        int remote = values.number(1, config.warehouses() - 1);
        customerWarehouse = remote >= homeWarehouse ? remote + 1 : remote;
        customerDistrict = values.number(1, config.districts());
      }
      customer = values.number(1, 100) <= 60
          ? 0 : values.nurand(1_023, 1, config.customersPerDistrict(),
              TpccNurandConstants.STANDARD.customerId());
      int maximumLast = Math.min(999, config.customersPerDistrict() - 1);
      last = customer == 0 ? values.lastName(values.nurand(255, 0, maximumLast,
          TpccNurandConstants.STANDARD.runLast())) : null;
      amount = values.money(values.number(100, 500_000));
      date = new Timestamp(System.currentTimeMillis());
    }
  }

  static final class CustomerOrder {
    int warehouse;
    int district;
    int customer;
    String last;

    void generate(
        TpccValues values, TpccConfig config, int homeWarehouse, int homeDistrict) {
      warehouse = homeWarehouse;
      district = homeDistrict;
      customer = values.number(1, 100) <= 60
          ? 0 : values.nurand(1_023, 1, config.customersPerDistrict(),
              TpccNurandConstants.STANDARD.customerId());
      int maximumLast = Math.min(999, config.customersPerDistrict() - 1);
      last = customer == 0 ? values.lastName(values.nurand(255, 0, maximumLast,
          TpccNurandConstants.STANDARD.runLast())) : null;
    }
  }

  static final class Delivery {
    int warehouse;
    int carrier;
    Timestamp date;

    void generate(TpccValues values, int homeWarehouse) {
      warehouse = homeWarehouse;
      carrier = values.number(1, 10);
      date = new Timestamp(System.currentTimeMillis());
    }
  }

  static final class StockLevel {
    int warehouse;
    int district;
    int threshold;

    void generate(TpccValues values, int homeWarehouse, int homeDistrict) {
      warehouse = homeWarehouse;
      district = homeDistrict;
      threshold = values.number(10, 20);
    }
  }

  private TpccInputs() {}

  private static boolean contains(int[] values, int length, int candidate) {
    for (int index = 0; index < length; index++) {
      if (values[index] == candidate) return true;
    }
    return false;
  }
}
