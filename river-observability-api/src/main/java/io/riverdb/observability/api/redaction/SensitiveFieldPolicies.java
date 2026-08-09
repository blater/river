package io.riverdb.observability.api.redaction;

/** Singleton redaction policies. Exporters should normally use {@link #safeExternal()}. */
public final class SensitiveFieldPolicies {
  private static final SensitiveFieldPolicy SAFE_EXTERNAL = sensitivity ->
      sensitivity == Sensitivity.PUBLIC;
  private static final SensitiveFieldPolicy INTERNAL_DIAGNOSTICS = sensitivity ->
      sensitivity != Sensitivity.SENSITIVE;
  private static final SensitiveFieldPolicy PRIVILEGED = sensitivity -> true;

  private SensitiveFieldPolicies() {
  }

  public static SensitiveFieldPolicy safeExternal() {
    return SAFE_EXTERNAL;
  }

  public static SensitiveFieldPolicy internalDiagnostics() {
    return INTERNAL_DIAGNOSTICS;
  }

  public static SensitiveFieldPolicy privileged() {
    return PRIVILEGED;
  }
}
