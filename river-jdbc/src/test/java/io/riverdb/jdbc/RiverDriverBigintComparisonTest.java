package io.riverdb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RiverDriverBigintComparisonTest {
  @Test
  void bigintComparisonsReachScansIndexesJoinsAggregatesAndMutations(
      @TempDir java.nio.file.Path root) throws SQLException {
    try (RiverDriverTestFixture fixture = RiverDriverTestFixture.open(root, 8);
        Connection connection = DriverManager.getConnection(fixture.url());
        Statement statement = connection.createStatement()) {
      assertEquals(0, statement.executeUpdate(
          "CREATE TABLE comparison_values "
              + "(id BIGINT PRIMARY KEY, value BIGINT, kind BIGINT)"));
      assertEquals(
          6,
          statement.executeUpdate(
              "INSERT INTO comparison_values VALUES "
                  + "(1, -9223372036854775808, 1), (2, -1, 1), "
                  + "(3, 0, 2), (4, 1, 2), "
                  + "(5, 9223372036854775807, 2), (6, NULL, 2)"));

      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE value<-1 ORDER BY id")) {
        assertTrue(rows.next());
        assertEquals(1, rows.getLong(1));
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE value<=-1 ORDER BY id")) {
        assertTrue(rows.next());
        assertEquals(1, rows.getLong(1));
        assertTrue(rows.next());
        assertEquals(2, rows.getLong(1));
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values "
              + "WHERE value<=-9223372036854775808")) {
        assertTrue(rows.next());
        assertEquals(1, rows.getLong(1));
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE value>1 ORDER BY id")) {
        assertTrue(rows.next());
        assertEquals(5, rows.getLong(1));
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE value>=1 ORDER BY id")) {
        assertTrue(rows.next());
        assertEquals(4, rows.getLong(1));
        assertTrue(rows.next());
        assertEquals(5, rows.getLong(1));
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values "
              + "WHERE value>9223372036854775807")) {
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE value<>0 ORDER BY id")) {
        for (long expected : new long[] {1, 2, 4, 5}) {
          assertTrue(rows.next());
          assertEquals(expected, rows.getLong(1));
        }
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE value!=0 ORDER BY id")) {
        for (long expected : new long[] {1, 2, 4, 5}) {
          assertTrue(rows.next());
          assertEquals(expected, rows.getLong(1));
        }
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE value IN (-1, 1, 99) ORDER BY id")) {
        assertTrue(rows.next());
        assertEquals(2, rows.getLong(1));
        assertTrue(rows.next());
        assertEquals(4, rows.getLong(1));
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE value NOT IN (-1, 1) ORDER BY id")) {
        assertTrue(rows.next());
        assertEquals(1, rows.getLong(1));
        assertTrue(rows.next());
        assertEquals(3, rows.getLong(1));
        assertTrue(rows.next());
        assertEquals(5, rows.getLong(1));
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE value NOT IN (-1, NULL)")) {
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE value IN (NULL)")) {
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE id IN "
              + "(SELECT id FROM comparison_values WHERE kind IN (1)) ORDER BY id")) {
        assertTrue(rows.next());
        assertEquals(1, rows.getLong(1));
        assertTrue(rows.next());
        assertEquals(2, rows.getLong(1));
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values "
              + "WHERE value>=-1 AND value<=1 ORDER BY id")) {
        for (long expected = 2; expected <= 4; expected++) {
          assertTrue(rows.next());
          assertEquals(expected, rows.getLong(1));
        }
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE value BETWEEN -1 AND 1 ORDER BY id")) {
        for (long expected = 2; expected <= 4; expected++) {
          assertTrue(rows.next());
          assertEquals(expected, rows.getLong(1));
        }
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values "
              + "WHERE value BETWEEN 1 AND 9223372036854775807 ORDER BY id")) {
        assertTrue(rows.next());
        assertEquals(4, rows.getLong(1));
        assertTrue(rows.next());
        assertEquals(5, rows.getLong(1));
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values "
              + "WHERE value>=-1 AND value<1 ORDER BY id")) {
        assertTrue(rows.next());
        assertEquals(2, rows.getLong(1));
        assertTrue(rows.next());
        assertEquals(3, rows.getLong(1));
        assertFalse(rows.next());
      }

      assertEquals(0, statement.executeUpdate(
          "CREATE TABLE comparison_indexed "
              + "(id BIGINT PRIMARY KEY, value BIGINT)"));
      assertEquals(3, statement.executeUpdate(
          "INSERT INTO comparison_indexed VALUES (1, 100), (2, 200), (3, 300)"));
      assertEquals(0, statement.executeUpdate(
          "CREATE INDEX comparison_value_idx ON comparison_indexed(value)"));
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_indexed WHERE value>100 ORDER BY id")) {
        assertTrue(rows.next());
        assertEquals(2, rows.getLong(1));
        assertTrue(rows.next());
        assertEquals(3, rows.getLong(1));
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_indexed "
              + "WHERE value BETWEEN 100 AND 200 ORDER BY id")) {
        assertTrue(rows.next());
        assertEquals(1, rows.getLong(1));
        assertTrue(rows.next());
        assertEquals(2, rows.getLong(1));
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_indexed ORDER BY value DESC")) {
        for (long expected = 3; expected >= 1; expected--) {
          assertTrue(rows.next());
          assertEquals(expected, rows.getLong(1));
        }
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values ORDER BY value DESC")) {
        long[] expected = {5, 4, 3, 2, 1, 6};
        for (long id : expected) {
          assertTrue(rows.next());
          assertEquals(id, rows.getLong(1));
        }
        assertFalse(rows.next());
      }

      try (ResultSet count = statement.executeQuery(
          "SELECT COUNT(*) FROM comparison_values WHERE value<>0")) {
        assertTrue(count.next());
        assertEquals(4, count.getLong(1));
        assertFalse(count.next());
      }
      try (ResultSet count = statement.executeQuery(
          "SELECT COUNT(value) AS present FROM comparison_values")) {
        assertEquals("present", count.getMetaData().getColumnLabel(1));
        assertTrue(count.next());
        assertEquals(5, count.getLong(1));
        assertFalse(count.wasNull());
        assertFalse(count.next());
      }
      try (ResultSet count = statement.executeQuery(
          "SELECT COUNT(value) FROM comparison_values WHERE id>=5")) {
        assertEquals("count", count.getMetaData().getColumnLabel(1));
        assertTrue(count.next());
        assertEquals(1, count.getLong(1));
        assertFalse(count.wasNull());
        assertFalse(count.next());
      }
      try (ResultSet count = statement.executeQuery(
          "SELECT COUNT(value) FROM comparison_values WHERE id=99")) {
        assertTrue(count.next());
        assertEquals(0, count.getLong(1));
        assertFalse(count.wasNull());
        assertFalse(count.next());
      }
      assertEquals(0, statement.executeUpdate(
          "CREATE INDEX comparison_kind_idx ON comparison_values(kind)"));
      try (ResultSet groups = statement.executeQuery(
          "SELECT kind, COUNT(*) FROM comparison_values "
              + "WHERE value!=0 GROUP BY kind ORDER BY kind")) {
        assertTrue(groups.next());
        assertEquals(1, groups.getLong(1));
        assertEquals(2, groups.getLong(2));
        assertTrue(groups.next());
        assertEquals(2, groups.getLong(1));
        assertEquals(2, groups.getLong(2));
        assertFalse(groups.next());
      }
      try (ResultSet sum = statement.executeQuery(
          "SELECT SUM(value) AS total FROM comparison_values")) {
        assertEquals("total", sum.getMetaData().getColumnLabel(1));
        assertTrue(sum.next());
        assertEquals(-1, sum.getLong(1));
        assertFalse(sum.wasNull());
        assertFalse(sum.next());
      }
      try (ResultSet sum = statement.executeQuery(
          "SELECT SUM(value) FROM comparison_values WHERE id>=2 AND id<5")) {
        assertEquals("sum", sum.getMetaData().getColumnLabel(1));
        assertTrue(sum.next());
        assertEquals(0, sum.getLong(1));
        assertFalse(sum.wasNull());
        assertFalse(sum.next());
      }
      try (ResultSet sum = statement.executeQuery(
          "SELECT SUM(value) FROM comparison_values WHERE id=6")) {
        assertTrue(sum.next());
        assertEquals(0, sum.getLong(1));
        assertTrue(sum.wasNull());
        assertNull(sum.getObject(1));
        assertFalse(sum.next());
      }
      try (ResultSet sum = statement.executeQuery(
          "SELECT SUM(value) FROM comparison_values WHERE id=99")) {
        assertTrue(sum.next());
        assertNull(sum.getObject(1));
        assertTrue(sum.wasNull());
        assertFalse(sum.next());
      }
      try (ResultSet minimum = statement.executeQuery(
          "SELECT MIN(value) AS lowest FROM comparison_values")) {
        assertEquals("lowest", minimum.getMetaData().getColumnLabel(1));
        assertTrue(minimum.next());
        assertEquals(Long.MIN_VALUE, minimum.getLong(1));
        assertFalse(minimum.wasNull());
        assertFalse(minimum.next());
      }
      try (ResultSet maximum = statement.executeQuery(
          "SELECT MAX(value) FROM comparison_values WHERE kind=1")) {
        assertEquals("max", maximum.getMetaData().getColumnLabel(1));
        assertTrue(maximum.next());
        assertEquals(-1, maximum.getLong(1));
        assertFalse(maximum.wasNull());
        assertFalse(maximum.next());
      }
      try (ResultSet minimum = statement.executeQuery(
          "SELECT MIN(value) FROM comparison_values WHERE id=6")) {
        assertTrue(minimum.next());
        assertNull(minimum.getObject(1));
        assertTrue(minimum.wasNull());
        assertFalse(minimum.next());
      }
      try (ResultSet maximum = statement.executeQuery(
          "SELECT MAX(value) FROM comparison_values WHERE id=99")) {
        assertTrue(maximum.next());
        assertNull(maximum.getObject(1));
        assertTrue(maximum.wasNull());
        assertFalse(maximum.next());
      }
      SQLException positiveOverflow = assertThrows(
          SQLException.class,
          () -> statement.executeQuery(
              "SELECT SUM(value) FROM comparison_values WHERE value>0"));
      assertEquals("22003", positiveOverflow.getSQLState());
      SQLException negativeOverflow = assertThrows(
          SQLException.class,
          () -> statement.executeQuery(
              "SELECT SUM(value) FROM comparison_values WHERE value<0"));
      assertEquals("22003", negativeOverflow.getSQLState());
      assertEquals(0, statement.executeUpdate(
          "CREATE TABLE comparison_kinds "
              + "(id BIGINT PRIMARY KEY, label BIGINT)"));
      assertEquals(2, statement.executeUpdate(
          "INSERT INTO comparison_kinds VALUES (1, 10), (2, 20)"));
      try (ResultSet rows = statement.executeQuery(
          "SELECT comparison_values.id FROM comparison_values "
              + "JOIN comparison_kinds "
              + "ON comparison_values.kind=comparison_kinds.id "
              + "WHERE comparison_values.value>0")) {
        assertTrue(rows.next());
        long first = rows.getLong(1);
        assertTrue(rows.next());
        long second = rows.getLong(1);
        assertTrue(
            (first == 4 && second == 5)
                || (first == 5 && second == 4));
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT cv.id, ck.label FROM comparison_values cv "
              + "JOIN comparison_kinds AS ck ON cv.kind=ck.id "
              + "WHERE cv.value>0")) {
        assertTrue(rows.next());
        long first = rows.getLong(1);
        assertEquals(20, rows.getLong(2));
        assertTrue(rows.next());
        long second = rows.getLong(1);
        assertEquals(20, rows.getLong(2));
        assertTrue(
            (first == 4 && second == 5)
                || (first == 5 && second == 4));
        assertFalse(rows.next());
      }
      SQLException ambiguousAlias = assertThrows(
          SQLException.class,
          () -> statement.executeQuery(
              "SELECT cv.id FROM comparison_values cv "
                  + "JOIN comparison_kinds cv ON cv.kind=cv.id"));
      assertEquals("22000", ambiguousAlias.getSQLState());
      try (ResultSet rows = statement.executeQuery(
          "SELECT d.id FROM "
              + "(SELECT id, value FROM comparison_values) d "
              + "WHERE d.value>0 ORDER BY id")) {
        assertTrue(rows.next());
        assertEquals(4, rows.getLong(1));
        assertTrue(rows.next());
        assertEquals(5, rows.getLong(1));
        assertFalse(rows.next());
      }
      try (ResultSet rows = statement.executeQuery(
          "SELECT d.id FROM "
              + "(SELECT id, value FROM comparison_values) d "
              + "WHERE d.value>0 ORDER BY id DESC")) {
        assertTrue(rows.next());
        assertEquals(5, rows.getLong(1));
        assertTrue(rows.next());
        assertEquals(4, rows.getLong(1));
        assertFalse(rows.next());
      }
      try (ResultSet row = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE id="
              + "(SELECT id FROM comparison_values "
              + "WHERE value>=9223372036854775807)")) {
        assertTrue(row.next());
        assertEquals(5, row.getLong(1));
        assertFalse(row.next());
      }

      assertEquals(2, statement.executeUpdate(
          "UPDATE comparison_values SET kind=9 WHERE value<=-1"));
      try (ResultSet count = statement.executeQuery(
          "SELECT COUNT(*) FROM comparison_values WHERE kind=9")) {
        assertTrue(count.next());
        assertEquals(2, count.getLong(1));
      }
      assertEquals(2, statement.executeUpdate(
          "DELETE FROM comparison_values WHERE value>=1"));
      try (ResultSet rows = statement.executeQuery(
          "SELECT id FROM comparison_values WHERE value!=0 ORDER BY id")) {
        assertTrue(rows.next());
        assertEquals(1, rows.getLong(1));
        assertTrue(rows.next());
        assertEquals(2, rows.getLong(1));
        assertFalse(rows.next());
      }
    }
  }
}
