package io.riverdb.bench.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates the deliberately small JSON Schema subset used by benchmark artifacts. */
public final class BenchmarkSchemaValidator {
  public static final String MANIFEST = "manifest-schema-v1.json";
  public static final String RESULT = "result-schema-v1.json";
  public static final String STREAMING_MANIFEST = "manifest-schema-v2.json";
  public static final String STREAMING_RESULT = "result-schema-v2.json";
  public static final String SAMPLE = "sample-schema-v1.json";
  public static final String STREAMING_SAMPLE = "sample-schema-v2.json";

  private static final String RESOURCE_ROOT = "/io/riverdb/bench/harness/schema/";
  private static final ObjectMapper MAPPER = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .build();

  private final Map<String, JsonNode> schemas;

  public BenchmarkSchemaValidator() {
    schemas = new HashMap<>();
    schemas.put(MANIFEST, load(MANIFEST));
    schemas.put(RESULT, load(RESULT));
    schemas.put(STREAMING_MANIFEST, load(STREAMING_MANIFEST));
    schemas.put(STREAMING_RESULT, load(STREAMING_RESULT));
    schemas.put(SAMPLE, load(SAMPLE));
    schemas.put(STREAMING_SAMPLE, load(STREAMING_SAMPLE));
  }

  public SchemaValidation validate(String schemaName, String json) {
    JsonNode schema = schemas.get(schemaName);
    if (schema == null) {
      return invalid("$: unknown schema " + schemaName);
    }
    final JsonNode document;
    try (JsonParser parser = MAPPER.createParser(json)) {
      document = MAPPER.readTree(parser);
      if (document == null || parser.nextToken() != null) {
        return invalid("$: trailing JSON token");
      }
    } catch (JsonProcessingException exception) {
      return invalid("$: invalid JSON");
    } catch (IOException exception) {
      return invalid("$: invalid JSON");
    }
    List<String> errors = new ArrayList<>();
    validateNode(schema, document, "$", errors);
    if ((SAMPLE.equals(schemaName) || STREAMING_SAMPLE.equals(schemaName))
        && document.isObject()) {
      validateSampleSemantics(document, errors);
    } else if (STREAMING_MANIFEST.equals(schemaName) && document.isObject()) {
      validateStreamingManifestSemantics(document, errors);
    } else if ((RESULT.equals(schemaName) || STREAMING_RESULT.equals(schemaName))
        && document.isObject()) {
      validateResultSemantics(document, errors);
    }
    return new SchemaValidation(errors.isEmpty(), errors);
  }

  private static JsonNode load(String name) {
    try (InputStream stream = BenchmarkSchemaValidator.class.getResourceAsStream(
        RESOURCE_ROOT + name)) {
      if (stream == null) {
        throw new IllegalStateException("missing benchmark schema " + name);
      }
      return MAPPER.readTree(stream);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read benchmark schema " + name, exception);
    }
  }

  private static SchemaValidation invalid(String error) {
    return new SchemaValidation(false, List.of(error));
  }

  private static void validateNode(
      JsonNode schema,
      JsonNode value,
      String path,
      List<String> errors) {
    String type = text(schema, "type");
    if (type != null && !hasType(value, type)) {
      errors.add(path + ": expected " + type);
      return;
    }
    JsonNode constant = schema.get("const");
    if (constant != null && !constant.equals(value)) {
      errors.add(path + ": value does not match const");
    }
    JsonNode values = schema.get("enum");
    if (values != null && !contains(values, value)) {
      errors.add(path + ": value is not in enum");
    }
    if (value.isObject()) {
      validateObject(schema, value, path, errors);
    } else if (value.isArray()) {
      validateArray(schema, value, path, errors);
    } else if (value.isTextual()) {
      validateText(schema, value.textValue(), path, errors);
    } else if (value.isNumber()) {
      validateNumber(schema, value, path, errors);
    }
  }

