package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RowResult;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

/** The single-column primary key of one durable River table. */
final class RiverPrimaryKeyResultSet extends AbstractResultSet {
  private static final String TABLE = "TABLE";
  private static final String[] COLUMN_NAMES = {
      "TABLE_CAT",
      "TABLE_SCHEM",
      "TABLE_NAME",
      "COLUMN_NAME",
      "KEY_SEQ",
      "PK_NAME"
  };
  private static final int[] COLUMN_TYPES = {
      Types.VARCHAR,
      Types.VARCHAR,
      Types.VARCHAR,
      Types.VARCHAR,
      Types.SMALLINT,
      Types.VARCHAR
  };
  private static final int[] COLUMN_NULLABILITY = {
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNoNulls,
      ResultSetMetaData.columnNoNulls,
      ResultSetMetaData.columnNoNulls,
      ResultSetMetaData.columnNullable
  };
  private static final int[] COLUMN_WIDTHS = {64, 64, 64, 64, 5, 64};
  private static final RiverResultSetMetaData METADATA =
      new RiverResultSetMetaData(
          COLUMN_NAMES,
          COLUMN_TYPES,
          COLUMN_NULLABILITY,
          COLUMN_WIDTHS);

  private final RiverJdbcConnection connection;
  private final RowResult source = new RowResult();
  private final CommandResult completion = new CommandResult();
  private final char[] catalogName =
      new char[CommandResult.MAXIMUM_TEXT_CHARACTERS];
  private final char[] catalogType =
      new char[CommandResult.MAXIMUM_TEXT_CHARACTERS];
  private final String requestedTable;
  private RiverQuery query;
  private String primaryColumn;
  private boolean resolved;
  private boolean rowAvailable;
  private boolean rowReturned;
  private boolean completed;
  private boolean closed;
  private boolean lastValueRead;
  private boolean lastWasNull;

  RiverPrimaryKeyResultSet(
      RiverJdbcConnection owner,
      RiverQuery catalogQuery,
      String tableName) {
    connection = owner;
    query = catalogQuery;
    requestedTable = tableName;
  }

  @Override
  public boolean next() throws SQLException {
    requireOpen();
    rowAvailable = false;
    lastValueRead = false;
    lastWasNull = false;
    if (completed) {
      return false;
    }
    if (!resolved) {
      resolvePrimaryKey();
    }
    if (primaryColumn == null || rowReturned) {
      finishLocal();
      return false;
    }
    rowAvailable = true;
    rowReturned = true;
    return true;
  }

  @Override
  public void close() throws SQLException {
    if (closed) {
      return;
    }
    if (query != null && query.isActive()) {
      closeQuery("close primary-key metadata");
    }
    closed = true;
    completed = true;
    rowAvailable = false;
    primaryColumn = null;
    connection.metadataResultClosed(this);
  }

  @Override
  public String getString(int column) throws SQLException {
    Object value = readValue(column);
    return value == null ? null : value.toString();
  }

  @Override
  public String getString(String label) throws SQLException {
    return getString(findColumn(label));
  }

  @Override
  public short getShort(int column) throws SQLException {
    Object value = readValue(column);
    if (value == null) {
      return 0;
    }
    if (value instanceof Short number) {
      return number;
    }
    throw JdbcExceptions.invalid("primary-key metadata field is not numeric");
  }

  @Override
  public short getShort(String label) throws SQLException {
    return getShort(findColumn(label));
  }

  @Override
  public int getInt(int column) throws SQLException {
    return getShort(column);
  }

  @Override
  public int getInt(String label) throws SQLException {
    return getInt(findColumn(label));
  }

  @Override
  public long getLong(int column) throws SQLException {
    return getShort(column);
  }

  @Override
  public long getLong(String label) throws SQLException {
    return getLong(findColumn(label));
  }

  @Override
  public Object getObject(int column) throws SQLException {
    return readValue(column);
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
    Object value = readValue(column);
    if (value == null || type.isInstance(value)) {
      return type.cast(value);
    }
    if (type == String.class) {
      return type.cast(value.toString());
    }
    throw JdbcExceptions.unsupported();
  }

  @Override
  public <T> T getObject(String label, Class<T> type) throws SQLException {
    return getObject(findColumn(label), type);
  }

