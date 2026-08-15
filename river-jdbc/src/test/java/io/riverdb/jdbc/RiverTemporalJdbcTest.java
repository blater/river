package io.riverdb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.LocalTemporal;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.EmbeddedRiver;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.LoopbackServerOpenResult;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Calendar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RiverTemporalJdbcTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4a44424354454d50L, 0x4f52414c30303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void convertsTemporalExtremaAndPrecisionZero() throws SQLException {
    char[] characters = new char[32];
    assertEquals(
        "0001-01-01",
        RiverJdbcTemporalValues.string(
            LocalTemporal.MINIMUM_EPOCH_DAY,
            SqlTypeDescriptor.DATE,
            characters));
    assertEquals(
        "9999-12-31",
        RiverJdbcTemporalValues.string(
            LocalTemporal.MAXIMUM_EPOCH_DAY,
            SqlTypeDescriptor.DATE,
            characters));
    assertEquals(
        "23:59:59",
        RiverJdbcTemporalValues.string(
            LocalTemporal.MICROSECONDS_PER_DAY
                - LocalTemporal.MICROSECONDS_PER_SECOND,
            SqlTypeDescriptor.time(0),
            characters));
    assertEquals(
        "23:59:59.999999",
        RiverJdbcTemporalValues.string(
            LocalTemporal.MICROSECONDS_PER_DAY - 1,
            SqlTypeDescriptor.time(6),
            characters));
    assertEquals(
        LocalTime.of(23, 59, 59, 999_999_000),
        RiverJdbcTemporalValues.object(
            LocalTemporal.MICROSECONDS_PER_DAY - 1,
            SqlTypeDescriptor.time(6)));
    long maximumSecond = LocalTemporal.MAXIMUM_TIMESTAMP_MICROSECONDS
        - LocalTemporal.MICROSECONDS_PER_SECOND + 1;
    assertEquals(
        LocalDateTime.of(1, 1, 1, 0, 0),
        RiverJdbcTemporalValues.object(
            LocalTemporal.MINIMUM_TIMESTAMP_MICROSECONDS,
            SqlTypeDescriptor.timestamp(6)));
    assertEquals(
        "0001-01-01 00:00:00.000000+00:00",
        RiverJdbcTemporalValues.string(
            LocalTemporal.MINIMUM_INSTANT_MICROSECONDS,
            SqlTypeDescriptor.timestampWithTimeZone(6),
            characters));
    assertEquals(
        "9999-12-31 23:59:59",
        RiverJdbcTemporalValues.string(
            maximumSecond,
            SqlTypeDescriptor.timestamp(0),
            characters));
    assertEquals(
        LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_000),
        RiverJdbcTemporalValues.object(
            LocalTemporal.MAXIMUM_TIMESTAMP_MICROSECONDS,
            SqlTypeDescriptor.timestamp(6)));
    assertEquals(
        "9999-12-31 23:59:59.999999+00:00",
        RiverJdbcTemporalValues.string(
            LocalTemporal.MAXIMUM_INSTANT_MICROSECONDS,
            SqlTypeDescriptor.timestampWithTimeZone(6),
            characters));
    assertEquals(
        OffsetDateTime.of(
            9999, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC),
        RiverJdbcTemporalValues.object(
            maximumSecond,
            SqlTypeDescriptor.timestampWithTimeZone(0)));
  }

  @Test
  void exposesBinaryTemporalResultsThroughJavaTimeAndJdbcAccessors(
      @TempDir Path root) throws Exception {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(
        root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackServerOpenResult listener = new LoopbackServerOpenResult();
    assertEquals(StatusCode.OK, LoopbackRiverServer.start(database, 0, listener));
    LoopbackRiverServer server = listener.server();

    try (Connection connection = DriverManager.getConnection(
        RiverDriver.URL_PREFIX + server.port());
        Statement statement = connection.createStatement()) {
      assertEquals(0, statement.executeUpdate(
          "CREATE TABLE temporal_jdbc (id BIGINT PRIMARY KEY, day DATE, "
              + "clock TIME(6), local_seen TIMESTAMP(6), "
              + "captured TIMESTAMP(6) WITH TIME ZONE)"));
      assertEquals(2, statement.executeUpdate(
          "INSERT INTO temporal_jdbc VALUES "
              + "(1,DATE '1969-12-31',TIME '23:59:58.123456',"
              + "TIMESTAMP '1969-12-31 23:59:58.123456',"
              + "TIMESTAMP WITH TIME ZONE "
              + "'1969-12-31 23:59:58.123456+01:00'),"
              + "(2,NULL,NULL,NULL,NULL)"));
      try (ResultSet rows = statement.executeQuery(
          "SELECT id,day,clock,local_seen,captured "
              + "FROM temporal_jdbc ORDER BY id")) {
        assertMetadata(rows.getMetaData());
        assertTrue(rows.next());
        assertValues(rows);
        assertTrue(rows.next());
        for (int column = 2; column <= 5; column++) {
          assertNull(rows.getObject(column));
          assertTrue(rows.wasNull());
        }
        SQLException numericNull = assertThrows(
            SQLException.class, () -> rows.getLong(2));
        assertEquals("0A000", numericNull.getSQLState());
        SQLException wrongTemporalNull = assertThrows(
            SQLException.class, () -> rows.getDate(3));
        assertEquals("0A000", wrongTemporalNull.getSQLState());
        assertFalse(rows.next());
      }
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertMetadata(ResultSetMetaData metadata)
      throws SQLException {
    assertEquals(Types.DATE, metadata.getColumnType(2));
    assertEquals(Types.TIME, metadata.getColumnType(3));
    assertEquals(Types.TIMESTAMP, metadata.getColumnType(4));
    assertEquals(Types.TIMESTAMP_WITH_TIMEZONE, metadata.getColumnType(5));
    assertEquals("DATE", metadata.getColumnTypeName(2));
    assertEquals("TIME", metadata.getColumnTypeName(3));
    assertEquals("TIMESTAMP", metadata.getColumnTypeName(4));
    assertEquals("TIMESTAMP WITH TIME ZONE", metadata.getColumnTypeName(5));
    assertEquals(6, metadata.getScale(3));
    assertEquals(6, metadata.getScale(4));
    assertEquals(6, metadata.getScale(5));
    assertEquals(10, metadata.getColumnDisplaySize(2));
    assertEquals(15, metadata.getColumnDisplaySize(3));
    assertEquals(26, metadata.getColumnDisplaySize(4));
    assertEquals(32, metadata.getColumnDisplaySize(5));
    assertEquals(ResultSetMetaData.columnNoNulls, metadata.isNullable(1));
    assertEquals(ResultSetMetaData.columnNullable, metadata.isNullable(2));
    assertEquals(LocalDate.class.getName(), metadata.getColumnClassName(2));
    assertEquals(LocalTime.class.getName(), metadata.getColumnClassName(3));
    assertEquals(LocalDateTime.class.getName(), metadata.getColumnClassName(4));
    assertEquals(OffsetDateTime.class.getName(), metadata.getColumnClassName(5));
  }

  private static void assertValues(ResultSet rows) throws SQLException {
    LocalDate date = LocalDate.of(1969, 12, 31);
    LocalTime time = LocalTime.of(23, 59, 58, 123_456_000);
    LocalDateTime local = LocalDateTime.of(date, time);
    Instant instant = Instant.parse("1969-12-31T22:59:58.123456Z");
    OffsetDateTime offset = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);

    assertEquals(date, rows.getObject("day"));
    assertEquals(time, rows.getObject("clock"));
    assertEquals(local, rows.getObject("local_seen"));
    assertEquals(offset, rows.getObject("captured"));
    assertEquals(date, rows.getObject(2, LocalDate.class));
    assertEquals(time, rows.getObject(3, LocalTime.class));
    assertEquals(local, rows.getObject(4, LocalDateTime.class));
    assertEquals(offset, rows.getObject(5, OffsetDateTime.class));
    assertEquals(Date.valueOf(date), rows.getDate(2));
    assertEquals(Time.valueOf(time), rows.getTime(3));
    assertEquals(time.withNano(0), rows.getTime(3).toLocalTime());
    assertEquals(Timestamp.valueOf(local), rows.getTimestamp(4));
    assertEquals(Timestamp.from(instant), rows.getTimestamp(5));
    assertEquals("1969-12-31", rows.getString(2));
    assertEquals("23:59:58.123456", rows.getString(3));
    assertEquals("1969-12-31 23:59:58.123456", rows.getString(4));
    assertEquals("1969-12-31 22:59:58.123456+00:00", rows.getString(5));
    SQLException unsupported = assertThrows(
        SQLException.class, () -> rows.getObject(2, LocalTime.class));
    assertEquals("0A000", unsupported.getSQLState());
    SQLException rawNumeric = assertThrows(
        SQLException.class, () -> rows.getLong(2));
    assertEquals("0A000", rawNumeric.getSQLState());
    SQLException calendar = assertThrows(
        SQLException.class, () -> rows.getDate(2, Calendar.getInstance()));
    assertEquals("0A000", calendar.getSQLState());
    assertInstanceOf(LocalDate.class, rows.getObject(2));
  }
}
