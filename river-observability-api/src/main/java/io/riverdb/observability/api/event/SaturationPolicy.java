package io.riverdb.observability.api.event;

/** Non-blocking action when a bounded diagnostic queue has no available slot. */
public enum SaturationPolicy {
  DROP_AND_COUNT,
  REPORT_BACKPRESSURE
}
