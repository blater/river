package io.riverdb.server;

/** Caller-owned transfer result for a successfully opened audit owner. */
public final class SecurityAuditOpenResult {
  private SecurityAuditLog audit;

  public SecurityAuditLog audit() {
    return audit;
  }

  public void reset() {
    audit = null;
  }

  void set(SecurityAuditLog value) {
    audit = value;
  }
}
