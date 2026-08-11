package io.riverdb.jdbc;

import io.riverdb.engine.api.RiverQuery;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;

/** Metadata for River's current bounded BIGINT and VARCHAR(7) projection. */
final class RiverResultSetMetaData implements ResultSetMetaData {
  private final String[] columnNames;
  private final boolean[] varcharColumns;
  private final int columnCount;

  RiverResultSetMetaData(RiverQuery query) throws SQLException {
    columnCount = query.columnCount();
    columnNames = new String[columnCount];
    varcharColumns = new boolean[columnCount];
    for (int index = 0; index < columnCount; index++) {
      varcharColumns[index] = query.columnIsVarchar(index);
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

  @Override
  public int getColumnCount() {
    return columnCount;
  }

  @Override
  public boolean isAutoIncrement(int column) throws SQLException {
    requireColumn(column);
    return false;
  }

  @Override
  public boolean isCaseSensitive(int column) throws SQLException {
    requireColumn(column);
    return varcharColumns[column - 1];
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
    return columnNoNulls;
  }

  @Override
  public boolean isSigned(int column) throws SQLException {
    requireColumn(column);
    return !varcharColumns[column - 1];
  }

  @Override
  public int getColumnDisplaySize(int column) throws SQLException {
    requireColumn(column);
    return varcharColumns[column - 1] ? 7 : 20;
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
    return varcharColumns[column - 1] ? 7 : 19;
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
    return varcharColumns[column - 1] ? Types.VARCHAR : Types.BIGINT;
  }

  @Override
  public String getColumnTypeName(int column) throws SQLException {
    requireColumn(column);
    return varcharColumns[column - 1] ? "VARCHAR" : "BIGINT";
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
    return varcharColumns[column - 1]
        ? String.class.getName() : Long.class.getName();
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
    return varcharColumns[column - 1];
  }
}
