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
    TpccWorkloadMix mix,
    TpccIsolationContract isolation,
    Duration retryBase,
    Duration retryMaximum,
    Path artifact,
    Path jfr,
    Path metricsStartFile,
    Path metricsStartedFile,
    Path metricsStopFile,
    Path metricsStoppedFile,
    TpccEvidenceMode evidence) {
  public TpccConfig {
    validateValues(url, warehouses, districts, customersPerDistrict, itemCount,
        ordersPerDistrict, firstUndeliveredOrder, terminals, warmup, measured,
        batchRows, maximumAttempts, retryBase, retryMaximum, artifact, phase,
        scheduling, mix, isolation, evidence);
    validateMetricsFiles(metricsStartFile, metricsStartedFile, metricsStopFile, metricsStoppedFile);
    validateStandardScale(itemCount, terminals, warehouses, districts);
    validateEvidence(evidence, mix, isolation);
  }

  private static void validateValues(
      String url, int warehouses, int districts, int customersPerDistrict, int itemCount,
      int ordersPerDistrict, int firstUndeliveredOrder, int terminals, Duration warmup,
      Duration measured, int batchRows, int maximumAttempts, Duration retryBase,
      Duration retryMaximum, Path artifact, TpccPhase phase, TpccScheduling scheduling,
      TpccWorkloadMix mix, TpccIsolationContract isolation, TpccEvidenceMode evidence) {
    if (url == null || !url.startsWith("jdbc:river:client-file:") || warehouses < 1
        || districts < 1 || customersPerDistrict < 1 || itemCount < 1
        || ordersPerDistrict < 1 || firstUndeliveredOrder < 1
        || (long) firstUndeliveredOrder > (long) ordersPerDistrict + 1
        || terminals < 1 || batchRows < 1 || maximumAttempts < 1
        || warmup.isNegative() || warmup.isZero() || measured.isNegative() || measured.isZero()
        || retryBase.isNegative() || retryBase.isZero() || retryMaximum.compareTo(retryBase) < 0
        || artifact == null || phase == null || scheduling == null || mix == null
        || isolation == null || evidence == null) {
      throw new IllegalArgumentException("invalid TPC-C acceptance configuration");
    }
  }

  private static void validateMetricsFiles(Path start, Path started, Path stop, Path stopped) {
    int files = (start == null ? 0 : 1) + (started == null ? 0 : 1)
        + (stop == null ? 0 : 1) + (stopped == null ? 0 : 1);
    if (files != 0 && files != 4) {
      throw new IllegalArgumentException("performance capture requires all four control files");
    }
  }

  private static void validateStandardScale(int itemCount, int terminals, int warehouses, int districts) {
    if (itemCount == 100_000 && terminals != (long) warehouses * districts) {
      throw new IllegalArgumentException(
          "standard acceptance requires one terminal per warehouse district");
    }
  }

  private static void validateEvidence(
      TpccEvidenceMode evidence, TpccWorkloadMix mix, TpccIsolationContract isolation) {
    if (evidence == TpccEvidenceMode.ALPHA3
        && (mix != TpccWorkloadMix.STANDARD || !isolation.common())) {
      throw new IllegalArgumentException(
          "Alpha3 evidence requires the standard mix and one common isolation contract");
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
