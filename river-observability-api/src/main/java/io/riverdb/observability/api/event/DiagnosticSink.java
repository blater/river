package io.riverdb.observability.api.event;

/**
 * General diagnostic event sink. Diagnostics explain outcomes; they are not engine control flow.
 * Security audit output requires a separate durable, authenticated subsystem.
 */
public interface DiagnosticSink extends KernelEventSink {
}
