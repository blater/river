package io.riverdb.observability.api.event;

/**
 * Caller-owned, reusable correlation context. All payload is fixed-width; there are no labels,
 * maps, strings, or per-use objects.
 */
public final class DiagnosticContext {
  private long presenceMask;
  private long databaseId;
  private long sessionId;
  private long transactionId;
  private long requestIdHigh;
  private long requestIdLow;
  private long statementFingerprint;

  public DiagnosticContext reset() {
    presenceMask = 0;
    databaseId = 0;
    sessionId = 0;
    transactionId = 0;
    requestIdHigh = 0;
    requestIdLow = 0;
    statementFingerprint = 0;
    return this;
  }

  public DiagnosticContext copyFrom(DiagnosticContext source) {
    presenceMask = source.presenceMask;
    databaseId = source.databaseId;
    sessionId = source.sessionId;
    transactionId = source.transactionId;
    requestIdHigh = source.requestIdHigh;
    requestIdLow = source.requestIdLow;
    statementFingerprint = source.statementFingerprint;
    return this;
  }

  public DiagnosticContext databaseId(long value) {
    presenceMask |= DiagnosticContextField.DATABASE_ID.presenceBit();
    databaseId = value;
    return this;
  }

  public DiagnosticContext sessionId(long value) {
    presenceMask |= DiagnosticContextField.SESSION_ID.presenceBit();
    sessionId = value;
    return this;
  }

  public DiagnosticContext transactionId(long value) {
    presenceMask |= DiagnosticContextField.TRANSACTION_ID.presenceBit();
    transactionId = value;
    return this;
  }

  public DiagnosticContext requestId(long high, long low) {
    presenceMask |= DiagnosticContextField.REQUEST_ID_HIGH.presenceBit();
    presenceMask |= DiagnosticContextField.REQUEST_ID_LOW.presenceBit();
    requestIdHigh = high;
    requestIdLow = low;
    return this;
  }

  public DiagnosticContext statementFingerprint(long value) {
    presenceMask |= DiagnosticContextField.STATEMENT_FINGERPRINT.presenceBit();
    statementFingerprint = value;
    return this;
  }

  public boolean has(DiagnosticContextField field) {
    return (presenceMask & field.presenceBit()) != 0;
  }

  public long value(DiagnosticContextField field) {
    return switch (field) {
      case DATABASE_ID -> databaseId;
      case SESSION_ID -> sessionId;
      case TRANSACTION_ID -> transactionId;
      case REQUEST_ID_HIGH -> requestIdHigh;
      case REQUEST_ID_LOW -> requestIdLow;
      case STATEMENT_FINGERPRINT -> statementFingerprint;
    };
  }

  public long presenceMask() {
    return presenceMask;
  }
}
