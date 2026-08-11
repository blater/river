package io.riverdb.jdbc;

import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RowResult;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

/** Streaming forward-only result set backed by one-row River fetch credit. */
final class RiverJdbcResultSet extends AbstractResultSet {
  private final RiverJdbcStatement statement;
  private final RiverQuery query;
  private final RowResult row = new RowResult();
  private final CommandResult completion = new CommandResult();
  private final char[] textCharacters =
      new char[CommandResult.MAXIMUM_TEXT_CHARACTERS];
  private final RiverResultSetMetaData metadata;
  private int rowNumber;
  private boolean rowAvailable;
  private boolean completed;
  private boolean closed;
  private boolean lastValueRead;
  private boolean lastWasNull;

  RiverJdbcResultSet(
      RiverJdbcStatement owner,
      RiverQuery remoteQuery) throws SQLException {
    int columnCount = remoteQuery.columnCount();
    if (columnCount <= 0 || columnCount > CommandResult.MAXIMUM_COLUMNS) {
      throw JdbcExceptions.invalid("query column count is invalid");
    }
    statement = owner;
    query = remoteQuery;
    metadata = new RiverResultSetMetaData(remoteQuery);
  }

  @Override
  public boolean next() throws SQLException {
    requireOpen();
    if (completed) {
      rowAvailable = false;
      return false;
    }
    row.reset();
    lastValueRead = false;
    lastWasNull = false;
    JdbcExceptions.require(query.next(row), "fetch row");
    rowAvailable = row.isAvailable();
    if (!rowAvailable) {
      completeQuery();
      return false;
    }
    rowNumber++;
    return true;
  }

  @Override
  public void close() throws SQLException {
    if (closed) {
      return;
    }
    if (!completed) {
      completeQuery();
    }
    closed = true;
    rowAvailable = false;
    lastValueRead = false;
    lastWasNull = false;
  }

  @Override
  public boolean wasNull() throws SQLException {
    requireRow();
    if (!lastValueRead) {
      throw JdbcExceptions.invalid("no column value has been read");
    }
    return lastWasNull;
  }

  @Override
  public String getString(int column) throws SQLException {
    long value = value(column);
    if (lastWasNull) {
      return null;
    }
    if (!metadata.isVarchar(column)) {
      return Long.toString(value);
    }
    int length = row.copyTextAt(column - 1, textCharacters, 0);
    if (length < 0) {
      throw JdbcExceptions.invalid("VARCHAR value is invalid");
    }
    return new String(textCharacters, 0, length);
  }

  @Override
  public boolean getBoolean(int column) throws SQLException {
    return numericValue(column) != 0;
  }

