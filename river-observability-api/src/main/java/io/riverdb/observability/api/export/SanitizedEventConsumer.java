package io.riverdb.observability.api.export;

/** Export endpoint that cannot receive River's raw diagnostic payload type. */
@FunctionalInterface
public interface SanitizedEventConsumer {
  void accept(SanitizedDiagnosticEvent event);
}
