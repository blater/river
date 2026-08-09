package io.riverdb.base.error;

/** Cold adapter for Java APIs that require exception-based failure reporting. */
public final class RiverException extends Exception {
  private static final long serialVersionUID = 1L;

  private final StatusCode statusCode;
  private final String sqlState;

  public RiverException(StatusCode statusCode, String sqlState, String message) {
    super(message);
    this.statusCode = statusCode;
    this.sqlState = sqlState;
  }

  public RiverException(StatusDetail detail, String sqlState) {
    this(detail.code(), sqlState, detail.asString());
  }

  public StatusCode statusCode() {
    return statusCode;
  }

  public String sqlState() {
    return sqlState;
  }
}
