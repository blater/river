package io.riverdb.engine.schema.catalog;

/** Caller-owned exact payload sizing result. */
public final class CatalogPayloadSize {
  private int bytes;

  void set(int value) { bytes = value; }
  public void reset() { bytes = 0; }
  public int bytes() { return bytes; }
}
