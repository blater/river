package io.riverdb.bench.tpcc;

import java.nio.file.Path;
import java.time.Duration;

/** Validated configuration for one River engineering TPC-C acceptance run. */
public record TpccConfig(
    String url,
    int warehouses,
    int districts,
    int customersPerDistrict,
    int itemCount,
    int ordersPerDistrict,
    int firstUndeliveredOrder,
    int terminals,
    Duration warmup,
    Duration measured,
    int batchRows,
    int maximumAttempts,
    long seed,
    boolean freshLoad,
    TpccPhase phase,
    TpccScheduling scheduling,
    Duration retryBase,
    Duration retryMaximum,
    Path artifact,
    Path jfr) {
  public TpccConfig {
    if (url == null || !url.startsWith("jdbc:river:") || warehouses < 1 || warehouses > 100
        || districts < 1 || districts > 10
        || customersPerDistrict < 1 || itemCount < 1 || ordersPerDistrict < 1
        || firstUndeliveredOrder < 1 || firstUndeliveredOrder > ordersPerDistrict + 1
        || terminals < 1 || terminals > 1_024
        || batchRows < 1 || batchRows > 32 || maximumAttempts < 1
        || warmup.isNegative() || warmup.isZero() || measured.isNegative() || measured.isZero()
        || retryBase.isNegative() || retryBase.isZero() || retryMaximum.compareTo(retryBase) < 0
        || retryMaximum.compareTo(Duration.ofSeconds(10)) > 0
        || artifact == null || phase == null || scheduling == null) {
      throw new IllegalArgumentException("invalid TPC-C acceptance configuration");
    }
    if (itemCount == 100_000 && terminals != warehouses * districts) {
      throw new IllegalArgumentException(
          "standard acceptance requires one terminal per warehouse district");
    }
  }

  public static TpccConfig parse(String[] arguments) {
    return TpccArguments.parse(arguments);
  }

  public boolean standardOneWarehouse() {
    return warehouses == 1 && standardScale();
  }

  public boolean standardScale() {
    return districts == 10 && customersPerDistrict == 3_000 && itemCount == 100_000
        && ordersPerDistrict == 3_000 && firstUndeliveredOrder == 2_101;
  }
}
