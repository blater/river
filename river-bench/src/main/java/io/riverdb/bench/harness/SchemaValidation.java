package io.riverdb.bench.harness;

import java.util.List;

/** Result of validating a benchmark document without using exceptions as control flow. */
public record SchemaValidation(boolean valid, List<String> errors) {
  public SchemaValidation {
    errors = List.copyOf(errors);
    if (valid == !errors.isEmpty()) {
      throw new IllegalArgumentException("valid must agree with errors");
    }
  }
}
