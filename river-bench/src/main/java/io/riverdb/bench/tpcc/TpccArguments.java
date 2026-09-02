package io.riverdb.bench.tpcc;

import java.nio.file.Path;
import java.time.Duration;

/** Strict command-line decoder kept separate from configuration invariants. */
final class TpccArguments {
  private String url;
  private boolean tiny;
  private boolean load = true;
  private TpccPhase phase = TpccPhase.LOAD_RUN_CHECKPOINT;
  private TpccScheduling scheduling = TpccScheduling.STANDARD;
  private Path artifact = Path.of("river-tpcc-acceptance.properties");
  private Path jfr;
  private long warmup = 300;
  private long measured = 1_800;
  private long seed = 0x5450_4343_0001L;
  private int warehouses = 1;
  private int terminals;
  private boolean terminalsExplicit;
  private int batch = 32;
  private int attempts = 4;
  private boolean attemptsExplicit;
  private long retryBaseMicros = 250;
  private long retryMaximumMillis = 100;
  private TpccEvidenceMode evidence = TpccEvidenceMode.DIAGNOSTIC;

  private TpccArguments() {}

  static TpccConfig parse(String[] arguments) {
    TpccArguments decoded = new TpccArguments();
    for (String argument : arguments) decoded.accept(argument);
    return decoded.configuration();
  }

  private void accept(String argument) {
    int split = argument.indexOf('=');
    String key = split < 0 ? argument : argument.substring(0, split);
    String value = split < 0 ? "true" : argument.substring(split + 1);
    switch (key) {
      case "--url" -> url = value;
      case "--phase" -> phase = TpccPhase.parse(value);
      case "--scheduling" -> scheduling = TpccScheduling.parse(value);
      case "--artifact" -> artifact = Path.of(value);
      case "--jfr" -> jfr = Path.of(value);
      case "--evidence" -> evidence = TpccEvidenceMode.parse(value);
      case "--tiny" -> tiny = Boolean.parseBoolean(value);
      case "--fresh-load" -> load = Boolean.parseBoolean(value);
      case "--warmup-seconds" -> warmup = Long.parseLong(value);
      case "--measured-seconds" -> measured = Long.parseLong(value);
      case "--warehouses" -> warehouses = Integer.parseInt(value);
      case "--terminals" -> {
        terminals = Integer.parseInt(value);
        terminalsExplicit = true;
      }
      case "--batch-rows" -> batch = Integer.parseInt(value);
      case "--maximum-attempts" -> {
        attempts = Integer.parseInt(value);
        attemptsExplicit = true;
      }
      case "--retry-base-micros" -> retryBaseMicros = Long.parseLong(value);
      case "--retry-maximum-millis" -> retryMaximumMillis = Long.parseLong(value);
      case "--seed" -> seed = Long.parseLong(value);
      default -> throw new IllegalArgumentException("unknown argument: " + key);
    }
  }

  private TpccConfig configuration() {
    if (url == null) throw new IllegalArgumentException("--url=jdbc:river://localhost:PORT required");
    int customers = tiny ? 30 : 3_000;
    int items = tiny ? 100 : 100_000;
    int orders = tiny ? 30 : 3_000;
    int configuredTerminals = terminalsExplicit ? terminals : Math.multiplyExact(warehouses, 10);
    int configuredAttempts = tiny && scheduling == TpccScheduling.NO_WAIT_STRESS
        && !attemptsExplicit ? 32 : attempts;
    return new TpccConfig(url, warehouses, 10, customers, items, orders,
        tiny ? 22 : 2_101, configuredTerminals,
        Duration.ofSeconds(warmup), Duration.ofSeconds(measured), batch, configuredAttempts,
        seed, load,
        phase, scheduling, Duration.ofNanos(Math.multiplyExact(retryBaseMicros, 1_000L)),
        Duration.ofMillis(retryMaximumMillis), artifact, jfr, evidence);
  }
}
