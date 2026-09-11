package io.riverdb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RiverDriverCatalogTest {
  @Test
  void streamsLongCatalogNamesThroughJdbcAndReopens(@TempDir java.nio.file.Path root)
      throws SQLException {
    String tableName = "customer_account_transaction_history";
    String viewName = "customer_account_transaction_history_view";
    try (RiverDriverTestFixture fixture = RiverDriverTestFixture.open(root, 8)) {
      try (Connection connection = DriverManager.getConnection(fixture.url());
          Statement statement = connection.createStatement()) {
      assertEquals(
          0,
          statement.executeUpdate(
              "CREATE TABLE " + tableName
                  + " (id BIGINT PRIMARY KEY, value BIGINT, label VARCHAR(7))"));
      assertEquals(
          0,
          statement.executeUpdate(
              "CREATE VIEW " + viewName
                  + " AS SELECT label AS code, id FROM " + tableName));
      assertEquals(
          0,
          statement.executeUpdate(
              "CREATE UNIQUE INDEX transaction_value_idx ON "
                  + tableName + "(value)"));
      assertEquals(
          0,
          statement.executeUpdate(
              "CREATE INDEX transaction_label_idx ON "
                  + tableName + "(label)"));
      assertEquals(
          0,
          statement.executeUpdate(
              "CREATE TABLE alpha_catalog_table "
                  + "(id BIGINT PRIMARY KEY, value BIGINT)"));
      for (int index = 0; index < 20; index++) {
        assertEquals(
            0,
            statement.executeUpdate(
                "CREATE TABLE catalog_order_" + index
                    + " (id BIGINT PRIMARY KEY, value BIGINT)"));
      }
      boolean table = false;
      boolean view = false;
      try (ResultSet catalog = statement.executeQuery("SHOW TABLES")) {
        ResultSetMetaData metadata = catalog.getMetaData();
        assertEquals(2, metadata.getColumnCount());
        assertEquals("table_name", metadata.getColumnLabel(1));
        assertEquals("table_type", metadata.getColumnLabel(2));
        assertEquals(Types.VARCHAR, metadata.getColumnType(1));
        assertEquals(Types.VARCHAR, metadata.getColumnType(2));
        while (catalog.next()) {
          String name = catalog.getString("table_name");
          String type = catalog.getString("table_type");
          if (tableName.equals(name)) {
            table = "TABLE".equals(type);
          } else if (viewName.equals(name)) {
            view = "VIEW".equals(type);
          }
        }
      }
      assertTrue(table);
      assertTrue(view);

      DatabaseMetaData metadata = connection.getMetaData();
      assertEquals("\\", metadata.getSearchStringEscape());
      try (ResultSet types = metadata.getTableTypes()) {
        assertEquals(1, types.getMetaData().getColumnCount());
        assertEquals("TABLE_TYPE", types.getMetaData().getColumnLabel(1));
        assertTrue(types.next());
        assertEquals("TABLE", types.getString(1));
        assertTrue(types.next());
        assertEquals("VIEW", types.getString("TABLE_TYPE"));
        assertFalse(types.next());
      }
      try (ResultSet ordered = metadata.getTables(null, null, "%", null)) {
        String previousType = "";
        String previousName = "";
        int rows = 0;
        while (ordered.next()) {
          String type = ordered.getString("TABLE_TYPE");
          String name = ordered.getString("TABLE_NAME");
          int typeOrder = previousType.compareTo(type);
          assertTrue(typeOrder < 0 || typeOrder == 0 && previousName.compareTo(name) < 0);
          previousType = type;
          previousName = name;
          rows++;
        }
        assertEquals(23, rows);
        assertEquals("VIEW", previousType);
        assertEquals(viewName, previousName);
      }
      assertCatalogRows(
          metadata,
          null,
          "%",
          "%transaction_history%",
          null,
          new String[] {tableName, viewName},
          new String[] {"TABLE", "VIEW"});
      assertCatalogRows(
          metadata,
          null,
          null,
          "customer\\_account\\_transaction\\_history",
          new String[] {"TABLE"},
          new String[] {tableName},
          new String[] {"TABLE"});
      assertCatalogRows(
          metadata,
          "missing_catalog",
          null,
          "%",
          null,
          new String[0],
          new String[0]);
      assertCatalogRows(
          metadata,
          null,
          null,
          "%",
          new String[] {"SYSTEM TABLE"},
          new String[0],
          new String[0]);

      try (ResultSet columns = metadata.getColumns(
          null,
          null,
          "%transaction_history%",
          "%")) {
        ResultSetMetaData fields = columns.getMetaData();
        assertEquals(24, fields.getColumnCount());
        assertEquals("TABLE_NAME", fields.getColumnLabel(3));
        assertEquals("COLUMN_NAME", fields.getColumnLabel(4));
        assertEquals(Types.INTEGER, fields.getColumnType(5));
        assertEquals(ResultSetMetaData.columnNoNulls, fields.isNullable(4));
        assertColumnMetadata(columns, tableName, "id", Types.BIGINT, 1, false);
        assertColumnMetadata(columns, tableName, "value", Types.BIGINT, 2, true);
        assertColumnMetadata(columns, tableName, "label", Types.VARCHAR, 3, true);
        assertColumnMetadata(columns, viewName, "code", Types.VARCHAR, 1, true);
        assertColumnMetadata(columns, viewName, "id", Types.BIGINT, 2, false);
        assertFalse(columns.next());
      }
      try (ResultSet columns = metadata.getColumns(
          null,
          null,
          "customer\\_account\\_transaction\\_history\\_view",
          "c_de")) {
        assertColumnMetadata(columns, viewName, "code", Types.VARCHAR, 1, true);
        assertFalse(columns.next());
      }
      try (ResultSet columns = metadata.getColumns(
          "missing_catalog",
          null,
          "%",
          "%")) {
        assertFalse(columns.next());
      }
      try (ResultSet keys = metadata.getPrimaryKeys(null, null, tableName)) {
        ResultSetMetaData fields = keys.getMetaData();
        assertEquals(6, fields.getColumnCount());
        assertEquals("TABLE_NAME", fields.getColumnLabel(3));
        assertEquals("COLUMN_NAME", fields.getColumnLabel(4));
        assertEquals(Types.SMALLINT, fields.getColumnType(5));
        assertEquals(ResultSetMetaData.columnNoNulls, fields.isNullable(4));
        assertTrue(keys.next());
        assertNull(keys.getString("TABLE_CAT"));
        assertTrue(keys.wasNull());
        assertEquals(tableName, keys.getString("TABLE_NAME"));
        assertEquals("id", keys.getString("COLUMN_NAME"));
        assertEquals(1, keys.getShort("KEY_SEQ"));
        assertNull(keys.getString("PK_NAME"));
        assertTrue(keys.wasNull());
        assertFalse(keys.next());
      }
      try (ResultSet keys = metadata.getPrimaryKeys(null, null, viewName)) {
        assertFalse(keys.next());
      }
      try (ResultSet keys = metadata.getPrimaryKeys(
          "missing_catalog",
          null,
          tableName)) {
        assertFalse(keys.next());
      }
      try (ResultSet indexes = metadata.getIndexInfo(
          null,
          null,
          tableName,
          false,
          false)) {
        ResultSetMetaData fields = indexes.getMetaData();
        assertEquals(13, fields.getColumnCount());
        assertEquals("NON_UNIQUE", fields.getColumnLabel(4));
        assertEquals(Types.BOOLEAN, fields.getColumnType(4));
        assertEquals(Types.SMALLINT, fields.getColumnType(7));
        assertIndexMetadata(indexes, tableName, null, "id", false);
        assertIndexMetadata(
            indexes,
            tableName,
            "transaction_value_idx",
            "value",
            false);
        assertIndexMetadata(
            indexes,
            tableName,
            "transaction_label_idx",
            "label",
            true);
        assertFalse(indexes.next());
      }
      try (ResultSet indexes = metadata.getIndexInfo(
          "missing_catalog",
          null,
          tableName,
          false,
          false)) {
        assertFalse(indexes.next());
      }
      try (ResultSet indexes = metadata.getIndexInfo(
          null,
          null,
          tableName,
          true,
          true)) {
        assertIndexMetadata(indexes, tableName, null, "id", false);
        assertIndexMetadata(
            indexes,
            tableName,
            "transaction_value_idx",
            "value",
            false);
        assertFalse(indexes.next());
      }
      try (ResultSet indexes = metadata.getIndexInfo(
          null,
          null,
          viewName,
          false,
          false)) {
        assertFalse(indexes.next());
      }

      ResultSet held = metadata.getColumns(null, null, "%", "%");
      assertTrue(held.next());
      SQLException concurrentMetadata = assertThrows(
          SQLException.class,
          () -> metadata.getTables(null, null, "%", null));
      assertEquals("40001", concurrentMetadata.getSQLState());
      held.close();

      try (ResultSet longPattern = metadata.getTables(
          null, null, "x".repeat(257), null)) {
        assertFalse(longPattern.next());
      }
      try (ResultSet manyTypes = metadata.getTables(
          null, null, "%", new String[257])) {
        assertFalse(manyTypes.next());
      }
      try (ResultSet longColumnPattern = metadata.getColumns(
          null, null, "%", "x".repeat(257))) {
        assertFalse(longColumnPattern.next());
      }
      SQLException invalidPrimaryTable = assertThrows(
          SQLException.class,
          () -> metadata.getPrimaryKeys(null, null, ""));
      assertEquals("22000", invalidPrimaryTable.getSQLState());
      SQLException invalidIndexTable = assertThrows(
          SQLException.class,
          () -> metadata.getIndexInfo(null, null, "", false, false));
      assertEquals("22000", invalidIndexTable.getSQLState());

      connection.setAutoCommit(false);
      assertEquals(
          0,
          statement.executeUpdate(
              "CREATE TABLE uncommitted_catalog_table "
                  + "(id BIGINT PRIMARY KEY, value BIGINT)"));
      assertCatalogRows(
          metadata,
          null,
          null,
          "uncommitted%",
          null,
          new String[] {"uncommitted_catalog_table"},
          new String[] {"TABLE"});
      connection.rollback();
      assertCatalogRows(
          metadata,
          null,
          null,
          "uncommitted%",
          null,
          new String[0],
          new String[0]);
      ResultSet closedAtCommit = metadata.getColumns(null, null, "%", "%");
      assertTrue(closedAtCommit.next());
      connection.commit();
      assertTrue(closedAtCommit.isClosed());
      connection.setAutoCommit(true);
      }

      fixture.reopen();
      try (Connection connection = DriverManager.getConnection(fixture.url())) {
    assertCatalogRows(
        connection.getMetaData(),
        null,
        null,
        "%transaction_history%",
        null,
        new String[] {tableName, viewName},
        new String[] {"TABLE", "VIEW"});
    try (ResultSet columns = connection.getMetaData().getColumns(
        null,
        null,
        "customer\\_account\\_transaction\\_history\\_view",
        "%")) {
      assertColumnMetadata(columns, viewName, "code", Types.VARCHAR, 1, true);
      assertColumnMetadata(columns, viewName, "id", Types.BIGINT, 2, false);
      assertFalse(columns.next());
    }
    try (ResultSet keys = connection.getMetaData().getPrimaryKeys(
        null,
        null,
        tableName)) {
      assertTrue(keys.next());
      assertEquals("id", keys.getString("COLUMN_NAME"));
      assertFalse(keys.next());
    }
    try (ResultSet indexes = connection.getMetaData().getIndexInfo(
        null,
        null,
        tableName,
        false,
        false)) {
      assertIndexMetadata(indexes, tableName, null, "id", false);
      assertIndexMetadata(
          indexes,
          tableName,
          "transaction_value_idx",
          "value",
          false);
      assertIndexMetadata(
          indexes,
          tableName,
          "transaction_label_idx",
          "label",
          true);
      assertFalse(indexes.next());
    }
    ResultSet owned = connection.getMetaData().getColumns(null, null, "%", "%");
    assertTrue(owned.next());
    assertFalse(owned.isClosed());
    connection.close();
    assertTrue(owned.isClosed());

      }
    }
  }

  private static void assertCatalogRows(
      DatabaseMetaData metadata,
      String catalog,
      String schema,
      String pattern,
      String[] types,
      String[] expectedNames,
      String[] expectedTypes) throws SQLException {
    boolean[] found = new boolean[expectedNames.length];
    int rows = 0;
    try (ResultSet tables = metadata.getTables(catalog, schema, pattern, types)) {
      ResultSetMetaData columns = tables.getMetaData();
      assertEquals(10, columns.getColumnCount());
      assertEquals("TABLE_CAT", columns.getColumnLabel(1));
      assertEquals("TABLE_NAME", columns.getColumnLabel(3));
      assertEquals("TABLE_TYPE", columns.getColumnLabel(4));
      assertEquals(ResultSetMetaData.columnNoNulls, columns.isNullable(3));
      assertEquals(ResultSetMetaData.columnNullable, columns.isNullable(5));
      while (tables.next()) {
        rows++;
        assertNull(tables.getString("TABLE_CAT"));
        assertTrue(tables.wasNull());
        assertNull(tables.getString("TABLE_SCHEM"));
        String name = tables.getString("TABLE_NAME");
        String type = tables.getString("TABLE_TYPE");
        assertNull(tables.getObject("REMARKS"));
        assertTrue(tables.wasNull());
        for (int index = 0; index < expectedNames.length; index++) {
          if (expectedNames[index].equals(name) && expectedTypes[index].equals(type)) {
            found[index] = true;
          }
        }
      }
    }
    assertEquals(expectedNames.length, rows);
    for (boolean value : found) {
      assertTrue(value);
    }
  }

  private static void assertColumnMetadata(
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

  private static void assertIndexMetadata(
      ResultSet indexes,
      String table,
      String index,
      String column,
      boolean nonUnique) throws SQLException {
    assertTrue(indexes.next());
    assertEquals(table, indexes.getString("TABLE_NAME"));
    assertEquals(nonUnique, indexes.getBoolean("NON_UNIQUE"));
    assertEquals(index, indexes.getString("INDEX_NAME"));
    assertEquals(index == null, indexes.wasNull());
    assertEquals(DatabaseMetaData.tableIndexOther, indexes.getShort("TYPE"));
    assertEquals(1, indexes.getShort("ORDINAL_POSITION"));
    assertEquals(column, indexes.getString("COLUMN_NAME"));
    assertEquals("A", indexes.getString("ASC_OR_DESC"));
    assertEquals(0, indexes.getLong("CARDINALITY"));
    assertEquals(0, indexes.getLong("PAGES"));
  }

}
