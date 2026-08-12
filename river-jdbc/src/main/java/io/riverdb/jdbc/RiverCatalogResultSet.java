package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RowResult;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

/** Forward-only JDBC catalog rows backed by River's durable catalog scan. */
final class RiverCatalogResultSet extends AbstractResultSet {
  private static final int TABLES = 1;
  private static final int TABLE_TYPES = 2;
  private static final String TABLE = "TABLE";
  private static final String VIEW = "VIEW";
  private static final String[] TABLE_COLUMNS = {
      "TABLE_CAT",
      "TABLE_SCHEM",
      "TABLE_NAME",
      "TABLE_TYPE",
      "REMARKS",
      "TYPE_CAT",
      "TYPE_SCHEM",
      "TYPE_NAME",
      "SELF_REFERENCING_COL_NAME",
      "REF_GENERATION"
  };
  private static final boolean[] TABLE_COLUMN_TYPES = {
      true, true, true, true, true, true, true, true, true, true
  };
  private static final int[] TABLE_COLUMN_NULLABILITY = {
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNoNulls,
      ResultSetMetaData.columnNoNulls,
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNullable
  };
  private static final int[] TABLE_COLUMN_WIDTHS = {
      64, 64, 64, 5, 64, 64, 64, 64, 64, 64
  };
  private static final String[] TYPE_COLUMNS = {"TABLE_TYPE"};
  private static final boolean[] TYPE_COLUMN_TYPES = {true};
  private static final int[] TYPE_COLUMN_NULLABILITY = {
      ResultSetMetaData.columnNoNulls
  };
  private static final int[] TYPE_COLUMN_WIDTHS = {5};
  private static final RiverResultSetMetaData TABLE_METADATA =
      new RiverResultSetMetaData(
          TABLE_COLUMNS,
          TABLE_COLUMN_TYPES,
          TABLE_COLUMN_NULLABILITY,
          TABLE_COLUMN_WIDTHS);
  private static final RiverResultSetMetaData TYPE_METADATA =
      new RiverResultSetMetaData(
          TYPE_COLUMNS,
          TYPE_COLUMN_TYPES,
          TYPE_COLUMN_NULLABILITY,
          TYPE_COLUMN_WIDTHS);

  private final RiverJdbcConnection connection;
  private final RiverQuery query;
  private final RowResult source = new RowResult();
  private final CommandResult completion = new CommandResult();
  private final char[] tableName = new char[CommandResult.MAXIMUM_TEXT_CHARACTERS];
  private final char[] tableType = new char[CommandResult.MAXIMUM_TEXT_CHARACTERS];
  private final String pattern;
  private final boolean includeTables;
  private final boolean includeViews;
  private final int mode;
  private int tableNameLength;
  private int tableTypeLength;
  private String currentTableType;
  private int rowNumber;
  private int typeIndex;
  private boolean rowAvailable;
  private boolean completed;
  private boolean closed;
  private boolean lastValueRead;
  private boolean lastWasNull;

  private RiverCatalogResultSet(
      RiverJdbcConnection owner,
      RiverQuery catalogQuery,
      String tableNamePattern,
      boolean tables,
      boolean views,
      int resultMode) {
    connection = owner;
    query = catalogQuery;
    pattern = tableNamePattern;
    includeTables = tables;
    includeViews = views;
    mode = resultMode;
  }

  static RiverCatalogResultSet tables(
      RiverJdbcConnection owner,
      RiverQuery query,
      String pattern,
      boolean includeTables,
      boolean includeViews) {
    return new RiverCatalogResultSet(
        owner,
        query,
        pattern,
        includeTables,
        includeViews,
        TABLES);
  }

  static RiverCatalogResultSet tableTypes(RiverJdbcConnection owner) {
    return new RiverCatalogResultSet(
        owner,
        null,
        null,
        true,
        true,
        TABLE_TYPES);
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
    if (mode == TABLE_TYPES) {
      if (typeIndex >= 2) {
        finishLocal();
        return false;
      }
      currentTableType = typeIndex++ == 0 ? TABLE : VIEW;
      rowAvailable = true;
      rowNumber++;
      return true;
    }
    while (query != null) {
      source.reset();
      JdbcExceptions.require(query.next(source), "fetch table metadata");
      if (!source.isAvailable()) {
        completeQuery();
        return false;
      }
      tableNameLength = source.copyTextAt(0, tableName, 0);
      tableTypeLength = source.copyTextAt(1, tableType, 0);
      if (tableNameLength < 0 || tableTypeLength < 0) {
        throw JdbcExceptions.failure(
            StatusCode.INVARIANT_BROKEN,
            "decode table metadata");
      }
      boolean table = equals(tableType, tableTypeLength, TABLE);
      boolean view = equals(tableType, tableTypeLength, VIEW);
      if ((table && includeTables || view && includeViews)
          && matches(tableName, tableNameLength, pattern)) {
        currentTableType = table ? TABLE : VIEW;
        rowAvailable = true;
        rowNumber++;
        return true;
      }
    }
    finishLocal();
    return false;
  }

