package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RowResult;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

/** JDBC column rows resolved through River's SQL binder without reading table rows. */
final class RiverColumnsResultSet extends AbstractResultSet {
  private static final int INITIAL_RELATION_CAPACITY = 16;
  private static final int MAXIMUM_RELATIONS = 32_767;
  private static final int MAXIMUM_COLUMNS = 8;
  private static final String[] COLUMN_NAMES = {
      "TABLE_CAT",
      "TABLE_SCHEM",
      "TABLE_NAME",
      "COLUMN_NAME",
      "DATA_TYPE",
      "TYPE_NAME",
      "COLUMN_SIZE",
      "BUFFER_LENGTH",
      "DECIMAL_DIGITS",
      "NUM_PREC_RADIX",
      "NULLABLE",
      "REMARKS",
      "COLUMN_DEF",
      "SQL_DATA_TYPE",
      "SQL_DATETIME_SUB",
      "CHAR_OCTET_LENGTH",
      "ORDINAL_POSITION",
      "IS_NULLABLE",
      "SCOPE_CATALOG",
      "SCOPE_SCHEMA",
      "SCOPE_TABLE",
      "SOURCE_DATA_TYPE",
      "IS_AUTOINCREMENT",
      "IS_GENERATEDCOLUMN"
  };
  private static final int[] COLUMN_TYPES = {
      Types.VARCHAR,
      Types.VARCHAR,
      Types.VARCHAR,
      Types.VARCHAR,
      Types.INTEGER,
      Types.VARCHAR,
      Types.INTEGER,
      Types.INTEGER,
      Types.INTEGER,
      Types.INTEGER,
      Types.INTEGER,
      Types.VARCHAR,
      Types.VARCHAR,
      Types.INTEGER,
      Types.INTEGER,
      Types.INTEGER,
      Types.INTEGER,
      Types.VARCHAR,
      Types.VARCHAR,
      Types.VARCHAR,
      Types.VARCHAR,
      Types.SMALLINT,
      Types.VARCHAR,
      Types.VARCHAR
  };
  private static final int[] COLUMN_NULLABILITY = {
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNoNulls,
      ResultSetMetaData.columnNoNulls,
      ResultSetMetaData.columnNoNulls,
      ResultSetMetaData.columnNoNulls,
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNoNulls,
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNoNulls,
      ResultSetMetaData.columnNoNulls,
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNullable,
      ResultSetMetaData.columnNoNulls,
      ResultSetMetaData.columnNoNulls
  };
  private static final int[] COLUMN_WIDTHS = {
      64, 64, 64, 64, 10, 7, 10, 10, 10, 10, 10, 64,
      64, 10, 10, 10, 10, 3, 64, 64, 64, 5, 3, 3
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
  private final char[] relationCharacters =
      new char[CommandResult.MAXIMUM_TEXT_CHARACTERS];
  private final String[] currentColumnNames = new String[MAXIMUM_COLUMNS];
  private final int[] currentTypeDescriptors = new int[MAXIMUM_COLUMNS];
  private final String tablePattern;
  private final String columnPattern;
  private RiverQuery query;
  private String[] relationNames = new String[INITIAL_RELATION_CAPACITY];
  private String currentRelation;
  private int relationCount;
  private int relationIndex;
  private int currentColumnCount;
  private int currentColumn = -1;
  private int rowNumber;
  private boolean relationsLoaded;
  private boolean rowAvailable;
  private boolean completed;
  private boolean closed;
  private boolean lastValueRead;
  private boolean lastWasNull;

  RiverColumnsResultSet(
      RiverJdbcConnection owner,
      RiverQuery catalogQuery,
      String tableNamePattern,
      String columnNamePattern) {
    connection = owner;
    query = catalogQuery;
    tablePattern = tableNamePattern;
    columnPattern = columnNamePattern;
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
    if (!relationsLoaded) {
      loadRelations();
    }
    while (true) {
      currentColumn++;
      if (currentColumn < currentColumnCount) {
        if (RiverCatalogResultSet.matches(
            currentColumnNames[currentColumn],
            columnPattern)) {
          rowAvailable = true;
          rowNumber++;
          return true;
        }
      } else if (relationIndex < relationCount) {
        describeRelation(relationNames[relationIndex++]);
      } else {
        finishLocal();
        return false;
      }
    }
  }

  @Override
  public void close() throws SQLException {
    if (closed) {
      return;
    }
    if (query != null && query.isActive()) {
      closeQuery("close column metadata");
    }
    closed = true;
    completed = true;
    rowAvailable = false;
    releaseNames();
    connection.metadataResultClosed(this);
  }

  @Override
  public String getString(int column) throws SQLException {
    requireRow(column);
    lastValueRead = true;
    Object value = value(column);
    lastWasNull = value == null;
    return value == null ? null : value.toString();
  }

  @Override
  public String getString(String label) throws SQLException {
    return getString(findColumn(label));
  }

  @Override
  public int getInt(int column) throws SQLException {
    requireRow(column);
    lastValueRead = true;
    Object value = value(column);
    lastWasNull = value == null;
    if (value == null) {
      return 0;
    }
    if (value instanceof Integer number) {
      return number;
    }
    throw JdbcExceptions.invalid("column value is not numeric");
  }

  @Override
  public int getInt(String label) throws SQLException {
    return getInt(findColumn(label));
  }

  @Override
  public long getLong(int column) throws SQLException {
    return getInt(column);
  }

  @Override
  public long getLong(String label) throws SQLException {
    return getLong(findColumn(label));
  }

  @Override
  public Object getObject(int column) throws SQLException {
    requireRow(column);
    lastValueRead = true;
    Object value = value(column);
    lastWasNull = value == null;
    return value;
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
    Object value = getObject(column);
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
      throw JdbcExceptions.invalid("no column metadata value has been read");
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

  private void loadRelations() throws SQLException {
    while (query != null && query.isActive()) {
      source.reset();
      JdbcExceptions.require(query.next(source), "fetch column catalog");
      if (!source.isAvailable()) {
        closeQuery("close column catalog");
        break;
      }
      int length = source.copyTextAt(0, relationCharacters, 0);
      if (length < 0) {
        throw JdbcExceptions.failure(
            StatusCode.INVARIANT_BROKEN,
            "decode column catalog");
      }
      if (RiverCatalogResultSet.matches(relationCharacters, length, tablePattern)) {
        appendRelation(new String(relationCharacters, 0, length));
      }
    }
    sortRelations();
    relationsLoaded = true;
  }

  private void describeRelation(String relation) throws SQLException {
    currentRelation = relation;
    query = connection.openColumnDescription(relation);
    currentColumnCount = query.columnCount();
    if (currentColumnCount < 0 || currentColumnCount > MAXIMUM_COLUMNS) {
      throw JdbcExceptions.failure(
          StatusCode.INVARIANT_BROKEN,
          "decode column count");
    }
    for (int index = 0; index < currentColumnCount; index++) {
      CharSequence name = query.columnName(index);
      if (name == null || name.length() == 0) {
        throw JdbcExceptions.failure(
            StatusCode.INVARIANT_BROKEN,
            "decode column name");
      }
      currentColumnNames[index] = name.toString();
      int descriptor = query.columnTypeDescriptor(index);
      if (!SqlTypeDescriptor.isValid(descriptor)) {
        throw JdbcExceptions.failure(
            StatusCode.CORRUPTION,
            "decode column type descriptor");
      }
      currentTypeDescriptors[index] = descriptor;
    }
    closeQuery("close column description");
    currentColumn = -1;
  }

  private void closeQuery(String operation) throws SQLException {
    completion.reset();
    JdbcExceptions.require(query.close(completion), operation);
    query = null;
    connection.metadataQueryClosed(completion);
  }

  private void appendRelation(String relation) throws SQLException {
    if (relationCount >= MAXIMUM_RELATIONS) {
      throw JdbcExceptions.failure(
          StatusCode.RESOURCE_EXHAUSTED,
          "materialize column catalog");
    }
    if (relationCount >= relationNames.length) {
      int capacity = Math.min(MAXIMUM_RELATIONS, relationNames.length << 1);
      String[] expanded = new String[capacity];
      System.arraycopy(relationNames, 0, expanded, 0, relationCount);
      relationNames = expanded;
    }
    relationNames[relationCount++] = relation;
  }

  private void sortRelations() {
    for (int start = relationCount / 2 - 1; start >= 0; start--) {
      siftDown(start, relationCount);
    }
    for (int end = relationCount - 1; end > 0; end--) {
      String value = relationNames[0];
      relationNames[0] = relationNames[end];
      relationNames[end] = value;
      siftDown(0, end);
    }
  }

  private void siftDown(int root, int end) {
    int current = root;
    while (current * 2 + 1 < end) {
      int child = current * 2 + 1;
      if (child + 1 < end
          && relationNames[child].compareTo(relationNames[child + 1]) < 0) {
        child++;
      }
      if (relationNames[current].compareTo(relationNames[child]) >= 0) {
        return;
      }
      String value = relationNames[current];
      relationNames[current] = relationNames[child];
      relationNames[child] = value;
      current = child;
    }
  }

  private Object value(int column) {
    int descriptor = currentTypeDescriptors[currentColumn];
    boolean varchar = SqlTypeDescriptor.typeId(descriptor)
        == SqlTypeDescriptor.TYPE_ID_VARCHAR;
    return switch (column) {
      case 3 -> currentRelation;
      case 4 -> currentColumnNames[currentColumn];
      case 5 -> RiverResultSetMetaData.jdbcType(descriptor);
      case 6 -> RiverResultSetMetaData.typeName(descriptor);
      case 7 -> RiverResultSetMetaData.precision(descriptor);
      case 9 -> SqlTypeDescriptor.typeId(descriptor)
          == SqlTypeDescriptor.TYPE_ID_DECIMAL
              ? SqlTypeDescriptor.parameterTwo(descriptor) : null;
      case 10 -> SqlTypeDescriptor.comparisonFamily(descriptor)
          == SqlTypeDescriptor.COMPARISON_EXACT_NUMERIC ? 10 : null;
      case 11 -> ResultSetMetaData.columnNullableUnknown;
      case 16 -> varchar ? SqlTypeDescriptor.parameterOne(descriptor) : null;
      case 17 -> currentColumn + 1;
      case 18, 23 -> "";
      case 24 -> "NO";
      default -> null;
    };
  }

  private void finishLocal() {
    completed = true;
    rowAvailable = false;
    releaseNames();
    connection.metadataResultClosed(this);
  }

  private void releaseNames() {
    for (int index = 0; index < relationCount; index++) {
      relationNames[index] = null;
    }
    for (int index = 0; index < currentColumnCount; index++) {
      currentColumnNames[index] = null;
    }
    currentRelation = null;
  }

  private void requireRow(int column) throws SQLException {
    requireOpen();
    if (!rowAvailable || column <= 0 || column > METADATA.getColumnCount()) {
      throw JdbcExceptions.invalid("column metadata row or field is unavailable");
    }
  }

  private void requireOpen() throws SQLException {
    if (closed) {
      throw JdbcExceptions.closed("column metadata result set");
    }
  }
}
