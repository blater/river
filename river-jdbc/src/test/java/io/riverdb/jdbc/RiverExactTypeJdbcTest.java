package io.riverdb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.EmbeddedRiver;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.LoopbackServerOpenResult;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.function.Executable;

final class RiverExactTypeJdbcTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4a44424345584143L, 0x5454595045303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void bindsAndReturnsBooleanAndScaledBigDecimal(@TempDir Path root) throws Exception {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackServerOpenResult listener = new LoopbackServerOpenResult();
    assertEquals(StatusCode.OK, LoopbackRiverServer.start(database, 0, listener));
    LoopbackRiverServer server = listener.server();

    try (Connection connection = DriverManager.getConnection(
        RiverDriver.URL_PREFIX + server.port())) {
      try (Statement schema = connection.createStatement()) {
        assertEquals(0, schema.executeUpdate(
            "CREATE TABLE invoices (id BIGINT PRIMARY KEY, paid BOOLEAN, "
                + "amount DECIMAL(8,2))"));
      }
      try (PreparedStatement insert = connection.prepareStatement(
          "INSERT INTO invoices VALUES (?, ?, ?)")) {
        insert.setLong(1, 1);
        insert.setBoolean(2, true);
        insert.setBigDecimal(3, new BigDecimal("42.7"));
        assertEquals(1, insert.executeUpdate());
        insert.setLong(1, 2);
        insert.setBoolean(2, false);
        insert.setBigDecimal(3, null);
        assertEquals(1, insert.executeUpdate());
        insert.setLong(1, 3);
        insert.setBigDecimal(3, new BigDecimal("42.701"));
        assertEquals(1, insert.executeUpdate());
      }
      try (PreparedStatement select = connection.prepareStatement(
          "SELECT paid, amount FROM invoices WHERE paid=? AND amount=?")) {
        select.setBoolean(1, true);
        select.setBigDecimal(2, new BigDecimal("42.70"));
        try (ResultSet rows = select.executeQuery()) {
          ResultSetMetaData metadata = rows.getMetaData();
          assertEquals(Types.BOOLEAN, metadata.getColumnType(1));
          assertEquals(Types.DECIMAL, metadata.getColumnType(2));
          assertEquals(8, metadata.getPrecision(2));
          assertEquals(2, metadata.getScale(2));
          assertTrue(rows.next());
          assertTrue(rows.getBoolean(1));
          assertTrue(rows.getBoolean("paid"));
          assertEquals((byte) 1, rows.getByte("paid"));
          assertEquals((short) 1, rows.getShort("paid"));
          assertEquals(1, rows.getInt("paid"));
          assertEquals(1L, rows.getLong("paid"));
          assertEquals(1.0F, rows.getFloat("paid"));
          assertEquals(1.0D, rows.getDouble("paid"));
          assertEquals(BigDecimal.ONE, rows.getBigDecimal("paid"));
          assertEquals(Boolean.TRUE, rows.getObject(1));
          assertEquals("true", rows.getObject(1, String.class));
          assertEquals(new BigDecimal("42.70"), rows.getBigDecimal(2));
          assertEquals(new BigDecimal("42.70"), rows.getObject(2));
          assertEquals(new BigDecimal("42.70"), rows.getObject(2, BigDecimal.class));
          assertEquals("42.70", rows.getString(2));
          assertEquals("42.70", rows.getObject(2, String.class));
          assertTrue(rows.getBoolean(2));
          assertNumericOverflow(() -> rows.getByte(2));
          assertNumericOverflow(() -> rows.getShort(2));
          assertNumericOverflow(() -> rows.getInt(2));
          assertNumericOverflow(() -> rows.getLong(2));
          assertEquals(42.7F, rows.getFloat(2), 0.0001F);
          assertEquals(42.7D, rows.getDouble(2), 0.0001D);
          assertUnsupported(() -> rows.getObject(2, Long.class));
          assertUnsupported(() -> rows.getObject(2, Integer.class));
          assertNumericOverflow(() -> rows.getLong("amount"));
          assertFalse(rows.next());
        }
      }
      try (Statement statement = connection.createStatement();
          ResultSet rows = statement.executeQuery(
              "SELECT amount FROM invoices WHERE id=3")) {
        assertTrue(rows.next());
        assertEquals(new BigDecimal("42.70"), rows.getBigDecimal(1));
        assertFalse(rows.next());
      }
      try (Statement select = connection.createStatement();
          ResultSet rows = select.executeQuery(
              "SELECT amount FROM invoices WHERE id=2")) {
        assertTrue(rows.next());
        assertEquals(0L, rows.getLong(1));
        assertTrue(rows.wasNull());
        assertUnsupported(() -> rows.getObject(1, Long.class));
        assertNull(rows.getBigDecimal(1));
        assertTrue(rows.wasNull());
        assertFalse(rows.next());
      }
      try (Statement expression = connection.createStatement();
          ResultSet value = expression.executeQuery("SELECT 1.00/8.0")) {
        assertTrue(value.next());
        assertEquals(new BigDecimal("0.125000"), value.getBigDecimal(1));
        assertEquals(Types.DECIMAL, value.getMetaData().getColumnType(1));
        assertEquals(8, value.getMetaData().getPrecision(1));
        assertEquals(6, value.getMetaData().getScale(1));
        assertFalse(value.next());
      }
      try (Statement expression = connection.createStatement()) {
        SQLException failure = assertThrows(
            SQLException.class,
            () -> expression.executeQuery("SELECT 1.0/0.0"));
        assertEquals("22012", failure.getSQLState());
        try (ResultSet sum = expression.executeQuery(
            "SELECT 900000000000000000+900000000000000000.0")) {
          assertTrue(sum.next());
          assertEquals(new BigDecimal("1800000000000000000.0"), sum.getBigDecimal(1));
          assertFalse(sum.next());
        }
      }
      try (Statement expression = connection.createStatement();
          ResultSet value = expression.executeQuery("SELECT 2147483648")) {
        assertTrue(value.next());
        SQLException overflow = assertThrows(
            SQLException.class, () -> value.getInt(1));
        assertEquals("22003", overflow.getSQLState());
        assertFalse(value.next());
      }
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertUnsupported(Executable operation) {
    SQLException failure = assertThrows(SQLException.class, operation);
    assertEquals("0A000", failure.getSQLState());
  }

  private static void assertNumericOverflow(Executable operation) {
    SQLException failure = assertThrows(SQLException.class, operation);
    assertEquals("22003", failure.getSQLState());
  }
}
