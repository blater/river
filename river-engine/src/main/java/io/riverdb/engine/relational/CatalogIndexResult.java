package io.riverdb.engine.relational;

/** Caller-owned visible index descriptor returned by a catalog index scan. */
public final class CatalogIndexResult {
  private final TableSchema.ColumnName indexName = new TableSchema.ColumnName();
  private final TableSchema.ColumnName columnName = new TableSchema.ColumnName();
  private boolean primary;
  private boolean unique;
  private boolean constraint;
  private boolean available;

  public void reset() {
    indexName.reset();
    columnName.reset();
    primary = false;
    unique = false;
    constraint = false;
    available = false;
  }

  void setPrimary(CharSequence column) {
    columnName.set(column);
    primary = true;
    unique = true;
    available = true;
  }

  void set(
      CharSequence name,
      CharSequence column,
      boolean isUnique,
      boolean isConstraint) {
    indexName.set(name);
    columnName.set(column);
    unique = isUnique;
    constraint = isConstraint;
    available = true;
  }

  public CharSequence indexName() {
    return available && !primary ? indexName : null;
  }

  public CharSequence columnName() {
    return available ? columnName : null;
  }

  public boolean isPrimary() {
    return primary;
  }

  public boolean isUnique() {
    return unique;
  }

  public boolean isConstraint() {
    return constraint;
  }

  public boolean isAvailable() {
    return available;
  }
}
