package io.riverdb.base.error;

/**
 * Stable non-allocating engine outcomes. Numeric values are durable protocol-facing registry
 * identities; they must not be renumbered once published.
 */
public enum StatusCode {
  OK(0, StatusFamily.OK, false, false),
  RETRY(1000, StatusFamily.RETRY, true, false),
  FENCED(1001, StatusFamily.RETRY, false, false),
  CLOSED(1002, StatusFamily.RETRY, false, false),
  CANCELLED(2000, StatusFamily.CANCELLED, false, false),
  INVALID_EXTERNAL_INPUT(3000, StatusFamily.INVALID_EXTERNAL_INPUT, false, false),
  CARDINALITY_VIOLATION(3001, StatusFamily.INVALID_EXTERNAL_INPUT, false, false),
  NUMERIC_VALUE_OUT_OF_RANGE(3002, StatusFamily.INVALID_EXTERNAL_INPUT, false, false),
  CHECK_VIOLATION(3003, StatusFamily.INVALID_EXTERNAL_INPUT, false, false),
  UNIQUE_VIOLATION(3004, StatusFamily.INVALID_EXTERNAL_INPUT, false, false),
  FOREIGN_KEY_VIOLATION(3005, StatusFamily.INVALID_EXTERNAL_INPUT, false, false),
  DATATYPE_MISMATCH(3006, StatusFamily.INVALID_EXTERNAL_INPUT, false, false),
  ACCESS_DENIED(3007, StatusFamily.INVALID_EXTERNAL_INPUT, false, false),
  DIVISION_BY_ZERO(3008, StatusFamily.INVALID_EXTERNAL_INPUT, false, false),
  INVALID_DATETIME_FORMAT(3009, StatusFamily.INVALID_EXTERNAL_INPUT, false, false),
  DATETIME_FIELD_OVERFLOW(3010, StatusFamily.INVALID_EXTERNAL_INPUT, false, false),
  INVALID_TIME_ZONE_DISPLACEMENT(3011, StatusFamily.INVALID_EXTERNAL_INPUT, false, false),
  STRING_DATA_RIGHT_TRUNCATION(3012, StatusFamily.INVALID_EXTERNAL_INPUT, false, false),
  FEATURE_NOT_SUPPORTED(3013, StatusFamily.INVALID_EXTERNAL_INPUT, false, false),
  PARAMETER_COUNT_MISMATCH(3014, StatusFamily.INVALID_EXTERNAL_INPUT, false, false),
  PROGRAM_STALE(3015, StatusFamily.INVALID_EXTERNAL_INPUT, false, false),
  CONFLICT(4000, StatusFamily.CONFLICT, true, false),
  NOT_OWNER(4001, StatusFamily.CONFLICT, false, false),
  DEADLOCK(4002, StatusFamily.CONFLICT, true, false),
  RESOURCE_EXHAUSTED(5000, StatusFamily.RESOURCE_EXHAUSTED, false, false),
  QUERY_TOO_COMPLEX(5001, StatusFamily.RESOURCE_EXHAUSTED, false, false),
  TIMEOUT(6000, StatusFamily.TIMEOUT, false, false),
  IO_FAILURE(7000, StatusFamily.IO_FAILURE, false, false),
  CORRUPTION(8000, StatusFamily.CORRUPTION, false, true),
  INVARIANT_BROKEN(9000, StatusFamily.INVARIANT_BROKEN, false, true);

  private final int stableCode;
  private final StatusFamily family;
  private final boolean retryable;
  private final boolean fatal;

  StatusCode(int stableCode, StatusFamily family, boolean retryable, boolean fatal) {
    this.stableCode = stableCode;
    this.family = family;
    this.retryable = retryable;
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
    return retryable;
  }

  /**
   * Whether every occurrence is intrinsically fail-stop. A component may explicitly fence a
   * contextual failure such as IO_FAILURE without changing this global classification.
   */
  public boolean isFatal() {
    return fatal;
  }
}
