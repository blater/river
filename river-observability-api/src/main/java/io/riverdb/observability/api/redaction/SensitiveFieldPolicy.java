package io.riverdb.observability.api.redaction;

/** Central policy consulted before a diagnostic field crosses an export boundary. */
@FunctionalInterface
public interface SensitiveFieldPolicy {
  boolean permits(Sensitivity sensitivity);
}
