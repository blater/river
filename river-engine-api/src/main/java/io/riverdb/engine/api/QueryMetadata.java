package io.riverdb.engine.api;

/** Immutable shape and reservation identity for one opened query generation. */
public interface QueryMetadata {
  int columnCount();

  int maximumEncodedTextBytes();

  long reservationGeneration();
}
