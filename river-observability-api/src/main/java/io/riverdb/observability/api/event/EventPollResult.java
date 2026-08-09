package io.riverdb.observability.api.event;

/** Stable result of a single-consumer ring poll. */
public enum EventPollResult {
  POLLED,
  EMPTY,
  PUBLICATION_HOLE,
  NOT_CONSUMER
}
