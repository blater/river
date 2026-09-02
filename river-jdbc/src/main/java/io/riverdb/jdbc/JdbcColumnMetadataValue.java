package io.riverdb.jdbc;

import io.riverdb.base.type.SqlTypeDescriptor;
import java.sql.ResultSetMetaData;

/** Maps one retained column description to the JDBC getColumns row shape. */
final class JdbcColumnMetadataValue {
  private JdbcColumnMetadataValue() { }

  static Object read(
      int column, String relation, JdbcColumnMetadataRows rows, int index) {
    int descriptor = rows.descriptor(index);
    boolean varchar = SqlTypeDescriptor.typeId(descriptor)
        == SqlTypeDescriptor.TYPE_ID_VARCHAR;
    return switch (column) {
      case 3 -> relation;
      case 4 -> rows.name(index);
      case 5 -> RiverJdbcTypeMetadata.jdbcType(descriptor);
      case 6 -> RiverJdbcTypeMetadata.typeName(descriptor);
      case 7 -> RiverJdbcTypeMetadata.precision(descriptor);
      case 9 -> SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_DECIMAL
          ? SqlTypeDescriptor.parameterTwo(descriptor) : null;
      case 10 -> SqlTypeDescriptor.comparisonFamily(descriptor)
          == SqlTypeDescriptor.COMPARISON_EXACT_NUMERIC ? 10 : null;
      case 11 -> rows.nullable(index)
          ? ResultSetMetaData.columnNullable : ResultSetMetaData.columnNoNulls;
      case 16 -> varchar ? SqlTypeDescriptor.parameterOne(descriptor) : null;
      case 17 -> index + 1;
      case 18 -> rows.nullable(index) ? "YES" : "NO";
      case 23 -> "";
      case 24 -> "NO";
      default -> null;
    };
  }
}
