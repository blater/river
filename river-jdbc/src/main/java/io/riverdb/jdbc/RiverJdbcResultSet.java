package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RowResult;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;

/** Streaming forward-only result set backed by one-row River fetch credit. */
final class RiverJdbcResultSet extends AbstractResultSet {
  private final RiverJdbcStatement statement;
  private final RiverQuery query;
  private final RowResult row = new RowResult();
  private final CommandResult completion = new CommandResult();
  private char[] textCharacters = RiverJdbcTextScratch.EMPTY;
  private final RiverResultSetMetaData metadata;
  private final RiverJdbcValueConversion valueConversion =
      new RiverJdbcValueConversion(this);
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
    try {
      JdbcExceptions.require(query.next(row), "fetch row");
    } catch (SQLException failure) {
      completeAfterFailure(failure);
      throw failure;
    }
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
    return valueConversion.getString(column);
  }

  @Override
  public boolean getBoolean(int column) throws SQLException {
    return valueConversion.getBoolean(column);
  }

  @Override
  public byte getByte(int column) throws SQLException {
    return valueConversion.getByte(column);
  }

  @Override
  public short getShort(int column) throws SQLException {
    return valueConversion.getShort(column);
  }

  @Override
  public int getInt(int column) throws SQLException {
    return valueConversion.getInt(column);
  }

  @Override
  public long getLong(int column) throws SQLException {
    return valueConversion.getLong(column);
  }

  @Override
  public float getFloat(int column) throws SQLException {
    return valueConversion.getFloat(column);
  }

  @Override
  public double getDouble(int column) throws SQLException {
    return valueConversion.getDouble(column);
  }

  @Override
  public BigDecimal getBigDecimal(int column) throws SQLException {
    return valueConversion.getBigDecimal(column);
  }

  @Override
  public Object getObject(int column) throws SQLException {
    return valueConversion.getObject(column);
  }

  @Override
  public <T> T getObject(int column, Class<T> type) throws SQLException {
    return valueConversion.getObject(column, type);
  }

  @Override
  public Date getDate(int column) throws SQLException {
    return valueConversion.getDate(column);
  }

  @Override
  public Time getTime(int column) throws SQLException {
    return valueConversion.getTime(column);
  }

  @Override
  public Timestamp getTimestamp(int column) throws SQLException {
    return valueConversion.getTimestamp(column);
  }

  @Override
  public long getLong(String label) throws SQLException {
    return getLong(findColumn(label));
  }

  @Override
  public boolean getBoolean(String label) throws SQLException {
    return getBoolean(findColumn(label));
  }

  @Override
  public byte getByte(String label) throws SQLException {
    return getByte(findColumn(label));
  }

  @Override
  public short getShort(String label) throws SQLException {
    return getShort(findColumn(label));
  }

  @Override
  public int getInt(String label) throws SQLException {
    return getInt(findColumn(label));
  }

  @Override
  public float getFloat(String label) throws SQLException {
    return getFloat(findColumn(label));
  }

  @Override
  public double getDouble(String label) throws SQLException {
    return getDouble(findColumn(label));
  }

  @Override
  public BigDecimal getBigDecimal(String label) throws SQLException {
    return getBigDecimal(findColumn(label));
  }

  @Override
  public String getString(String label) throws SQLException {
    return getString(findColumn(label));
  }

  @Override
  public Date getDate(String label) throws SQLException {
    return getDate(findColumn(label));
  }

  @Override
  public Time getTime(String label) throws SQLException {
    return getTime(findColumn(label));
  }

  @Override
  public Timestamp getTimestamp(String label) throws SQLException {
    return getTimestamp(findColumn(label));
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

  long value(int column) throws SQLException {
    requireRow();
    if (column <= 0 || column > metadata.getColumnCount()) {
      throw JdbcExceptions.invalid("column index is out of range");
    }
    lastValueRead = true;
    lastWasNull = row.isNull(column - 1);
    return row.valueAt(column - 1);
  }

  RiverResultSetMetaData metadata() {
    return metadata;
  }

  char[] textCharacters() throws SQLException {
    textCharacters = RiverJdbcTextScratch.require(
        textCharacters, RiverJdbcTextScratch.TEMPORAL_CHARACTERS);
    return textCharacters;
  }

  char[] textCharacters(int minimumCharacters) throws SQLException {
    textCharacters = RiverJdbcTextScratch.require(textCharacters, minimumCharacters);
    return textCharacters;
  }

  int textLength(int column) {
    return row.textLengthAt(column - 1);
  }

  int copyText(int column, char[] target, int offset) {
    return row.copyTextAt(column - 1, target, offset);
  }

  long decimalUnscaledHigh(int column) {
    return row.decimalUnscaledHighAt(column - 1);
  }

  boolean lastWasNull() {
    return lastWasNull;
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

  private void completeAfterFailure(SQLException failure) {
    completion.reset();
    StatusCode status = query.close(completion);
    if (status.isOk() || !query.isActive()) {
      completed = true;
      rowAvailable = false;
      lastValueRead = false;
      lastWasNull = false;
      try {
        statement.queryCompleted(this, completion);
      } catch (SQLException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
    } else {
      failure.addSuppressed(JdbcExceptions.failure(status, "close failed query"));
    }
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
