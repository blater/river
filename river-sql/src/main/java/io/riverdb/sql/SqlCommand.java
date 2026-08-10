package io.riverdb.sql;

/** Caller-owned parsed SQL command for the first executable point-statement subset. */
public final class SqlCommand {
  private final SqlIdentifier tableName = new SqlIdentifier();
  private SqlCommandType type;
  private long key;
  private long value;
  private boolean available;

  public void reset() {
    tableName.reset();
    type = null;
    key = 0;
    value = 0;
    available = false;
  }

  void set(SqlCommandType commandType, long primaryKey, long rowValue) {
    type = commandType;
    key = primaryKey;
    value = rowValue;
    available = true;
  }

  SqlIdentifier writableTableName() {
    return tableName;
  }

  public SqlCommandType type() {
    return type;
  }

  public SqlIdentifier tableName() {
    return tableName;
  }

  public long key() {
    return key;
  }

  public long value() {
    return value;
  }

  public boolean isAvailable() {
    return available;
  }
}
