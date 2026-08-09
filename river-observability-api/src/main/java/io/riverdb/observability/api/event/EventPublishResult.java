package io.riverdb.observability.api.event;

/** Stable result of a best-effort event publication attempt. */
public enum EventPublishResult {
  PUBLISHED,
  DISABLED,
  DROPPED,
  COALESCED,
  BACKPRESSURE
}
