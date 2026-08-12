package io.riverdb.jdbc;

import io.riverdb.engine.api.RiverQuery;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;

/** Metadata for River's bounded BIGINT and text result projection. */
final class RiverResultSetMetaData implements ResultSetMetaData {
  private final String[] columnNames;
  private final int[] columnTypes;
  private final int[] nullability;
  private final int[] displaySizes;
  private final boolean autoIncrement;
  private final int columnCount;

  RiverResultSetMetaData(RiverQuery query) throws SQLException {
    columnCount = query.columnCount();
    columnNames = new String[columnCount];
    columnTypes = new int[columnCount];
    nullability = new int[columnCount];
    displaySizes = new int[columnCount];
    autoIncrement = false;
    for (int index = 0; index < columnCount; index++) {
      columnTypes[index] = query.columnIsVarchar(index) ? Types.VARCHAR : Types.BIGINT;
      nullability[index] = columnNoNulls;
      displaySizes[index] = columnTypes[index] == Types.VARCHAR ? 7 : 20;
      CharSequence name = query.columnName(index);
      if (name == null || name.length() <= 0) {
        throw JdbcExceptions.invalid("query column name is missing");
      }
      if (name instanceof String text) {
        columnNames[index] = text;
      } else {
        char[] characters = new char[name.length()];
        for (int character = 0; character < name.length(); character++) {
          characters[character] = name.charAt(character);
        }
        columnNames[index] = new String(characters);
      }
    }
  }

  RiverResultSetMetaData(String columnName, boolean generated) {
    columnCount = 1;
    columnNames = new String[] {columnName};
    columnTypes = new int[] {Types.BIGINT};
    nullability = new int[] {columnNoNulls};
    displaySizes = new int[] {20};
    autoIncrement = generated;
  }

  RiverResultSetMetaData(
      String[] names,
      boolean[] varchar,
      int[] nullable,
      int[] widths) {
    columnCount = names.length;
    columnNames = names;
    columnTypes = new int[varchar.length];
    for (int index = 0; index < varchar.length; index++) {
      columnTypes[index] = varchar[index] ? Types.VARCHAR : Types.BIGINT;
    }
    nullability = nullable;
    displaySizes = widths;
    autoIncrement = false;
  }

  RiverResultSetMetaData(
      String[] names,
      int[] types,
      int[] nullable,
      int[] widths) {
    columnCount = names.length;
    columnNames = names;
    columnTypes = types;
    nullability = nullable;
    displaySizes = widths;
    autoIncrement = false;
  }

  @Override
  public int getColumnCount() {
    return columnCount;
  }

  @Override
  public boolean isAutoIncrement(int column) throws SQLException {
    requireColumn(column);
    return autoIncrement;
  }

  @Override
  public boolean isCaseSensitive(int column) throws SQLException {
    requireColumn(column);
    return columnTypes[column - 1] == Types.VARCHAR;
  }

  @Override
  public boolean isSearchable(int column) throws SQLException {
    requireColumn(column);
    return true;
  }

  @Override
  public boolean isCurrency(int column) throws SQLException {
    requireColumn(column);
    return false;
  }

  @Override
  public int isNullable(int column) throws SQLException {
    requireColumn(column);
    return nullability[column - 1];
  }

  @Override
  public boolean isSigned(int column) throws SQLException {
    requireColumn(column);
    return numeric(columnTypes[column - 1]);
  }

  @Override
  public int getColumnDisplaySize(int column) throws SQLException {
    requireColumn(column);
    return displaySizes[column - 1];
  }

  @Override
  public String getColumnLabel(int column) throws SQLException {
    requireColumn(column);
    return columnNames[column - 1];
  }

  @Override
  public String getColumnName(int column) throws SQLException {
    return getColumnLabel(column);
  }

  @Override
  public String getSchemaName(int column) throws SQLException {
    requireColumn(column);
    return "";
  }

  @Override
  public int getPrecision(int column) throws SQLException {
    requireColumn(column);
    return switch (columnTypes[column - 1]) {
      case Types.VARCHAR -> displaySizes[column - 1];
      case Types.SMALLINT -> 5;
      case Types.INTEGER -> 10;
      default -> 19;
    };
  }

  @Override
  public int getScale(int column) throws SQLException {
    requireColumn(column);
    return 0;
  }

  @Override
  public String getTableName(int column) throws SQLException {
    requireColumn(column);
    return "";
  }

  @Override
  public String getCatalogName(int column) throws SQLException {
    requireColumn(column);
    return "";
  }

  @Override
  public int getColumnType(int column) throws SQLException {
    requireColumn(column);
    return columnTypes[column - 1];
  }

  @Override
  public String getColumnTypeName(int column) throws SQLException {
    requireColumn(column);
    return switch (columnTypes[column - 1]) {
      case Types.VARCHAR -> "VARCHAR";
      case Types.SMALLINT -> "SMALLINT";
      case Types.INTEGER -> "INTEGER";
      default -> "BIGINT";
    };
  }

  @Override
  public boolean isReadOnly(int column) throws SQLException {
    requireColumn(column);
    return true;
  }

  @Override
  public boolean isWritable(int column) throws SQLException {
    requireColumn(column);
    return false;
  }

  @Override
  public boolean isDefinitelyWritable(int column) throws SQLException {
    requireColumn(column);
    return false;
  }

  @Override
  public String getColumnClassName(int column) throws SQLException {
    requireColumn(column);
    return switch (columnTypes[column - 1]) {
      case Types.VARCHAR -> String.class.getName();
      case Types.SMALLINT -> Short.class.getName();
      case Types.INTEGER -> Integer.class.getName();
      default -> Long.class.getName();
    };
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

  private void requireColumn(int column) throws SQLException {
    if (column <= 0 || column > columnCount) {
      throw JdbcExceptions.invalid("column index is out of range");
    }
  }

  int findColumn(String label) throws SQLException {
    if (label != null) {
      for (int index = 0; index < columnNames.length; index++) {
        if (columnNames[index].equalsIgnoreCase(label)) {
          return index + 1;
        }
      }
    }
    throw JdbcExceptions.invalid("column label is not in the result projection");
  }

  boolean isVarchar(int column) throws SQLException {
    requireColumn(column);
    return columnTypes[column - 1] == Types.VARCHAR;
  }

  private static boolean numeric(int type) {
    return type == Types.BIGINT || type == Types.INTEGER || type == Types.SMALLINT;
  }
}
