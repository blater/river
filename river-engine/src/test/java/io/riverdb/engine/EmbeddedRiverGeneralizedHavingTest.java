package io.riverdb.engine;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.RowResult;
import io.riverdb.engine.api.SessionOpenResult;
import io.riverdb.sql.SqlCommand;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real-path evidence for the bounded aggregate set and generalized HAVING. */
final class EmbeddedRiverGeneralizedHavingTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x47454e4552414c48L, 0x4156494e47303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void evaluatesScalarAndGroupedAggregateSetsWithThreeValuedLogic(
      @TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult command = new CommandResult();
    createFacts(session, command);

    assertScalarValue(
        session,
        "SELECT COUNT(amount) AS n FROM facts "
            + "HAVING n=3 AND MIN(amount)=10 AND MIN(amount) IN (10,NULL)",
        3);
    assertScalarEmpty(
        session,
        "SELECT COUNT(amount) AS n FROM facts HAVING n>3",
        SqlTypeDescriptor.BIGINT);
    assertScalarEmpty(
        session,
        "SELECT COUNT(amount) FROM facts "
            + "HAVING MAX(amount) NOT IN (999,NULL)",
        SqlTypeDescriptor.BIGINT);
    assertScalarValue(
        session,
        "SELECT COUNT(amount) FROM facts WHERE grp=99 "
            + "HAVING COUNT(amount)=0",
        0);
    assertScalarValue(
        session,
        "SELECT COUNT(amount) FROM facts WHERE grp=3 "
            + "HAVING COUNT(amount)=0",
        0);

    assertNumericGroups(
        session,
        "SELECT grp, SUM(amount) AS total FROM facts GROUP BY grp "
            + "HAVING total>=30 AND MIN(amount)=10 "
            + "AND MIN(amount) IN (10,NULL) OR COUNT(amount)=0 ORDER BY grp",
        new long[] {1, 3},
        new long[] {30, 0},
        1L << 1);
    assertNumericGroups(
        session,
        "SELECT grp AS g, COUNT(*) AS n FROM facts GROUP BY grp "
            + "HAVING g BETWEEN 1 AND 2 AND n>=2 ORDER BY grp",
        new long[] {1, 2},
        new long[] {2, 2},
        0);
    assertNumericGroups(
        session,
        "SELECT grp, COUNT(*) FROM facts GROUP BY grp "
            + "HAVING MIN(amount) IS NULL OR MAX(amount)>20 ORDER BY grp",
        new long[] {2, 3},
        new long[] {2, 1},
        0);
    String groupedCapacity =
        "SELECT grp, COUNT(*) FROM facts GROUP BY grp HAVING "
            + "COUNT(*)+COUNT(label)+SUM(amount)+AVG(amount)"
            + "+MIN(amount)+MAX(amount)>=0 "
            + "AND MIN(day)>=DATE '2024-01-01' "
            + "AND MAX(day)>=DATE '2024-01-01' ORDER BY grp";
    assertNumericGroups(
        session,
        groupedCapacity,
        new long[] {1, 2},
        new long[] {2, 2},
        0);
    assertNumericGroups(
        session,
        groupedCapacity.replace(
            " ORDER BY grp", " AND MIN(label)>='' ORDER BY grp"),
        new long[] {1, 2},
        new long[] {2, 2},
        0);
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        session.beginQuery(
            "SELECT grp, MIN(label) FROM facts GROUP BY grp "
                + "HAVING CAST(MIN(label) AS DATE)=DATE '2024-01-01'",
            new QueryOpenResult()));

    assertHavingPlan(session, "EXPLAIN ");
    assertHavingPlan(session, "EXPLAIN ANALYZE ");
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void ownsTextAcrossOrderedMaterializedAndSpilledGroups(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult command = new CommandResult();
    createFacts(session, command);
    createSpill(session, command);

    assertTextAggregateGroups(
        session,
        "SELECT grp, MIN(label) AS first FROM facts GROUP BY grp "
            + "HAVING MAX(label)='猫' ORDER BY grp",
        new long[] {1},
        new String[] {"犬"});
    assertTextAggregateGroups(
        session,
        "SELECT nullable_grp, MAX(label) FROM facts GROUP BY nullable_grp "
            + "HAVING MIN(label) IN ('','犬') ORDER BY nullable_grp",
        new long[] {1, 2},
        new String[] {"猫", "é"});
    assertTextAggregateGroups(
        session,
        "SELECT grp, MAX(CAST(day AS VARCHAR(10))) FROM facts GROUP BY grp "
            + "HAVING MAX(CAST(day AS VARCHAR(10)))>='2024-01-02' ORDER BY grp",
        new long[] {1, 2},
        new String[] {"2024-01-02", "2024-02-01"});
    assertTextKeyGroups(
        session,
        "SELECT label, COUNT(*) FROM facts WHERE label IS NOT NULL GROUP BY label "
            + "HAVING label IN ('','猫') ORDER BY label",
        new String[] {"", "猫"},
        new long[] {1, 1});
    assertTextKeyGroups(
        session,
        "SELECT bucket, MIN(label) FROM text_spill GROUP BY bucket "
            + "HAVING bucket IN ('甲','乙') AND MAX(label)='omega' ORDER BY bucket",
        new String[] {"乙", "甲"},
        new String[] {"alpha", "alpha"});

    assertNumericGroups(
        session,
        "SELECT grp, COUNT(*) FROM facts GROUP BY grp ORDER BY grp",
        new long[] {1, 2, 3},
        new long[] {2, 2, 1},
        0);
    assertNumericValues(
        session,
        "SELECT DISTINCT grp FROM facts ORDER BY grp",
        1, 2, 3);
    assertTerminalTextCleanup(session, command);

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void createFacts(RiverSession session, CommandResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE facts (id BIGINT PRIMARY KEY, grp BIGINT NOT NULL, "
                + "nullable_grp BIGINT, label VARCHAR(16), amount BIGINT, day DATE)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO facts VALUES "
                + "(1,1,1,'猫',10,DATE '2024-01-01'),"
                + "(2,1,1,'犬',20,DATE '2024-01-02'),"
                + "(3,2,2,'',30,DATE '2024-02-01'),"
                + "(4,2,2,'é',NULL,NULL),"
                + "(5,3,NULL,NULL,NULL,NULL)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX facts_grp ON facts(grp)", result));
  }

  private static void createSpill(RiverSession session, CommandResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE text_spill "
                + "(id BIGINT PRIMARY KEY, bucket VARCHAR(8), label VARCHAR(16))",
            result));
    for (int first = 1; first <= 1_025; first += SqlCommand.RECOMMENDED_INSERT_BATCH_ROWS) {
      int last = Math.min(1_025, first + SqlCommand.RECOMMENDED_INSERT_BATCH_ROWS - 1);
      StringBuilder sql = new StringBuilder("INSERT INTO text_spill VALUES ");
      for (int id = first; id <= last; id++) {
        if (id > first) sql.append(',');
        sql.append('(').append(id).append(',')
            .append(id % 2 == 0 ? "'甲'" : "'乙'").append(',')
            .append(id % 3 == 0 ? "'omega'" : "'alpha'").append(')');
      }
      assertEquals(StatusCode.OK, session.execute(sql.toString(), result));
    }
  }

  private static void assertScalarValue(
      RiverSession session, String sql, long expected) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.beginQuery(sql, opened));
    RiverQuery query = opened.query();
    assertEquals(1, query.columnCount());
    assertEquals(SqlTypeDescriptor.BIGINT, query.columnTypeDescriptor(0));
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    assertTrue(row.isAvailable());
    assertFalse(row.isNull(0));
    assertEquals(expected, row.valueAt(0));
    assertEquals(StatusCode.OK, query.next(row));
    assertFalse(row.isAvailable());
    assertEquals(StatusCode.OK, query.close(new CommandResult()));
  }

  private static void assertScalarEmpty(
      RiverSession session, String sql, int descriptor) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.beginQuery(sql, opened));
    RiverQuery query = opened.query();
    assertEquals(1, query.columnCount());
    assertEquals(descriptor, query.columnTypeDescriptor(0));
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    assertFalse(row.isAvailable());
    assertEquals(StatusCode.OK, query.close(new CommandResult()));
  }

  private static void assertNumericGroups(
      RiverSession session,
      String sql,
      long[] groups,
      long[] values,
      long nullMask) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.beginQuery(sql, opened), sql);
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    for (int index = 0; index < groups.length; index++) {
      assertEquals(StatusCode.OK, query.next(row));
      assertTrue(row.isAvailable());
      assertEquals(groups[index], row.valueAt(0));
      assertEquals((nullMask & 1L << index) != 0, row.isNull(1));
      assertEquals(values[index], row.valueAt(1));
    }
    assertEquals(StatusCode.OK, query.next(row));
    assertFalse(row.isAvailable());
    assertEquals(StatusCode.OK, query.close(new CommandResult()));
  }

  private static void assertNumericValues(
      RiverSession session, String sql, long... values) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.beginQuery(sql, opened));
    RowResult row = new RowResult();
    for (long value : values) {
      assertEquals(StatusCode.OK, opened.query().next(row));
      assertEquals(value, row.valueAt(0));
    }
    assertEquals(StatusCode.OK, opened.query().next(row));
    assertFalse(row.isAvailable());
    assertEquals(StatusCode.OK, opened.query().close(new CommandResult()));
  }

  private static void assertTextAggregateGroups(
      RiverSession session, String sql, long[] groups, String[] values) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.beginQuery(sql, opened), sql);
    RowResult row = new RowResult();
    for (int index = 0; index < groups.length; index++) {
      assertEquals(StatusCode.OK, opened.query().next(row));
      assertEquals(groups[index], row.valueAt(0));
      assertText(values[index], row, 1);
    }
    assertEquals(StatusCode.OK, opened.query().next(row));
    assertFalse(row.isAvailable());
    assertEquals(StatusCode.OK, opened.query().close(new CommandResult()));
  }

  private static void assertTextKeyGroups(
      RiverSession session, String sql, String[] groups, long[] values) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.beginQuery(sql, opened), sql);
    RowResult row = new RowResult();
    for (int index = 0; index < groups.length; index++) {
      assertEquals(StatusCode.OK, opened.query().next(row));
      assertText(groups[index], row, 0);
      assertEquals(values[index], row.valueAt(1));
    }
    assertEquals(StatusCode.OK, opened.query().next(row));
    assertFalse(row.isAvailable());
    assertEquals(StatusCode.OK, opened.query().close(new CommandResult()));
  }

  private static void assertTextKeyGroups(
      RiverSession session, String sql, String[] groups, String[] values) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.beginQuery(sql, opened), sql);
    RowResult row = new RowResult();
    for (int index = 0; index < groups.length; index++) {
      assertEquals(StatusCode.OK, opened.query().next(row));
      assertText(groups[index], row, 0);
      assertText(values[index], row, 1);
    }
    assertEquals(StatusCode.OK, opened.query().next(row));
    assertFalse(row.isAvailable());
    assertEquals(StatusCode.OK, opened.query().close(new CommandResult()));
  }

  private static void assertTerminalTextCleanup(
      RiverSession session, CommandResult command) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT grp, MIN(label) FROM facts GROUP BY grp "
                + "HAVING MIN(label)>='' AND COUNT(*)/0>0 ORDER BY grp",
            opened));
    RowResult row = new RowResult();
    assertEquals(StatusCode.DIVISION_BY_ZERO, opened.query().next(row));
    assertEquals(StatusCode.DIVISION_BY_ZERO, opened.query().next(row));
    assertEquals(StatusCode.OK, opened.query().close(command));
    assertEquals(StatusCode.OK, session.execute("SELECT id FROM facts WHERE id=1", command));
    assertEquals(1, command.valueAt(0));
  }

  private static void assertHavingPlan(RiverSession session, String prefix) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            prefix + "SELECT grp, SUM(amount) AS total FROM facts GROUP BY grp "
                + "HAVING total>=30 AND MIN(amount)>=10 ORDER BY grp",
            opened));
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, opened.query().next(row));
    assertText("having", row, 0);
    assertEquals(2, row.valueAt(1));
    if (prefix.startsWith("EXPLAIN ANALYZE")) {
      assertFalse(row.isNull(2));
      assertEquals(2, row.valueAt(2));
    } else {
      assertTrue(row.isNull(2));
    }
    assertEquals(StatusCode.OK, opened.query().next(row));
    assertText("group", row, 0);
    StatusCode status;
    do {
      status = opened.query().next(row);
      // Drain the bounded plan rows.
    } while (status.isOk() && row.isAvailable());
    assertEquals(StatusCode.OK, status);
    assertFalse(row.isAvailable());
    assertEquals(StatusCode.OK, opened.query().close(new CommandResult()));
  }

  private static void assertText(String expected, RowResult row, int column) {
    char[] text = new char[32];
    int length = row.copyTextAt(column, text, 0);
    assertEquals(expected.length(), length);
    assertEquals(expected, new String(text, 0, length));
  }
}
