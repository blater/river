package io.riverdb.observability.api;

/**
 * Compatibility identity for the first frozen observability registry. Version 1 begins at River
 * commit {@value #BASELINE_COMMIT}; its IDs may be retired but never reused.
 */
public final class ObservabilityRegistryV1 {
  public static final int VERSION = 1;
  public static final String BASELINE_COMMIT = "0bff93f";

  private ObservabilityRegistryV1() {
  }
}
