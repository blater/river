package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RowResult;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

/** Bounded JDBC index rows backed by River's durable index catalog. */
final class RiverIndexInfoResultSet extends AbstractResultSet {
  private static final int MAXIMUM_INDEXES = 5;
  private static final String TABLE = "TABLE";
  private static final String[] COLUMN_NAMES = {
      "TABLE_CAT",
      "TABLE_SCHEM",
      "TABLE_NAME",
      "NON_UNIQUE",
      "INDEX_QUALIFIER",
      "INDEX_NAME",
      "TYPE",
      "ORDINAL_POSITION",
      "COLUMN_NAME",
      "ASC_OR_DESC",
      "CARDINALITY",
      "PAGES",
      "FILTER_CONDITION"
  };
  private static final int[] COLUMN_TYPES = {
      Types.VARCHAR,
      Types.VARCHAR,
      Types.VARCHAR,
      Types.BOOLEAN,
      Types.VARCHAR,
      Types.VARCHAR,
      Types.SMALLINT,
      Types.SMALLINT,
      Types.VARCHAR,
      Types.VARCHAR,
      Types.BIGINT,
      Types.BIGINT,
      Types.VARCHAR
  };
  private static final int[] COLUMN_NULLABILITY = {
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNoNulls,
      ResultSetMetaData.columnNoNulls,
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNoNulls,
      ResultSetMetaData.columnNoNulls,
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNoNulls,
      ResultSetMetaData.columnNoNulls,
      ResultSetMetaData.columnNullable
  };
  private static final int[] COLUMN_WIDTHS = {
      64, 64, 64, 5, 64, 64, 5, 5, 64, 1, 20, 20, 64
  };
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
  private final char[] text = new char[CommandResult.MAXIMUM_TEXT_CHARACTERS];
  private final String[] indexNames = new String[MAXIMUM_INDEXES];
  private final String[] columnNames = new String[MAXIMUM_INDEXES];
  private final boolean[] uniqueIndexes = new boolean[MAXIMUM_INDEXES];
  private final String requestedTable;
  private final boolean uniqueOnly;
  private RiverQuery query;
  private int indexCount;
  private int indexPosition;
  private int rowNumber;
  private boolean catalogResolved;
  private boolean indexesLoaded;
  private boolean rowAvailable;
  private boolean completed;
  private boolean closed;
  private boolean lastValueRead;
  private boolean lastWasNull;

  RiverIndexInfoResultSet(
      RiverJdbcConnection owner,
      RiverQuery catalogQuery,
      String tableName,
      boolean requireUnique) {
    connection = owner;
    query = catalogQuery;
    requestedTable = tableName;
    uniqueOnly = requireUnique;
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
    if (!catalogResolved) {
      resolveCatalogTable();
    }
    if (!indexesLoaded) {
      loadIndexes();
    }
    if (indexPosition >= indexCount) {
      finishLocal();
      return false;
    }
    indexPosition++;
    rowNumber++;
    rowAvailable = true;
    return true;
  }

