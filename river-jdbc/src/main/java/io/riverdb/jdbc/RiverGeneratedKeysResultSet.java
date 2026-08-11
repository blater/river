package io.riverdb.jdbc;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

/** One-row local result for an identity value returned by an update. */
final class RiverGeneratedKeysResultSet extends AbstractResultSet {
  private final RiverJdbcStatement statement;
  private final RiverResultSetMetaData metadata =
      new RiverResultSetMetaData("GENERATED_KEY", true);
  private final long key;
  private final boolean available;
  private boolean visited;
  private boolean row;
  private boolean closed;

  RiverGeneratedKeysResultSet(
      RiverJdbcStatement owner,
      long generatedKey,
      boolean generatedKeyAvailable) {
    statement = owner;
    key = generatedKey;
    available = generatedKeyAvailable;
  }

  @Override
  public boolean next() throws SQLException {
    requireOpen();
    if (visited) {
      row = false;
      return false;
    }
    visited = true;
    row = available;
    return row;
  }

  @Override
  public void close() {
    closed = true;
    row = false;
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public long getLong(int column) throws SQLException {
    requireRow(column);
    return key;
  }

  @Override
  public long getLong(String label) throws SQLException {
    return getLong(findColumn(label));
  }

  @Override
  public int getInt(int column) throws SQLException {
    long value = getLong(column);
    if (value > Integer.MAX_VALUE) {
      throw JdbcExceptions.invalid("generated key does not fit in int");
    }
    return (int) value;
  }

  @Override
  public int getInt(String label) throws SQLException {
    return getInt(findColumn(label));
  }

  @Override
  public String getString(int column) throws SQLException {
    return Long.toString(getLong(column));
  }

  @Override
  public String getString(String label) throws SQLException {
    return getString(findColumn(label));
  }

  @Override
  public Object getObject(int column) throws SQLException {
    return Long.valueOf(getLong(column));
  }

  @Override
  public Object getObject(String label) throws SQLException {
    return getObject(findColumn(label));
  }

  @Override
  public <T> T getObject(int column, Class<T> type) throws SQLException {
    if (type == null) {
      throw JdbcExceptions.invalid("target type must not be null");
    }
    Object value;
    if (type == Long.class) {
      value = Long.valueOf(getLong(column));
    } else if (type == String.class) {
      value = getString(column);
    } else if (type == Integer.class) {
      value = Integer.valueOf(getInt(column));
    } else {
      throw JdbcExceptions.unsupported();
    }
    return type.cast(value);
  }

  @Override
  public <T> T getObject(String label, Class<T> type) throws SQLException {
    return getObject(findColumn(label), type);
  }

  @Override
  public boolean wasNull() throws SQLException {
    requireRow(1);
    return false;
  }

  @Override
  public int findColumn(String label) throws SQLException {
    return metadata.findColumn(label);
  }

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    requireOpen();
    return metadata;
  }

  @Override
  public Statement getStatement() throws SQLException {
    requireOpen();
    return statement;
  }

  @Override
  public int getType() throws SQLException {
    requireOpen();
    return ResultSet.TYPE_FORWARD_ONLY;
  }

  @Override
  public int getConcurrency() throws SQLException {
    requireOpen();
    return ResultSet.CONCUR_READ_ONLY;
  }

  @Override
  public int getFetchDirection() throws SQLException {
    requireOpen();
    return ResultSet.FETCH_FORWARD;
  }

  @Override
  public int getFetchSize() throws SQLException {
    requireOpen();
    return 1;
  }

  @Override
  public int getRow() throws SQLException {
    requireOpen();
    return row ? 1 : 0;
  }

  private void requireOpen() throws SQLException {
    if (closed) {
      throw JdbcExceptions.invalid("generated keys result is closed");
    }
  }

  private void requireRow(int column) throws SQLException {
    requireOpen();
    if (!row || column != 1) {
      throw JdbcExceptions.invalid("generated key row or column is unavailable");
    }
  }
}