  private static void validateObject(
      JsonNode schema,
      JsonNode value,
      String path,
      List<String> errors) {
    JsonNode required = schema.get("required");
    if (required != null) {
      required.forEach(name -> {
        if (!value.has(name.textValue())) {
          errors.add(path + ": missing " + name.textValue());
        }
      });
    }
    JsonNode properties = schema.get("properties");
    if (properties == null) {
      return;
    }
    if (schema.path("additionalProperties").isBoolean()
        && !schema.path("additionalProperties").booleanValue()) {
      Set<String> known = new HashSet<>();
      properties.fieldNames().forEachRemaining(known::add);
      value.fieldNames().forEachRemaining(name -> {
        if (!known.contains(name)) {
          errors.add(path + ": unexpected property " + name);
        }
      });
    }
    Iterator<Map.Entry<String, JsonNode>> fields = properties.properties().iterator();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      JsonNode child = value.get(field.getKey());
      if (child != null) {
        validateNode(field.getValue(), child, path + "." + field.getKey(), errors);
      }
    }
  }

  private static void validateArray(
      JsonNode schema,
      JsonNode value,
      String path,
      List<String> errors) {
    int minimum = schema.path("minItems").asInt(0);
    if (value.size() < minimum) {
      errors.add(path + ": fewer than " + minimum + " items");
    }
    JsonNode itemSchema = schema.get("items");
    if (itemSchema != null) {
      for (int index = 0; index < value.size(); index++) {
        validateNode(itemSchema, value.get(index), path + "[" + index + "]", errors);
      }
    }
  }

  private static void validateText(
      JsonNode schema,
      String value,
      String path,
      List<String> errors) {
    int minimum = schema.path("minLength").asInt(0);
    if (value.length() < minimum) {
      errors.add(path + ": shorter than " + minimum);
    }
    String expression = text(schema, "pattern");
    if (expression != null && !Pattern.matches(expression, value)) {
      errors.add(path + ": does not match pattern");
    }
  }

  private static void validateNumber(
      JsonNode schema,
      JsonNode value,
      String path,
      List<String> errors) {
    JsonNode minimum = schema.get("minimum");
    if (minimum != null && value.decimalValue().compareTo(minimum.decimalValue()) < 0) {
      errors.add(path + ": below minimum");
    }
    JsonNode maximum = schema.get("maximum");
    if (maximum != null && value.decimalValue().compareTo(maximum.decimalValue()) > 0) {
      errors.add(path + ": above maximum");
    }
  }

  private static void validateSampleSemantics(JsonNode sample, List<String> errors) {
    String mode = sample.path("mode").textValue();
    String metric = sample.path("metric").textValue();
    long operations = sample.path("operation_count").asLong(-1);
    long interval = sample.path("expected_interval_ns").asLong(-1);
    long histogramCount = sample.path("histogram_count").asLong(-1);
    if ("closed_loop".equals(mode)) {
      if (!"service".equals(metric)) {
        errors.add("$.metric: closed_loop only permits service");
      }
      if (interval != 0) {
        errors.add("$.expected_interval_ns: closed_loop requires zero");
      }
    } else if ("open_loop".equals(mode) && interval < 1) {
      errors.add("$.expected_interval_ns: open_loop requires a positive interval");
    }
    if ("coordinated_omission_corrected_service".equals(metric)) {
      if (histogramCount < operations) {
        errors.add("$.histogram_count: corrected count cannot be below operation count");
      }
    } else if (histogramCount != operations) {
      errors.add("$.histogram_count: service/scheduled count must equal operation count");
    }
    long minimum = sample.path("minimum_ns").asLong(-1);
    long p50 = sample.path("p50_ns").asLong(-1);
    long p95 = sample.path("p95_ns").asLong(-1);
    long p99 = sample.path("p99_ns").asLong(-1);
    long p999 = sample.path("p999_ns").asLong(-1);
    long maximum = sample.path("maximum_ns").asLong(-1);
    if (!(minimum <= p50 && p50 <= p95 && p95 <= p99
        && p99 <= p999 && p999 <= maximum)) {
      errors.add("$: latency quantiles must be monotonic from minimum through maximum");
    }
    double mean = sample.path("mean_ns").asDouble(Double.NaN);
    if (!Double.isFinite(mean) || mean < minimum || mean > maximum) {
      errors.add("$.mean_ns: mean must be finite and within minimum/maximum");
    }
  }

  private static void validateResultSemantics(JsonNode result, List<String> errors) {
    Set<String> paths = new HashSet<>();
    Set<String> names = new HashSet<>();
    for (JsonNode reference : result.path("workload_artifacts")) {
      String name = reference.path("name").textValue();
      String path = reference.path("path").textValue();
      if (name != null && !names.add(name)) {
        errors.add("$.workload_artifacts: duplicate workload name " + name);
      }
      if (name != null && path != null && !path.startsWith(name + "-v")) {
        errors.add("$.workload_artifacts: path does not identify its named workload");
      }
      if (path != null && !paths.add(path)) {
        errors.add("$.workload_artifacts: duplicate output path " + path);
      }
    }
  }

  private static void validateStreamingManifestSemantics(
      JsonNode manifest,
      List<String> errors) {
    Set<String> names = new HashSet<>();
    Map<String, Long> familySeeds = new HashMap<>();
    Map<String, String> familyConfigs = new HashMap<>();
    for (JsonNode workload : manifest.path("workloads")) {
      String name = workload.path("name").textValue();
      String schemaId = workload.path("schema_id").textValue();
      String config = workload.path("config").textValue();
      if (name == null || !names.add(name)) {
        errors.add("$.workloads: duplicate or absent workload name " + name);
        continue;
      }
      int separator = name.indexOf('_');
      if (separator < 1) {
        errors.add("$.workloads: workload name has no family " + name);
        continue;
      }
      String family = name.substring(0, separator);
      String table = name.substring(separator + 1);
      String expectedSchema = family + '.' + table + ".v2";
      if (!expectedSchema.equals(schemaId)) {
        errors.add("$.workloads: schema_id does not match workload name " + name);
      }
      String configPrefix = "schema=" + family + "_v2;";
      if (config == null || !config.startsWith(configPrefix)) {
        errors.add("$.workloads: config family does not match workload name " + name);
        continue;
      }
      int tableOffset = config.indexOf(";table=");
      if (tableOffset < 0) {
        errors.add("$.workloads: config has no table identity " + name);
        continue;
      }
      int tableStart = tableOffset + ";table=".length();
      int tableEnd = config.indexOf(';', tableStart);
      String configuredTable = tableEnd < 0
          ? config.substring(tableStart)
          : config.substring(tableStart, tableEnd);
      if (!table.equals(configuredTable)) {
        errors.add("$.workloads: config table does not match workload name " + name);
      }
      String commonConfig = tableOffset < 0 ? config : config.substring(0, tableOffset);
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

  private static boolean contains(JsonNode array, JsonNode value) {
    for (JsonNode candidate : array) {
      if (candidate.equals(value)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasType(JsonNode value, String type) {
    return switch (type) {
      case "array" -> value.isArray();
      case "integer" -> value.isIntegralNumber();
      case "number" -> value.isNumber();
      case "object" -> value.isObject();
      case "string" -> value.isTextual();
      default -> false;
    };
  }

  private static String text(JsonNode node, String name) {
    JsonNode value = node.get(name);
    return value == null ? null : value.textValue();
  }
}
