package io.riverdb.engine.relational;

/** Caller-owned visible catalog object returned by a catalog scan. */
public final class CatalogObjectResult {
  public static final int TABLE = 1;
  public static final int VIEW = 2;

  private final TableSchema.ColumnName name = new TableSchema.ColumnName();
  private int type;
  private boolean available;

  public void reset() {
    name.reset();
    type = 0;
    available = false;
  }

  void set(CharSequence objectName, int objectType) {
    name.set(objectName);
    type = objectType;
    available = true;
  }

  public CharSequence name() {
    return available ? name : null;
  }

  public int type() {
    return type;
  }

  public boolean isAvailable() {
    return available;
  }
}
