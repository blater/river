package io.riverdb.server;

/** Caller-owned result for the bounded durable security audit. */
final class SecurityAuditOpenResult {
  private SecurityAuditLog audit;

  void reset() {
    audit = null;
  }

  void set(SecurityAuditLog opened) {
    audit = opened;
  }

  SecurityAuditLog audit() {
    return audit;
  }
}