  @Override
  public byte getByte(int column) throws SQLException {
    long value = numericValue(column);
    if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
      throw JdbcExceptions.invalid("BIGINT value does not fit in byte");
    }
    return (byte) value;
  }

  @Override
  public short getShort(int column) throws SQLException {
    long value = numericValue(column);
    if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
      throw JdbcExceptions.invalid("BIGINT value does not fit in short");
    }
    return (short) value;
  }

  @Override
  public int getInt(int column) throws SQLException {
    long value = numericValue(column);
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw JdbcExceptions.invalid("BIGINT value does not fit in int");
    }
    return (int) value;
  }

  @Override
  public long getLong(int column) throws SQLException {
    return numericValue(column);
  }

  @Override
  public float getFloat(int column) throws SQLException {
    return numericValue(column);
  }

  @Override
  public double getDouble(int column) throws SQLException {
    return numericValue(column);
  }

  @Override
  public BigDecimal getBigDecimal(int column) throws SQLException {
    long value = numericValue(column);
    return lastWasNull ? null : BigDecimal.valueOf(value);
  }

  @Override
  public Object getObject(int column) throws SQLException {
    long value = value(column);
    if (lastWasNull) {
      return null;
    }
    return metadata.isVarchar(column) ? getString(column) : Long.valueOf(value);
  }

  @Override
  public <T> T getObject(int column, Class<T> type) throws SQLException {
    if (type == null) {
      throw JdbcExceptions.invalid("target type must not be null");
    }
    long value = value(column);
    if (lastWasNull) {
      return null;
    }
    Object converted;
    if (metadata.isVarchar(column)) {
      if (type != String.class) {
        throw JdbcExceptions.unsupported();
      }
      converted = getString(column);
    } else if (type == Long.class || type == Long.TYPE) {
      converted = Long.valueOf(value);
    } else if (type == Integer.class || type == Integer.TYPE) {
      if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
        throw JdbcExceptions.invalid("BIGINT value does not fit in int");
      }
      converted = Integer.valueOf((int) value);
    } else if (type == String.class) {
      converted = Long.toString(value);
    } else if (type == BigDecimal.class) {
      converted = BigDecimal.valueOf(value);
    } else {
      throw JdbcExceptions.unsupported();
    }
    @SuppressWarnings("unchecked")
    T result = (T) converted;
    return result;
  }

  @Override
  public long getLong(String label) throws SQLException {
    return getLong(findColumn(label));
  }

  @Override
  public int getInt(String label) throws SQLException {
    return getInt(findColumn(label));
  }

  @Override
  public String getString(String label) throws SQLException {
    return getString(findColumn(label));
  }

  @Override
  public Object getObject(String label) throws SQLException {
    return getObject(findColumn(label));
  }

  @Override
  public <T> T getObject(String label, Class<T> type) throws SQLException {
    return getObject(findColumn(label), type);
  }

  @Override
  public int findColumn(String label) throws SQLException {
    requireOpen();
    return metadata.findColumn(label);
  }

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    requireOpen();
    return metadata;
  }

  @Override
  public boolean isBeforeFirst() throws SQLException {
    requireOpen();
    return rowNumber == 0 && !completed;
  }

  @Override
  public boolean isAfterLast() throws SQLException {
    requireOpen();
    return completed && rowNumber > 0;
  }

  @Override
  public boolean isFirst() throws SQLException {
    requireOpen();
    return rowAvailable && rowNumber == 1;
  }

  @Override
  public int getRow() throws SQLException {
    requireOpen();
    return rowAvailable ? rowNumber : 0;
  }

  @Override
  public void setFetchDirection(int direction) throws SQLException {
    requireOpen();
    if (direction != ResultSet.FETCH_FORWARD) {
      throw JdbcExceptions.unsupported();
    }
  }

  @Override
  public int getFetchDirection() throws SQLException {
    requireOpen();
    return ResultSet.FETCH_FORWARD;
  }

  @Override
  public void setFetchSize(int rows) throws SQLException {
    requireOpen();
    if (rows < 0 || rows > 1) {
      throw JdbcExceptions.unsupported();
    }
  }

  @Override
  public int getFetchSize() throws SQLException {
    requireOpen();
    return 1;
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
  public Statement getStatement() throws SQLException {
    requireOpen();
    return statement;
  }

  @Override
  public int getHoldability() throws SQLException {
    requireOpen();
    return ResultSet.CLOSE_CURSORS_AT_COMMIT;
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public java.sql.SQLWarning getWarnings() throws SQLException {
    requireOpen();
    return null;
  }

  @Override
  public void clearWarnings() throws SQLException {
    requireOpen();
  }

  @Override
  public <T> T unwrap(Class<T> type) throws SQLException {
    if (type != null && type.isInstance(this)) {
      return type.cast(this);
    }
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean isWrapperFor(Class<?> type) {
    return type != null && type.isInstance(this);
  }

  private long value(int column) throws SQLException {
    requireRow();
    if (column <= 0 || column > metadata.getColumnCount()) {
      throw JdbcExceptions.invalid("column index is out of range");
    }
    lastValueRead = true;
    lastWasNull = row.isNull(column - 1);
    return row.valueAt(column - 1);
  }

  private long numericValue(int column) throws SQLException {
    long result = value(column);
    if (metadata.isVarchar(column) && !lastWasNull) {
      throw JdbcExceptions.invalid("VARCHAR value is not numeric");
    }
    return result;
  }

  private void completeQuery() throws SQLException {
    completion.reset();
    JdbcExceptions.require(query.close(completion), "close query");
    completed = true;
    rowAvailable = false;
    lastValueRead = false;
    lastWasNull = false;
    statement.queryCompleted(this, completion);
  }

  private void requireRow() throws SQLException {
    requireOpen();
    if (!rowAvailable) {
      throw JdbcExceptions.invalid("result set is not positioned on a row");
    }
  }

  private void requireOpen() throws SQLException {
    if (closed) {
      throw JdbcExceptions.closed("result set");
    }
  }
}