  @Override
  public boolean wasNull() throws SQLException {
    requireOpen();
    if (!rowAvailable || !lastValueRead) {
      throw JdbcExceptions.invalid("no primary-key metadata value has been read");
    }
    return lastWasNull;
  }

  @Override
  public int findColumn(String label) throws SQLException {
    requireOpen();
    return METADATA.findColumn(label);
  }

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    requireOpen();
    return METADATA;
  }

  @Override
  public Statement getStatement() throws SQLException {
    requireOpen();
    return null;
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
  public void setFetchDirection(int direction) throws SQLException {
    requireOpen();
    if (direction != ResultSet.FETCH_FORWARD) {
      throw JdbcExceptions.unsupported();
    }
  }

  @Override
  public int getFetchSize() throws SQLException {
    requireOpen();
    return 1;
  }

  @Override
  public void setFetchSize(int rows) throws SQLException {
    requireOpen();
    if (rows < 0 || rows > 1) {
      throw JdbcExceptions.unsupported();
    }
  }

  @Override
  public int getHoldability() throws SQLException {
    requireOpen();
    return ResultSet.CLOSE_CURSORS_AT_COMMIT;
  }

  @Override
  public int getRow() throws SQLException {
    requireOpen();
    return rowAvailable ? 1 : 0;
  }

  @Override
  public boolean isBeforeFirst() throws SQLException {
    requireOpen();
    return !rowReturned && !completed;
  }

  @Override
  public boolean isAfterLast() throws SQLException {
    requireOpen();
    return completed && rowReturned;
  }

  @Override
  public boolean isFirst() throws SQLException {
    requireOpen();
    return rowAvailable;
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public <T> T unwrap(Class<T> type) throws SQLException {
    requireOpen();
    if (type != null && type.isInstance(this)) {
      return type.cast(this);
    }
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean isWrapperFor(Class<?> type) {
    return !closed && type != null && type.isInstance(this);
  }

  private void resolvePrimaryKey() throws SQLException {
    boolean tableFound = false;
    while (query != null && query.isActive()) {
      source.reset();
      JdbcExceptions.require(query.next(source), "fetch primary-key catalog");
      if (!source.isAvailable()) {
        break;
      }
      int nameLength = source.copyTextAt(0, catalogName, 0);
      int typeLength = source.copyTextAt(1, catalogType, 0);
      if (nameLength < 0 || typeLength < 0) {
        throw JdbcExceptions.failure(
            StatusCode.INVARIANT_BROKEN,
            "decode primary-key catalog");
      }
      if (RiverCatalogResultSet.equals(catalogName, nameLength, requestedTable)
          && RiverCatalogResultSet.equals(catalogType, typeLength, TABLE)) {
        tableFound = true;
      }
    }
    if (query != null && query.isActive()) {
      closeQuery("close primary-key catalog");
    }
    if (tableFound) {
      query = connection.openColumnDescription(requestedTable);
      if (query.columnCount() <= 0 || query.columnName(0) == null) {
        throw JdbcExceptions.failure(
            StatusCode.INVARIANT_BROKEN,
            "decode primary-key column");
      }
      primaryColumn = query.columnName(0).toString();
      closeQuery("close primary-key description");
    }
    resolved = true;
  }

  private void closeQuery(String operation) throws SQLException {
    completion.reset();
    JdbcExceptions.require(query.close(completion), operation);
    query = null;
    connection.metadataQueryClosed(completion);
  }

  private Object readValue(int column) throws SQLException {
    requireRow(column);
    Object value = switch (column) {
      case 3 -> requestedTable;
      case 4 -> primaryColumn;
      case 5 -> Short.valueOf((short) 1);
      default -> null;
    };
    lastValueRead = true;
    lastWasNull = value == null;
    return value;
  }

  private void finishLocal() {
    completed = true;
    rowAvailable = false;
    primaryColumn = null;
    connection.metadataResultClosed(this);
  }

  private void requireRow(int column) throws SQLException {
    requireOpen();
    if (!rowAvailable || column <= 0 || column > METADATA.getColumnCount()) {
      throw JdbcExceptions.invalid("primary-key metadata row or field is unavailable");
    }
  }

  private void requireOpen() throws SQLException {
    if (closed) {
      throw JdbcExceptions.closed("primary-key metadata result set");
    }
  }
}
