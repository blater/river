package io.riverdb.jdbc;

import io.riverdb.base.collection.BoundedArrayGrowth;
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
  private static final int INITIAL_TABLE_CAPACITY = 16;
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
  final RiverQuery query;
  final RowResult source = new RowResult();
  private final CommandResult completion = new CommandResult();
  final char[] tableNameCharacters =
      new char[CommandResult.MAXIMUM_TEXT_CHARACTERS];
  final char[] tableType = new char[CommandResult.MAXIMUM_TEXT_CHARACTERS];
  final String pattern;
  final boolean includeTables;
  final boolean includeViews;
  private final int mode;
  private String[] tableNames;
  private byte[] tableTypes;
  private String currentTableName;
  int tableNameLength;
  int tableTypeLength;
  private String currentTableType;
  int tableCount;
  private int tableIndex;
  private int rowNumber;
  private int typeIndex;
  private boolean rowAvailable;
  boolean tablesLoaded;
  boolean queryClosed;
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
    if (mode == TABLES) {
      tableNames = new String[INITIAL_TABLE_CAPACITY];
      tableTypes = new byte[INITIAL_TABLE_CAPACITY];
    }
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
    if (!tablesLoaded) {
      loadTables();
    }
    if (completed) {
      return false;
    }
    if (tableIndex >= tableCount) {
      finishLocal();
      return false;
    }
    currentTableName = tableNames[tableIndex];
    currentTableType = tableTypes[tableIndex++] == 0 ? TABLE : VIEW;
    rowAvailable = true;
    rowNumber++;
    return true;
  }

  private void loadTables() throws SQLException {
    RiverCatalogTableLoader.load(this);
  }

  @Override
  public void close() throws SQLException {
    if (closed) {
      return;
    }
    if (!queryClosed && query != null) {
      closeQuery(true);
    } else {
      connection.metadataResultClosed(this);
    }
    closed = true;
    rowAvailable = false;
    releaseTables();
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
      return currentTableName;
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

  void closeQuery(boolean completeResult) throws SQLException {
    completion.reset();
    JdbcExceptions.require(query.close(completion), "close table metadata");
    queryClosed = true;
    rowAvailable = false;
    if (completeResult) {
      completed = true;
      connection.metadataQueryCompleted(this, completion);
    } else {
      connection.metadataQueryClosed(completion);
    }
  }

  void finishLocal() {
    completed = true;
    rowAvailable = false;
    releaseTables();
    connection.metadataResultClosed(this);
  }

  void appendTable(byte type) throws SQLException {
    if (tableCount == Integer.MAX_VALUE) {
      throw JdbcExceptions.failure(
          StatusCode.RESOURCE_EXHAUSTED,
          "materialize table metadata");
    }
    if (tableCount >= tableNames.length) {
      int capacity = BoundedArrayGrowth.capacity(
          tableNames.length, tableCount + 1, Integer.MAX_VALUE, INITIAL_TABLE_CAPACITY);
      if (capacity < 0) {
        throw JdbcExceptions.failure(
            StatusCode.RESOURCE_EXHAUSTED,
            "materialize table metadata");
      }
      try {
        String[] expandedNames = new String[capacity];
        byte[] expandedTypes = new byte[capacity];
        System.arraycopy(tableNames, 0, expandedNames, 0, tableCount);
        System.arraycopy(tableTypes, 0, expandedTypes, 0, tableCount);
        tableNames = expandedNames;
        tableTypes = expandedTypes;
      } catch (OutOfMemoryError failure) {
        throw JdbcExceptions.failure(
            StatusCode.RESOURCE_EXHAUSTED,
            "materialize table metadata");
      }
    }
    tableNames[tableCount] = new String(tableNameCharacters, 0, tableNameLength);
    tableTypes[tableCount] = type;
    tableCount++;
  }

  void sortTables() {
    for (int start = tableCount / 2 - 1; start >= 0; start--) {
      siftDown(start, tableCount);
    }
    for (int end = tableCount - 1; end > 0; end--) {
      swap(0, end);
      siftDown(0, end);
    }
  }

  private void siftDown(int root, int end) {
    int current = root;
    while (current * 2 + 1 < end) {
      int child = current * 2 + 1;
      if (child + 1 < end && compare(child, child + 1) < 0) {
        child++;
      }
      if (compare(current, child) >= 0) {
        return;
      }
      swap(current, child);
      current = child;
    }
  }

  private int compare(int left, int right) {
    int typeComparison = Byte.compare(tableTypes[left], tableTypes[right]);
    return typeComparison != 0
        ? typeComparison : tableNames[left].compareTo(tableNames[right]);
  }

  private void swap(int left, int right) {
    String name = tableNames[left];
    tableNames[left] = tableNames[right];
    tableNames[right] = name;
    byte type = tableTypes[left];
    tableTypes[left] = tableTypes[right];
    tableTypes[right] = type;
  }

  private void releaseTables() {
    if (tableNames != null) {
      for (int index = 0; index < tableCount; index++) {
        tableNames[index] = null;
      }
    }
    currentTableName = null;
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

  static boolean equals(char[] value, int length, String expected) {
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

  static boolean matches(
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

  static boolean matches(String value, String candidatePattern) {
    int valueIndex = 0;
    int patternIndex = 0;
    int wildcardPattern = -1;
    int wildcardValue = -1;
    while (valueIndex < value.length()) {
      int tokenLength = literalTokenLength(candidatePattern, patternIndex);
      if (tokenLength > 0
          && literalToken(candidatePattern, patternIndex) == value.charAt(valueIndex)) {
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
