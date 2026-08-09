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
}
