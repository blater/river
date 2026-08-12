package io.riverdb.observability.api.event;

/** Build/runtime mode which owns the diagnostic consumer guard choice. */
public enum ObservabilityBuildMode {
  PRODUCTION(ConsumerAccess.UNCHECKED),
  DIAGNOSTIC(ConsumerAccess.GUARDED),
  TEST(ConsumerAccess.GUARDED);

  private final ConsumerAccess consumerAccess;

  ObservabilityBuildMode(ConsumerAccess consumerAccess) {
    this.consumerAccess = consumerAccess;
  }

  public ConsumerAccess consumerAccess() {
    return consumerAccess;
  }
}
