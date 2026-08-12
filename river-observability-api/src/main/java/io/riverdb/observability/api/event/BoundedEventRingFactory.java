package io.riverdb.observability.api.event;

/** Owns mode-dependent construction of bounded diagnostic rings. */
public final class BoundedEventRingFactory {
  private BoundedEventRingFactory() {
  }

  /**
   * Creates a ring with guarded consumer ownership in diagnostic and test modes.
   *
   * <p>Production selects the lower-overhead unchecked path only after the database lifecycle has
   * established a single owning exporter thread. Callers that cannot establish that invariant
   * must select {@link ObservabilityBuildMode#DIAGNOSTIC} even in a production process.
   */
  public static BoundedEventRing create(
      int capacity,
      Severity threshold,
      SaturationPolicy saturationPolicy,
      ObservabilityBuildMode buildMode) {
    return new BoundedEventRing(
        capacity,
        threshold,
        saturationPolicy,
        buildMode.consumerAccess());
  }
}
