package io.riverdb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;

/** Shared JDBC metadata row assertions for catalog and driver tests. */
final class JdbcMetadataAssertions {
  private JdbcMetadataAssertions() {}

  static void assertColumnMetadata(
      ResultSet columns,
      String table,
      String column,
      int type,
      int ordinal,
      boolean nullable) throws SQLException {
    assertTrue(columns.next());
    assertNull(columns.getString("TABLE_CAT"));
    assertTrue(columns.wasNull());
    assertNull(columns.getString("TABLE_SCHEM"));
    assertEquals(table, columns.getString("TABLE_NAME"));
    assertEquals(column, columns.getString("COLUMN_NAME"));
    assertEquals(type, columns.getInt("DATA_TYPE"));
    assertEquals(type == Types.VARCHAR ? "VARCHAR" : "BIGINT", columns.getString("TYPE_NAME"));
    assertEquals(type == Types.VARCHAR ? 7 : 19, columns.getInt("COLUMN_SIZE"));
    assertEquals(
        nullable ? ResultSetMetaData.columnNullable : ResultSetMetaData.columnNoNulls,
        columns.getInt("NULLABLE"));
    assertEquals(ordinal, columns.getInt("ORDINAL_POSITION"));
    assertEquals(nullable ? "YES" : "NO", columns.getString("IS_NULLABLE"));
    assertEquals("", columns.getString("IS_AUTOINCREMENT"));
    assertEquals("NO", columns.getString("IS_GENERATEDCOLUMN"));
  }
}
