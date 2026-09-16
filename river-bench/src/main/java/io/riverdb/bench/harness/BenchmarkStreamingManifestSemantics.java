package io.riverdb.bench.harness;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates cross-field identity shared by streaming manifest workloads. */
final class BenchmarkStreamingManifestSemantics {
  private BenchmarkStreamingManifestSemantics() {}

  static void validate(
      JsonNode manifest,
      List<String> errors) {
    Set<String> names = new HashSet<>();
    Map<String, Long> familySeeds = new HashMap<>();
    Map<String, String> familyConfigs = new HashMap<>();
    for (JsonNode workload : manifest.path("workloads")) {
      validateWorkload(workload, names, familySeeds, familyConfigs, errors);
    }
  }

  private static void validateWorkload(
      JsonNode workload, Set<String> names, Map<String, Long> familySeeds,
      Map<String, String> familyConfigs, List<String> errors) {
    String name = workload.path("name").textValue();
    String schemaId = workload.path("schema_id").textValue();
    String config = workload.path("config").textValue();
    if (name == null || !names.add(name)) {
      errors.add("$.workloads: duplicate or absent workload name " + name);
      return;
    }
    int separator = name.indexOf('_');
    if (separator < 1) {
      errors.add("$.workloads: workload name has no family " + name);
      return;
    }
    String family = name.substring(0, separator);
    String table = name.substring(separator + 1);
    validateSchema(name, family, table, schemaId, errors);
    if (config == null || !config.startsWith("schema=" + family + "_v2;")) {
      errors.add("$.workloads: config family does not match workload name " + name);
      return;
    }
    int tableOffset = config.indexOf(";table=");
    if (tableOffset < 0) {
      errors.add("$.workloads: config has no table identity " + name);
      return;
    }
    validateTable(name, table, config, tableOffset, errors);
    validateFamily(family, config.substring(0, tableOffset), workload, familySeeds,
        familyConfigs, errors);
  }

  private static void validateSchema(
      String name, String family, String table, String schemaId, List<String> errors) {
    if (!(family + '.' + table + ".v2").equals(schemaId)) {
      errors.add("$.workloads: schema_id does not match workload name " + name);
    }
  }

  private static void validateTable(
      String name, String table, String config, int tableOffset, List<String> errors) {
    int tableStart = tableOffset + ";table=".length();
    int tableEnd = config.indexOf(';', tableStart);
    String configuredTable = tableEnd < 0
        ? config.substring(tableStart)
        : config.substring(tableStart, tableEnd);
    if (!table.equals(configuredTable)) {
      errors.add("$.workloads: config table does not match workload name " + name);
    }
  }

  private static void validateFamily(
      String family, String commonConfig, JsonNode workload,
      Map<String, Long> familySeeds, Map<String, String> familyConfigs,
      List<String> errors) {
    long seed = workload.path("seed").asLong();
    Long priorSeed = familySeeds.putIfAbsent(family, seed);
    if (priorSeed != null && priorSeed != seed) {
      errors.add("$.workloads: inconsistent seed for family " + family);
    }
    String priorConfig = familyConfigs.putIfAbsent(family, commonConfig);
    if (priorConfig != null && !priorConfig.equals(commonConfig)) {
      errors.add("$.workloads: inconsistent common config for family " + family);
    }
  }
}
