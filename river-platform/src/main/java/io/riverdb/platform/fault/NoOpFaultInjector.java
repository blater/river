package io.riverdb.platform.fault;

/** Allocation-free production default. */
public enum NoOpFaultInjector implements FaultInjector {
  INSTANCE;

  @Override
  public void evaluate(
      FaultPoint point,
      FaultOperation operation,
      long attempt,
      long position,
      int requestedBytes,
      FaultDecision result) {
    result.reset();
  }
}
