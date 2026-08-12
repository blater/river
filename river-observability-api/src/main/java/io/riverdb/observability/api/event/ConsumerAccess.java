package io.riverdb.observability.api.event;

/** Consumer ownership checking mode selected outside the hot path. */
public enum ConsumerAccess {
  UNCHECKED,
  GUARDED
}
