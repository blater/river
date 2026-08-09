package io.riverdb.base.error;

/**
 * Stable non-allocating engine outcomes. Numeric values are durable protocol-facing registry
 * identities; they must not be renumbered once published.
 */
public enum StatusCode {
  OK(0, StatusFamily.OK, false),
  RETRY(1000, StatusFamily.RETRY, false),
  FENCED(1001, StatusFamily.RETRY, false),
  CLOSED(1002, StatusFamily.RETRY, false),
  CANCELLED(2000, StatusFamily.CANCELLED, false),
  INVALID_EXTERNAL_INPUT(3000, StatusFamily.INVALID_EXTERNAL_INPUT, false),
  CONFLICT(4000, StatusFamily.CONFLICT, false),
  NOT_OWNER(4001, StatusFamily.CONFLICT, false),
  RESOURCE_EXHAUSTED(5000, StatusFamily.RESOURCE_EXHAUSTED, false),
  TIMEOUT(6000, StatusFamily.TIMEOUT, false),
  IO_FAILURE(7000, StatusFamily.IO_FAILURE, false),
  CORRUPTION(8000, StatusFamily.CORRUPTION, true),
  INVARIANT_BROKEN(9000, StatusFamily.INVARIANT_BROKEN, true);

  private final int stableCode;
  private final StatusFamily family;
  private final boolean fatal;

  StatusCode(int stableCode, StatusFamily family, boolean fatal) {
    this.stableCode = stableCode;
    this.family = family;
    this.fatal = fatal;
  }

  public int stableCode() {
    return stableCode;
  }

  public StatusFamily family() {
    return family;
  }

  public boolean isOk() {
    return this == OK;
  }

  public boolean isRetryable() {
    return family == StatusFamily.RETRY || family == StatusFamily.CONFLICT;
  }

  public boolean isFatal() {
    return fatal;
  }
}
