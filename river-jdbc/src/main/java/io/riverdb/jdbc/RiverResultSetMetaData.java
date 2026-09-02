package io.riverdb.jdbc;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.RiverQuery;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;

/** Metadata for River's bounded typed result projection. */
final class RiverResultSetMetaData implements ResultSetMetaData {
  private final String[] columnNames;
  private final int[] columnTypes;
  private final int[] typeDescriptors;
  private final int[] nullability;
  private final int[] displaySizes;
  private final boolean autoIncrement;
  private final int columnCount;

  RiverResultSetMetaData(RiverQuery query) throws SQLException {
    columnCount = query.columnCount();
    columnNames = new String[columnCount];
    columnTypes = new int[columnCount];
    typeDescriptors = new int[columnCount];
    nullability = new int[columnCount];
    displaySizes = new int[columnCount];
    autoIncrement = false;
    for (int index = 0; index < columnCount; index++) {
      int descriptor = query.columnTypeDescriptor(index);
      if (!SqlTypeDescriptor.isValid(descriptor)) {
        throw JdbcExceptions.invalid("query column type descriptor is invalid");
      }
      typeDescriptors[index] = descriptor;
      columnTypes[index] = RiverJdbcTypeMetadata.jdbcType(descriptor);
      nullability[index] = query.columnIsNullable(index)
          ? columnNullable : columnNoNulls;
      displaySizes[index] = RiverJdbcTypeMetadata.displaySize(descriptor);
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
    typeDescriptors = new int[] {SqlTypeDescriptor.BIGINT};
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
    typeDescriptors = new int[varchar.length];
    for (int index = 0; index < varchar.length; index++) {
      columnTypes[index] = varchar[index] ? Types.VARCHAR : Types.BIGINT;
      typeDescriptors[index] = varchar[index]
          ? SqlTypeDescriptor.varchar(widths[index]) : SqlTypeDescriptor.BIGINT;
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
    typeDescriptors = new int[types.length];
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
    return RiverJdbcTypeMetadata.numeric(columnTypes[column - 1]);
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
    int descriptor = typeDescriptors[column - 1];
    if (SqlTypeDescriptor.isValid(descriptor)) {
      return RiverJdbcTypeMetadata.precision(descriptor);
    }
    return switch (columnTypes[column - 1]) {
      case Types.VARCHAR -> displaySizes[column - 1];
      case Types.BOOLEAN -> 1;
      case Types.SMALLINT -> 5;
      case Types.INTEGER -> 10;
      default -> 19;
    };
  }

  @Override
  public int getScale(int column) throws SQLException {
    requireColumn(column);
    int descriptor = typeDescriptors[column - 1];
    if (!SqlTypeDescriptor.isValid(descriptor)) return 0;
    int type = SqlTypeDescriptor.typeId(descriptor);
    if (type == SqlTypeDescriptor.TYPE_ID_DECIMAL) {
      return SqlTypeDescriptor.parameterTwo(descriptor);
    }
    return type == SqlTypeDescriptor.TYPE_ID_TIME
            || type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
            || type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE
        ? SqlTypeDescriptor.parameterOne(descriptor) : 0;
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
    int descriptor = typeDescriptors[column - 1];
    if (SqlTypeDescriptor.isValid(descriptor)) {
      return RiverJdbcTypeMetadata.typeName(descriptor);
    }
    return switch (columnTypes[column - 1]) {
      case Types.VARCHAR -> "VARCHAR";
      case Types.BOOLEAN -> "BOOLEAN";
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
      case Types.BOOLEAN -> Boolean.class.getName();
      case Types.DECIMAL -> java.math.BigDecimal.class.getName();
      case Types.REAL -> Float.class.getName();
      case Types.FLOAT, Types.DOUBLE -> Double.class.getName();
      case Types.DATE -> java.time.LocalDate.class.getName();
      case Types.TIME -> java.time.LocalTime.class.getName();
      case Types.TIMESTAMP -> java.time.LocalDateTime.class.getName();
      case Types.TIMESTAMP_WITH_TIMEZONE -> java.time.OffsetDateTime.class.getName();
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

  boolean isBoolean(int column) throws SQLException {
    requireColumn(column);
    return columnTypes[column - 1] == Types.BOOLEAN;
  }

  boolean isDecimal(int column) throws SQLException {
    requireColumn(column);
    return columnTypes[column - 1] == Types.DECIMAL;
  }

  boolean isApproximate(int column) throws SQLException {
    requireColumn(column);
    int type = columnTypes[column - 1];
    return type == Types.REAL || type == Types.FLOAT || type == Types.DOUBLE;
  }

  int decimalScale(int column) throws SQLException {
    requireColumn(column);
    return SqlTypeDescriptor.parameterTwo(typeDescriptors[column - 1]);
  }

  int typeDescriptor(int column) throws SQLException {
    requireColumn(column);
    return typeDescriptors[column - 1];
  }

}
