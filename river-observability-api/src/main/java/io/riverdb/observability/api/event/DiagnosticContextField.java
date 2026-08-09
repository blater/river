package io.riverdb.observability.api.event;

import io.riverdb.observability.api.redaction.Sensitivity;

/** Fixed correlation fields and their central sensitivity classifications. */
public enum DiagnosticContextField {
  DATABASE_ID(0, Sensitivity.INTERNAL),
  SESSION_ID(1, Sensitivity.SENSITIVE),
  TRANSACTION_ID(2, Sensitivity.SENSITIVE),
  REQUEST_ID_HIGH(3, Sensitivity.SENSITIVE),
  REQUEST_ID_LOW(4, Sensitivity.SENSITIVE),
  STATEMENT_FINGERPRINT(5, Sensitivity.SENSITIVE);

  private final int bitIndex;
  private final Sensitivity sensitivity;

  DiagnosticContextField(int bitIndex, Sensitivity sensitivity) {
    this.bitIndex = bitIndex;
    this.sensitivity = sensitivity;
  }

  public long presenceBit() {
    return 1L << bitIndex;
  }

  public Sensitivity sensitivity() {
    return sensitivity;
  }
}
