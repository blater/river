package io.riverdb.observability.api.event;

/** Ordered diagnostic severity. Severity records impact; it does not control shutdown. */
public enum Severity {
  DEBUG(10),
  INFO(20),
  WARN(30),
  ERROR(40),
  FATAL(50);

  private final int stableCode;

  Severity(int stableCode) {
    this.stableCode = stableCode;
  }

  public int stableCode() {
    return stableCode;
  }

  public boolean isEnabledAt(Severity threshold) {
    return stableCode >= threshold.stableCode;
  }
}