  @Override
  public void close() throws SQLException {
    if (closed) {
      return;
    }
    if (query != null && query.isActive()) {
      closeQuery("close index metadata");
    }
    closed = true;
    completed = true;
    rowAvailable = false;
    releaseRows();
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
  public boolean getBoolean(int column) throws SQLException {
    Object value = readValue(column);
    if (value == null) {
      return false;
    }
    if (value instanceof Boolean result) {
      return result;
    }
    throw JdbcExceptions.invalid("index metadata field is not boolean");
  }

  @Override
  public boolean getBoolean(String label) throws SQLException {
    return getBoolean(findColumn(label));
  }

  @Override
  public short getShort(int column) throws SQLException {
    Object value = readValue(column);
    if (value == null) {
      return 0;
    }
    if (value instanceof Number result) {
      return result.shortValue();
    }
    throw JdbcExceptions.invalid("index metadata field is not a short");
  }

  @Override
  public short getShort(String label) throws SQLException {
    return getShort(findColumn(label));
  }

  @Override
  public int getInt(int column) throws SQLException {
    Object value = readValue(column);
    if (value == null) {
      return 0;
    }
    if (value instanceof Number result) {
      return result.intValue();
    }
    throw JdbcExceptions.invalid("index metadata field is not numeric");
  }

  @Override
  public int getInt(String label) throws SQLException {
    return getInt(findColumn(label));
  }

  @Override
  public long getLong(int column) throws SQLException {
    Object value = readValue(column);
    if (value == null) {
      return 0;
    }
    if (value instanceof Number result) {
      return result.longValue();
    }
    throw JdbcExceptions.invalid("index metadata field is not numeric");
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
      throw JdbcExceptions.invalid("no index metadata value has been read");
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

  private void resolveCatalogTable() throws SQLException {
    boolean tableFound = false;
    while (query != null && query.isActive()) {
      source.reset();
      JdbcExceptions.require(query.next(source), "fetch index catalog");
      if (!source.isAvailable()) {
        break;
      }
      int nameLength = source.copyTextAt(0, catalogName, 0);
      int typeLength = source.copyTextAt(1, catalogType, 0);
      if (nameLength < 0 || typeLength < 0) {
        throw JdbcExceptions.failure(
            StatusCode.INVARIANT_BROKEN,
            "decode index catalog");
      }
      if (RiverCatalogResultSet.equals(catalogName, nameLength, requestedTable)
          && RiverCatalogResultSet.equals(catalogType, typeLength, TABLE)) {
        tableFound = true;
      }
    }
    if (query != null && query.isActive()) {
      closeQuery("close index catalog");
    }
    if (tableFound) {
      query = connection.openIndexDescription(requestedTable);
    }
    catalogResolved = true;
  }

  private void loadIndexes() throws SQLException {
    while (query != null && query.isActive()) {
      source.reset();
      JdbcExceptions.require(query.next(source), "fetch table indexes");
      if (!source.isAvailable()) {
        closeQuery("close table indexes");
        break;
      }
      boolean unique = source.valueAt(2) != 0;
      if (uniqueOnly && !unique) {
        continue;
      }
      if (indexCount >= MAXIMUM_INDEXES) {
        throw JdbcExceptions.failure(
            StatusCode.INVARIANT_BROKEN,
            "decode table index count");
      }
      indexNames[indexCount] = source.isNull(0) ? null : copyText(0);
      columnNames[indexCount] = copyText(1);
      uniqueIndexes[indexCount] = unique;
      indexCount++;
    }
    sortIndexes();
    indexesLoaded = true;
  }

  private String copyText(int column) throws SQLException {
    int length = source.copyTextAt(column, text, 0);
    if (length < 0) {
      throw JdbcExceptions.failure(
          StatusCode.INVARIANT_BROKEN,
          "decode table index text");
    }
    return new String(text, 0, length);
  }

  private void sortIndexes() {
    for (int start = indexCount / 2 - 1; start >= 0; start--) {
      siftDown(start, indexCount);
    }
    for (int end = indexCount - 1; end > 0; end--) {
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
    int uniqueness = Boolean.compare(uniqueIndexes[right], uniqueIndexes[left]);
    if (uniqueness != 0) {
      return uniqueness;
    }
    String leftName = indexNames[left];
    String rightName = indexNames[right];
    return leftName == null
        ? rightName == null ? 0 : -1
        : rightName == null ? 1 : leftName.compareTo(rightName);
  }

  private void swap(int left, int right) {
    String indexName = indexNames[left];
    indexNames[left] = indexNames[right];
    indexNames[right] = indexName;
    String columnName = columnNames[left];
    columnNames[left] = columnNames[right];
    columnNames[right] = columnName;
    boolean unique = uniqueIndexes[left];
    uniqueIndexes[left] = uniqueIndexes[right];
    uniqueIndexes[right] = unique;
  }

  private Object readValue(int column) throws SQLException {
    requireRow(column);
    int index = indexPosition - 1;
    Object value = switch (column) {
      case 3 -> requestedTable;
      case 4 -> !uniqueIndexes[index];
      case 6 -> indexNames[index];
      case 7 -> Short.valueOf(DatabaseMetaData.tableIndexOther);
      case 8 -> Short.valueOf((short) 1);
      case 9 -> columnNames[index];
      case 10 -> "A";
      case 11, 12 -> Long.valueOf(0);
      default -> null;
    };
    lastValueRead = true;
    lastWasNull = value == null;
    return value;
  }

  private void closeQuery(String operation) throws SQLException {
    completion.reset();
    JdbcExceptions.require(query.close(completion), operation);
    query = null;
    connection.metadataQueryClosed(completion);
  }

  private void finishLocal() {
    completed = true;
    rowAvailable = false;
    releaseRows();
    connection.metadataResultClosed(this);
  }

  private void releaseRows() {
    for (int index = 0; index < indexCount; index++) {
      indexNames[index] = null;
      columnNames[index] = null;
    }
  }

  private void requireRow(int column) throws SQLException {
    requireOpen();
    if (!rowAvailable || column <= 0 || column > METADATA.getColumnCount()) {
      throw JdbcExceptions.invalid("index metadata row or field is unavailable");
    }
  }

  private void requireOpen() throws SQLException {
    if (closed) {
      throw JdbcExceptions.closed("index metadata result set");
    }
  }
}
