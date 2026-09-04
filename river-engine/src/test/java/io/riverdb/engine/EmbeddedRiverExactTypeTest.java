package io.riverdb.engine;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.text.PackedText;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.RowResult;
import io.riverdb.engine.api.SessionOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EmbeddedRiverExactTypeTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4558414354545950L, 0x4553303030303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void storesIndexesAndRecoversBooleanAndDecimal(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult command = new CommandResult();
    QueryOpenResult queryResult = new QueryOpenResult();
    RiverQuery query;
    RowResult row = new RowResult();

    assertEquals(StatusCode.OK, session.execute("SELECT 1.20+2.345", command));
    assertEquals(SqlTypeDescriptor.decimal(5, 3), command.typeDescriptorAt(0));
    assertEquals(3_545, command.valueAt(0));
    assertEquals(StatusCode.OK, session.execute("SELECT 1.00/8.0", command));
    assertEquals(SqlTypeDescriptor.decimal(8, 6), command.typeDescriptorAt(0));
    assertEquals(125_000, command.valueAt(0));
    assertEquals(StatusCode.OK, session.execute("SELECT 5.50%2.0", command));
    assertEquals(150, command.valueAt(0));
    assertEquals(StatusCode.OK, session.execute("SELECT ABS(-12.30)", command));
    assertEquals(1_230, command.valueAt(0));
    assertEquals(StatusCode.OK, session.execute("SELECT CEIL(12.01)", command));
    assertEquals(13, command.valueAt(0));
    assertEquals(StatusCode.OK, session.execute("SELECT FLOOR(-12.01)", command));
    assertEquals(-13, command.valueAt(0));
    assertEquals(StatusCode.OK, session.execute("SELECT ROUND(1.255,2)", command));
    assertEquals(126, command.valueAt(0));
    assertEquals(StatusCode.OK, session.execute("SELECT TRUNCATE(-1.259,2)", command));
    assertEquals(-125, command.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT CAST(1.25 AS DECIMAL(4,1))", command));
    assertEquals(12, command.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT CAST(1.00 AS BIGINT)", command));
    assertEquals(1, command.valueAt(0));
    assertEquals(
        StatusCode.NUMERIC_VALUE_OUT_OF_RANGE,
        session.execute("SELECT CAST(1.20 AS BIGINT)", command));
    assertEquals(
        StatusCode.DIVISION_BY_ZERO,
        session.execute("SELECT 1.0/0.0", command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT 900000000000000000+900000000000000000.0", command));
    assertEquals(SqlTypeDescriptor.decimal(21, 1), command.typeDescriptorAt(0));
    assertEquals(0, command.decimalUnscaledHighAt(0));
    assertEquals(-446_744_073_709_551_616L, command.decimalUnscaledLowAt(0));
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT 900000000000000000+900000000000000000.0", queryResult));
    query = queryResult.query();
    assertEquals(StatusCode.OK, query.next(row));
    assertTrue(row.isAvailable());
    assertEquals(SqlTypeDescriptor.decimal(21, 1), row.typeDescriptorAt(0));
    assertEquals(0, row.decimalUnscaledHighAt(0));
    assertEquals(-446_744_073_709_551_616L, row.decimalUnscaledLowAt(0));
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(StatusCode.OK, query.close(command));
    assertEquals(StatusCode.OK, session.execute("SELECT TRUE", command));
    assertEquals(SqlTypeDescriptor.BOOLEAN, command.typeDescriptorAt(0));
    assertEquals(1, command.valueAt(0));

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE invoices (id BIGINT PRIMARY KEY, paid BOOLEAN DEFAULT FALSE, "
                + "amount DECIMAL(8,2) DEFAULT 12.30 CHECK (amount>=0.00))",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO invoices VALUES (1, TRUE, 42.7), (2, FALSE, 10.00), "
                + "(3, DEFAULT, DEFAULT)",
            command));
    assertCount(session, command, "amount=42.700", 1);
    assertCount(session, command, "amount<>42.700", 2);
    assertCount(session, command, "amount<12.300", 1);
    assertCount(session, command, "amount<=12.300", 2);
    assertCount(session, command, "amount>12.300", 1);
    assertCount(session, command, "amount>=12.300", 2);
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX invoices_amount ON invoices(amount)", command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE rates (id BIGINT PRIMARY KEY, amount DECIMAL(6,1))",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO rates VALUES (1, 42.7), (2, 99.9)", command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE large_amounts "
                + "(id BIGINT PRIMARY KEY, bucket BIGINT, value DECIMAL(18,1))",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO large_amounts VALUES "
                + "(1, 1, 90000000000000000.0), "
                + "(2, 1, 90000000000000000.0)",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT SUM(value) FROM large_amounts", command));
    assertEquals(SqlTypeDescriptor.decimal(38, 1), command.typeDescriptorAt(0));
    assertEquals(0, command.decimalUnscaledHighAt(0));
    assertEquals(1_800_000_000_000_000_000L, command.decimalUnscaledLowAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT AVG(value) FROM large_amounts", command));
    assertEquals(SqlTypeDescriptor.decimal(38, 6), command.typeDescriptorAt(0));
    assertEquals(4_878, command.decimalUnscaledHighAt(0));
    assertEquals(-1_664_335_628_902_334_464L, command.decimalUnscaledLowAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE flags (id BIGINT PRIMARY KEY, enabled BOOLEAN)", command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO flags VALUES (1, TRUE), (2, FALSE), (3, NULL)", command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE flag_refs (id BIGINT PRIMARY KEY, enabled BOOLEAN)", command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO flag_refs VALUES (10, TRUE), (20, FALSE)", command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE boolean_checks "
                + "(id BIGINT PRIMARY KEY, enabled BOOLEAN CHECK (enabled=TRUE))",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO boolean_checks VALUES (1, TRUE)", command));
    assertEquals(
        StatusCode.CHECK_VIOLATION,
        session.execute("INSERT INTO boolean_checks VALUES (2, FALSE)", command));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX flags_enabled ON flags(enabled)", command));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT COUNT(*) FROM flags WHERE enabled IS TRUE", command));
    assertEquals(1, command.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT COUNT(*) FROM flags WHERE enabled IS FALSE", command));
    assertEquals(1, command.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT COUNT(*) FROM flags WHERE enabled IS UNKNOWN", command));
    assertEquals(1, command.valueAt(0));
    assertCount(session, command, "enabled=TRUE", 1, "flags");
    assertCount(session, command, "enabled<>TRUE", 1, "flags");
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT COUNT(*) FROM flags WHERE enabled IN (TRUE, FALSE)", command));
    assertEquals(2, command.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT COUNT(*) FROM invoices WHERE amount IN (42.70, 10.0)", command));
    assertEquals(2, command.valueAt(0));
    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        session.execute("SELECT COUNT(*) FROM flags WHERE enabled<TRUE", command));
    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        session.execute("SELECT MIN(enabled) FROM flags", command));
    assertEquals(
        StatusCode.OK,
        session.beginQuery("SELECT DISTINCT enabled FROM flags", queryResult));
    query = queryResult.query();
    int distinctFlags = 0;
    while (true) {
      assertEquals(StatusCode.OK, query.next(row));
      if (!row.isAvailable()) {
        break;
      }
      assertEquals(SqlTypeDescriptor.BOOLEAN, row.typeDescriptorAt(0));
      distinctFlags++;
    }
    assertEquals(3, distinctFlags);
    assertEquals(StatusCode.OK, query.close(command));
    queryResult = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT enabled, COUNT(*) FROM flags GROUP BY enabled", queryResult));
    query = queryResult.query();
    int flagGroups = 0;
    while (true) {
      assertEquals(StatusCode.OK, query.next(row));
      if (!row.isAvailable()) {
        break;
      }
      assertEquals(1, row.valueAt(1));
      flagGroups++;
    }
    assertEquals(3, flagGroups);
    assertEquals(StatusCode.OK, query.close(command));
    queryResult = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX rates_amount ON rates(amount)", command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE decimal_keys "
                + "(id BIGINT PRIMARY KEY, value DECIMAL(6,2) UNIQUE)",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO decimal_keys VALUES (1, 1.0)", command));
    assertEquals(
        StatusCode.UNIQUE_VIOLATION,
        session.execute("INSERT INTO decimal_keys VALUES (2, 1.00)", command));
    assertEquals(
        StatusCode.CHECK_VIOLATION,
        session.execute("INSERT INTO invoices VALUES (4, FALSE, -0.01)", command));

    assertEquals(
        StatusCode.OK,
        session.execute("SELECT paid, amount FROM invoices WHERE id=1", command));
    assertEquals(SqlTypeDescriptor.BOOLEAN, command.typeDescriptorAt(0));
    assertEquals(1, command.valueAt(0));
    assertEquals(SqlTypeDescriptor.decimal(8, 2), command.typeDescriptorAt(1));
    assertEquals(4_270, command.valueAt(1));

    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT invoices.id, rates.id FROM invoices JOIN rates "
                + "ON invoices.amount=rates.amount",
            queryResult));
    query = queryResult.query();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(1, row.valueAt(0));
    assertEquals(1, row.valueAt(1));
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(false, row.isAvailable());
    assertEquals(StatusCode.OK, query.close(command));
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT id FROM invoices WHERE amount="
                + "(SELECT amount FROM rates WHERE id=1)",
            queryResult));
    query = queryResult.query();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(1, row.valueAt(0));
    assertEquals(StatusCode.OK, query.close(command));

    queryResult = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT id FROM invoices WHERE EXISTS "
                + "(SELECT id FROM rates WHERE rates.amount=invoices.amount)",
            queryResult));
    query = queryResult.query();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(1, row.valueAt(0));
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(false, row.isAvailable());
    assertEquals(StatusCode.OK, query.close(command));

    queryResult = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT amount FROM invoices WHERE amount BETWEEN 10.0 AND 30.000 "
                + "ORDER BY amount",
            queryResult));
    query = queryResult.query();
    long[] expected = {1_000, 1_230};
    for (long value : expected) {
      assertEquals(StatusCode.OK, query.next(row));
      assertEquals(SqlTypeDescriptor.decimal(8, 2), row.typeDescriptorAt(0));
      assertEquals(value, row.valueAt(0));
    }
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(false, row.isAvailable());
    assertEquals(StatusCode.OK, query.close(command));
    queryResult = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT flags.id, flag_refs.id FROM flags JOIN flag_refs "
                + "ON flags.enabled=flag_refs.enabled",
            queryResult));
    query = queryResult.query();
    assertEquals(StatusCode.OK, query.next(row));
    long firstFlag = row.valueAt(0);
    long firstReference = row.valueAt(1);
    assertEquals(StatusCode.OK, query.next(row));
    long secondFlag = row.valueAt(0);
    long secondReference = row.valueAt(1);
    assertEquals(3, firstFlag + secondFlag);
    assertEquals(30, firstReference + secondReference);
    assertEquals(firstFlag * 10, firstReference);
    assertEquals(secondFlag * 10, secondReference);
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(false, row.isAvailable());
    assertEquals(StatusCode.OK, query.close(command));
    queryResult = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT id FROM flags WHERE enabled="
                + "(SELECT enabled FROM flag_refs WHERE id=10)",
            queryResult));
    query = queryResult.query();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(1, row.valueAt(0));
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(false, row.isAvailable());
    assertEquals(StatusCode.OK, query.close(command));

    queryResult = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT id FROM invoices WHERE amount=42.700", queryResult));
    query = queryResult.query();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(1, row.valueAt(0));
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(false, row.isAvailable());
    assertEquals(StatusCode.OK, query.close(command));
    assertExplainUsesIndex(
        session, "SELECT id FROM invoices WHERE amount=42.700");
    assertExplainUsesIndex(
        session,
        "SELECT amount FROM invoices "
            + "WHERE amount BETWEEN 10.0 AND 30.000 ORDER BY amount");

    queryResult = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT amount FROM invoices "
                + "WHERE amount>=10.0 AND amount<=30.000 ORDER BY amount",
            queryResult));
    query = queryResult.query();
    for (long value : expected) {
      assertEquals(StatusCode.OK, query.next(row));
      assertEquals(value, row.valueAt(0));
    }
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(false, row.isAvailable());
    assertEquals(StatusCode.OK, query.close(command));

    queryResult = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery("SELECT DISTINCT amount FROM invoices", queryResult));
    query = queryResult.query();
    int distinctAmounts = 0;
    while (true) {
      assertEquals(StatusCode.OK, query.next(row));
      if (!row.isAvailable()) {
        break;
      }
      assertEquals(SqlTypeDescriptor.decimal(8, 2), row.typeDescriptorAt(0));
      distinctAmounts++;
    }
    assertEquals(3, distinctAmounts);
    assertEquals(StatusCode.OK, query.close(command));

    queryResult = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT amount, COUNT(*) FROM invoices GROUP BY amount", queryResult));
    query = queryResult.query();
    int amountGroups = 0;
    while (true) {
      assertEquals(StatusCode.OK, query.next(row));
      if (!row.isAvailable()) {
        break;
      }
      assertEquals(1, row.valueAt(1));
      amountGroups++;
    }
    assertEquals(3, amountGroups);
    assertEquals(StatusCode.OK, query.close(command));

    assertEquals(
        StatusCode.OK,
        session.execute("SELECT SUM(amount) FROM invoices", command));
    assertEquals(
        SqlTypeDescriptor.decimal(38, 2), command.typeDescriptorAt(0));
    assertEquals(6_500, command.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT AVG(amount) FROM invoices", command));
    assertEquals(
        SqlTypeDescriptor.decimal(38, 6), command.typeDescriptorAt(0));
    assertEquals(21_666_667, command.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT MIN(amount) FROM invoices", command));
    assertEquals(SqlTypeDescriptor.decimal(8, 2), command.typeDescriptorAt(0));
    assertEquals(1_000, command.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT MAX(amount) FROM invoices", command));
    assertEquals(SqlTypeDescriptor.decimal(8, 2), command.typeDescriptorAt(0));
    assertEquals(4_270, command.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT paid, SUM(amount) FROM invoices GROUP BY paid "
                + "HAVING SUM(amount)>30.0",
            queryResult));
    query = queryResult.query();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(1, row.valueAt(0));
    assertEquals(4_270, row.valueAt(1));
    assertEquals(
        SqlTypeDescriptor.decimal(38, 2), row.typeDescriptorAt(1));
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(false, row.isAvailable());
    assertEquals(StatusCode.OK, query.close(command));
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT paid, AVG(amount) FROM invoices GROUP BY paid "
                + "HAVING AVG(amount)>20.0",
            queryResult));
    query = queryResult.query();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(1, row.valueAt(0));
    assertEquals(42_700_000, row.valueAt(1));
    assertEquals(
        SqlTypeDescriptor.decimal(38, 6), row.typeDescriptorAt(1));
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(false, row.isAvailable());
    assertEquals(StatusCode.OK, query.close(command));

    assertEquals(
        StatusCode.OK,
        session.execute(
            "UPDATE invoices SET amount=amount+1.00 WHERE id=2", command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "UPDATE invoices SET amount=ROUND(amount,1) WHERE id=2", command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT amount FROM invoices WHERE id=2", command));
    assertEquals(SqlTypeDescriptor.decimal(8, 2), command.typeDescriptorAt(0));
    assertEquals(1_100, command.valueAt(0));

    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", command));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
    assertEquals(StatusCode.OK, EmbeddedRiver.openExisting(
        databaseRequest(8),
        root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    session = sessionResult.session();
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT paid, amount FROM invoices WHERE id=3", command));
    assertEquals(0, command.valueAt(0));
    assertEquals(1_230, command.valueAt(1));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT amount FROM invoices WHERE id=2", command));
    assertEquals(1_100, command.valueAt(0));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertCount(
      RiverSession session,
      CommandResult result,
      String predicate,
      long expected) {
    assertCount(session, result, predicate, expected, "invoices");
  }

  private static void assertCount(
      RiverSession session,
      CommandResult result,
      String predicate,
      long expected,
      String table) {
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT COUNT(*) FROM " + table + " WHERE " + predicate, result));
    assertEquals(expected, result.valueAt(0));
  }

  private static void assertExplainUsesIndex(RiverSession session, String sql) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.beginQuery("EXPLAIN " + sql, opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    boolean indexed = false;
    while (true) {
      assertEquals(StatusCode.OK, query.next(row));
      if (!row.isAvailable()) break;
      indexed |= row.valueAt(0) == PackedText.pack("index");
    }
    assertTrue(indexed);
    assertEquals(StatusCode.OK, query.close(new CommandResult()));
  }
}
