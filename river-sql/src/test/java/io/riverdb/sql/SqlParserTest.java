package io.riverdb.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class SqlParserTest {
  @Test
  void parsesExecutablePointStatementSubset() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    assertEquals(StatusCode.OK, parser.parse("create table accounts;", command));
    assertEquals(SqlCommandType.CREATE_TABLE, command.type());
    assertName("accounts", command.tableName());
    assertEquals(StatusCode.OK, parser.parse("INSERT INTO accounts VALUES (7, -9)", command));
    assertEquals(SqlCommandType.INSERT, command.type());
    assertEquals(7, command.key());
    assertEquals(-9, command.value());
    assertEquals(StatusCode.OK, parser.parse("select value from accounts where key=7", command));
    assertEquals(SqlCommandType.SELECT, command.type());
    assertEquals(7, command.key());
    assertEquals(StatusCode.OK, parser.parse("UPDATE accounts SET value=11 WHERE key=7", command));
    assertEquals(SqlCommandType.UPDATE, command.type());
    assertEquals(11, command.value());
    assertEquals(StatusCode.OK, parser.parse("DELETE FROM accounts WHERE key = 7", command));
    assertEquals(SqlCommandType.DELETE, command.type());
    assertEquals(StatusCode.OK, parser.parse("BEGIN;", command));
    assertEquals(SqlCommandType.BEGIN, command.type());
    assertEquals(StatusCode.OK, parser.parse("COMMIT", command));
    assertEquals(SqlCommandType.COMMIT, command.type());
    assertEquals(StatusCode.OK, parser.parse("ROLLBACK", command));
    assertEquals(SqlCommandType.ROLLBACK, command.type());
  }

  @Test
  void rejectsMalformedUnsupportedAndOverflowInput() {
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, parser.parse("SELECT * FROM x", command));
    assertFalse(command.isAvailable());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, parser.parse("CREATE TABLE bad-name", command));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        parser.parse("INSERT INTO x VALUES (9223372036854775808, 1)", command));
    assertEquals(
        StatusCode.OK,
        parser.parse("INSERT INTO x VALUES (0, -9223372036854775808)", command));
    assertEquals(Long.MIN_VALUE, command.value());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, parser.parse("DROP TABLE x", command));
  }

  @Test
  void warmedParseReusesCommandAndParserState() {
    java.lang.management.ThreadMXBean standard = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standard instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standard;
    Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    bean.setThreadAllocatedMemoryEnabled(true);
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand();
    for (int index = 0; index < 1_000; index++) {
      parser.parse("UPDATE accounts SET value=11 WHERE key=7", command);
    }
    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 0; index < 1_000; index++) {
      assertEquals(
          StatusCode.OK,
          parser.parse("UPDATE accounts SET value=11 WHERE key=7", command));
    }
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;
    assertTrue(allocated <= 256, "warmed SQL parse allocated bytes: " + allocated);
  }

  private static void assertName(String expected, SqlIdentifier actual) {
    assertEquals(expected.length(), actual.length());
    for (int index = 0; index < expected.length(); index++) {
      assertEquals(expected.charAt(index), actual.charAt(index));
    }
  }
}
