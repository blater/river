package io.riverdb.platform.fault;

/** Allocation-free hook at a named production failure boundary. */
@FunctionalInterface
public interface FaultInjector {
  /**
   * Writes a decision into {@code result}. Arguments describe the attempted operation and are
   * deliberately primitive so a hot-path check does not allocate.
   */
  void evaluate(
      FaultPoint point,
      FaultOperation operation,
      long attempt,
      long position,
      int requestedBytes,
      FaultDecision result);

  /** Boundary-aware extension; legacy providers are before-boundary only. */
  default void evaluate(
      FaultPoint point,
      FaultOperation operation,
      FaultBoundary boundary,
      long attempt,
      long position,
      int requestedBytes,
      FaultDecision result) {
    evaluate(point, operation, attempt, position, requestedBytes, result);
  }
}