  @Override
  public void close() throws SQLException {
    if (closed) {
      return;
    }
    if (!completed && query != null) {
      completeQuery();
    } else {
      connection.metadataResultClosed(this);
    }
    closed = true;
    rowAvailable = false;
  }

  @Override
  public String getString(int column) throws SQLException {
    requireRow(column);
    lastValueRead = true;
    if (mode == TABLE_TYPES) {
      lastWasNull = false;
      return currentTableType;
    }
    if (column == 3) {
      lastWasNull = false;
      return new String(tableName, 0, tableNameLength);
    }
    if (column == 4) {
      lastWasNull = false;
      return currentTableType;
    }
    lastWasNull = true;
    return null;
  }

  @Override
  public String getString(String label) throws SQLException {
    return getString(findColumn(label));
  }

  @Override
  public Object getObject(int column) throws SQLException {
    return getString(column);
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
    if (type != String.class) {
      throw JdbcExceptions.unsupported();
    }
    return type.cast(getString(column));
  }

  @Override
  public <T> T getObject(String label, Class<T> type) throws SQLException {
    return getObject(findColumn(label), type);
  }

  @Override
  public boolean wasNull() throws SQLException {
    requireOpen();
    if (!rowAvailable || !lastValueRead) {
      throw JdbcExceptions.invalid("no catalog column value has been read");
    }
    return lastWasNull;
  }

  @Override
  public int findColumn(String label) throws SQLException {
    requireOpen();
    return metadata().findColumn(label);
  }

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    requireOpen();
    return metadata();
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
    return rowAvailable ? rowNumber : 0;
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

  private RiverResultSetMetaData metadata() {
    return mode == TABLES ? TABLE_METADATA : TYPE_METADATA;
  }

  private void completeQuery() throws SQLException {
    completion.reset();
    JdbcExceptions.require(query.close(completion), "close table metadata");
    completed = true;
    rowAvailable = false;
    connection.metadataQueryCompleted(this, completion);
  }

  private void finishLocal() {
    completed = true;
    rowAvailable = false;
    connection.metadataResultClosed(this);
  }

  private void requireRow(int column) throws SQLException {
    requireOpen();
    if (!rowAvailable || column <= 0 || column > metadata().getColumnCount()) {
      throw JdbcExceptions.invalid("catalog row or column is unavailable");
    }
  }

  private void requireOpen() throws SQLException {
    if (closed) {
      throw JdbcExceptions.closed("catalog result set");
    }
  }

  private static boolean equals(char[] value, int length, String expected) {
    if (length != expected.length()) {
      return false;
    }
    for (int index = 0; index < length; index++) {
      if (value[index] != expected.charAt(index)) {
        return false;
      }
    }
    return true;
  }

  private static boolean matches(
      char[] value,
      int length,
      String candidatePattern) {
    int valueIndex = 0;
    int patternIndex = 0;
    int wildcardPattern = -1;
    int wildcardValue = -1;
    while (valueIndex < length) {
      int tokenLength = literalTokenLength(candidatePattern, patternIndex);
      if (tokenLength > 0
          && literalToken(candidatePattern, patternIndex) == value[valueIndex]) {
        patternIndex += tokenLength;
        valueIndex++;
      } else if (patternIndex < candidatePattern.length()
          && candidatePattern.charAt(patternIndex) == '_') {
        patternIndex++;
        valueIndex++;
      } else if (patternIndex < candidatePattern.length()
          && candidatePattern.charAt(patternIndex) == '%') {
        wildcardPattern = ++patternIndex;
        wildcardValue = valueIndex;
      } else if (wildcardPattern >= 0) {
        patternIndex = wildcardPattern;
        valueIndex = ++wildcardValue;
      } else {
        return false;
      }
    }
    while (patternIndex < candidatePattern.length()
        && candidatePattern.charAt(patternIndex) == '%') {
      patternIndex++;
    }
    return patternIndex == candidatePattern.length();
  }

  private static int literalTokenLength(String pattern, int index) {
    if (index >= pattern.length()) {
      return 0;
    }
    char value = pattern.charAt(index);
    if (value == '%' || value == '_') {
      return 0;
    }
    return value == '\\' && index + 1 < pattern.length() ? 2 : 1;
  }

  private static char literalToken(String pattern, int index) {
    return pattern.charAt(index) == '\\' && index + 1 < pattern.length()
        ? pattern.charAt(index + 1) : pattern.charAt(index);
  }
}
