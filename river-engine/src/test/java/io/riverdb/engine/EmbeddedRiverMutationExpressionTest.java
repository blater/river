package io.riverdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.RowResult;
import io.riverdb.engine.api.SessionOpenResult;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EmbeddedRiverMutationExpressionTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4d55544154494f4eL, 0x4558505230303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void evaluatesInsertAndOldRowUpdateProgramsThroughRelationalMutation(
      @TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult result = new CommandResult();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE mutation_values (id BIGINT PRIMARY KEY,"
                + "a BIGINT NOT NULL,b BIGINT NOT NULL,amount DECIMAL(8,2),"
                + "day DATE,observed TIMESTAMP(6),"
                + "captured TIMESTAMP(6) WITH TIME ZONE,flag BOOLEAN)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO mutation_values VALUES"
                + "(1+0,10+1,20+2,CAST(1.25*2.0 AS DECIMAL(8,2)),"
                + "DATE '2024-01-01'+1,"
                + "CAST(DATE '2024-01-02' AS TIMESTAMP(6)),"
                + "TIMESTAMP '2024-01-02 10:00:00' AT TIME ZONE 'UTC',TRUE),"
                + "(2,30,40,3.50,DATE '2024-01-03',"
                + "TIMESTAMP '2024-01-03 00:00:00',"
                + "TIMESTAMP WITH TIME ZONE '2024-01-03 00:00:00+00:00',FALSE),"
                + "(3,50,60,4.50,DATE '2024-03-31',"
                + "TIMESTAMP '2024-03-31 01:30:00',NULL,FALSE)",
            result));
    assertEquals(3, result.affectedRows());
    assertRow(session, result, 1, 11, 22, 250, epochDay(2024, 1, 2));

    assertEquals(
        StatusCode.OK,
        session.execute(
            "UPDATE mutation_values SET a=b+1,b=a-1,amount=amount*2,day=day+1 "
                + "WHERE id=1",
            result));
    assertEquals(1, result.affectedRows());
    assertRow(session, result, 1, 23, 10, 500, epochDay(2024, 1, 3));

    ParameterSet parameters = new ParameterSet(2, 0);
    assertEquals(StatusCode.OK, parameters.appendFixed(SqlTypeDescriptor.BIGINT, 7));
    assertEquals(StatusCode.OK, parameters.appendFixed(SqlTypeDescriptor.BIGINT, 1));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "UPDATE mutation_values SET a=a+? WHERE id+0=?", parameters, result));
    assertEquals(1, result.affectedRows());
    assertRow(session, result, 1, 30, 10, 500, epochDay(2024, 1, 3));
    parameters.reset();
    assertEquals(StatusCode.OK, parameters.appendFixed(SqlTypeDescriptor.BIGINT, 41));
    assertEquals(StatusCode.OK, parameters.appendFixed(SqlTypeDescriptor.BIGINT, 2));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "UPDATE mutation_values SET a=?+1 WHERE id=?", parameters, result));
    assertEquals(1, result.affectedRows());
    assertRow(session, result, 2, 42, 40, 350, epochDay(2024, 1, 3));

    assertEquals(
        StatusCode.OK,
        session.execute(
            "UPDATE mutation_values SET captured=observed AT TIME ZONE 'Europe/London' "
                + "WHERE id+0=2",
            result));
    assertEquals(1, result.affectedRows());
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.execute(
            "UPDATE mutation_values SET captured=observed AT TIME ZONE 'Europe/London' "
                + "WHERE id IN (2,3)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT captured FROM mutation_values WHERE id=2", result));
    assertFalse(result.isNull(0));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "UPDATE mutation_values SET observed=LOCALTIMESTAMP WHERE id IN (1,2)",
            result));
    assertEquals(2, result.affectedRows());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT observed FROM mutation_values WHERE id=1", result));
    long statementTime = result.valueAt(0);
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT observed FROM mutation_values WHERE id=2", result));
    assertEquals(statementTime, result.valueAt(0));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute(
            "UPDATE mutation_values SET a=NULL+1 WHERE id=1", result));
    assertRow(session, result, 1, 30, 10, 500, epochDay(2024, 1, 3));

    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    opened.reset();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    sessionResult.reset();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    session = sessionResult.session();
    assertRow(session, result, 1, 30, 10, 500, epochDay(2024, 1, 3));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void rollsBackMutationErrorsAndPreservesLegacyMultiRowInsert(
      @TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult result = new CommandResult();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE guarded_values (id BIGINT PRIMARY KEY,value BIGINT,"
                + "day DATE CHECK(day>=DATE '2020-01-01'),"
                + "observed TIMESTAMP(6),captured TIMESTAMP(6) WITH TIME ZONE)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX guarded_value_index ON guarded_values(value)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO guarded_values VALUES"
                + "(1,-9223372036854775808,DATE '2024-01-01',NULL,NULL),"
                + "(2,20,DATE '9999-12-31',NULL,NULL),"
                + "(3,30,NULL,NULL,NULL)",
            result));
    assertEquals(3, result.affectedRows());
    ParameterSet insertParameters = new ParameterSet(2, 0);
    assertEquals(StatusCode.OK, insertParameters.appendFixed(SqlTypeDescriptor.BIGINT, 4));
    assertEquals(StatusCode.OK, insertParameters.appendFixed(SqlTypeDescriptor.BIGINT, 40));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO guarded_values VALUES(?+0,?+1,NULL,NULL,NULL)",
            insertParameters,
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT value FROM guarded_values WHERE id=4", result));
    assertEquals(41, result.valueAt(0));
    insertParameters.reset();
    assertEquals(StatusCode.OK, insertParameters.appendFixed(SqlTypeDescriptor.BIGINT, 5));
    assertEquals(
        StatusCode.PARAMETER_COUNT_MISMATCH,
        session.execute(
            "INSERT INTO guarded_values VALUES(?+0,?+1,NULL,NULL,NULL)",
            insertParameters,
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO guarded_values VALUES(5,NULL+1,NULL,NULL,NULL)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "UPDATE guarded_values SET value=1+NULL WHERE id=5", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT value FROM guarded_values WHERE id=5", result));
    assertTrue(result.isNull(0));

    assertEquals(
        StatusCode.DATETIME_FIELD_OVERFLOW,
        session.execute(
            "UPDATE guarded_values SET day=day+1 WHERE id IN (1,2)", result));
    assertDate(session, result, 1, epochDay(2024, 1, 1));
    assertEquals(
        StatusCode.CHECK_VIOLATION,
        session.execute(
            "INSERT INTO guarded_values VALUES"
                + "(10,10,DATE '2024-01-01',NULL,NULL),"
                + "(11,11,DATE '2020-01-01'-1,NULL,NULL)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT COUNT(*) FROM guarded_values WHERE id=10", result));
    assertEquals(0, result.valueAt(0));
    assertDate(session, result, 2, epochDay(9999, 12, 31));
    assertEquals(
        StatusCode.CHECK_VIOLATION,
        session.execute(
            "UPDATE guarded_values SET day=DATE '2020-01-01'-1 WHERE id=1", result));
    assertDate(session, result, 1, epochDay(2024, 1, 1));

    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.execute(
            "UPDATE guarded_values SET captured=observed AT TIME ZONE 'Not/A_Zone' "
                + "WHERE id=999",
            result));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        session.execute(
            "INSERT INTO guarded_values VALUES(4,value+1,NULL,NULL,NULL)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "UPDATE guarded_values SET value=value+1 "
                + "WHERE day+0 IS NULL AND id=3",
            result));
    assertEquals(1, result.affectedRows());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT value FROM guarded_values WHERE id=3", result));
    assertEquals(31, result.valueAt(0));
    assertFalse(result.isNull(0));
    assertSingleRow(
        session,
        result,
        "SELECT id FROM guarded_values WHERE value=31",
        3);
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT COUNT(*) FROM guarded_values WHERE value=30", result));
    assertEquals(0, result.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute("DELETE FROM guarded_values WHERE value+0=41", result));
    assertEquals(1, result.affectedRows());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT COUNT(*) FROM guarded_values WHERE id=4", result));
    assertEquals(0, result.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute("DELETE FROM guarded_values WHERE value+0=1", result));
    assertEquals(0, result.affectedRows());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT COUNT(*) FROM guarded_values WHERE id=5", result));
    assertEquals(1, result.valueAt(0));
    assertEquals(
        StatusCode.DATETIME_FIELD_OVERFLOW,
        session.execute(
            "UPDATE guarded_values SET value=value+1 "
                + "WHERE day+1=DATE '0001-01-01'",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT value FROM guarded_values WHERE id=1", result));
    assertEquals(Long.MIN_VALUE, result.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "UPDATE guarded_values SET value=value+1 WHERE id=1 "
                + "AND day+0=DATE '2024-01-02'",
            result));
    assertEquals(0, result.affectedRows());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT value FROM guarded_values WHERE id=1", result));
    assertEquals(Long.MIN_VALUE, result.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "UPDATE guarded_values SET value=-9223372036854775808+1 WHERE id=1",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT value FROM guarded_values WHERE id=1", result));
    assertEquals(Long.MIN_VALUE + 1, result.valueAt(0));

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE mut_parents(id BIGINT PRIMARY KEY,pad BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE mut_children(id BIGINT PRIMARY KEY,"
                + "parent_id BIGINT REFERENCES mut_parents(id))",
            result));
    assertEquals(StatusCode.OK, session.execute("INSERT INTO mut_parents VALUES(1,0)", result));
    assertEquals(StatusCode.OK, session.execute("INSERT INTO mut_children VALUES(1,1)", result));
    assertEquals(
        StatusCode.FOREIGN_KEY_VIOLATION,
        session.execute(
            "UPDATE mut_children SET parent_id=parent_id+1 WHERE id=1", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT parent_id FROM mut_children WHERE id=1", result));
    assertEquals(1, result.valueAt(0));

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE uniq_mutations(id BIGINT PRIMARY KEY,value BIGINT UNIQUE)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO uniq_mutations VALUES(1,10),(2,20)", result));
    assertEquals(
        StatusCode.UNIQUE_VIOLATION,
        session.execute(
            "UPDATE uniq_mutations SET value=value-10 WHERE id=2", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT value FROM uniq_mutations WHERE id=2", result));
    assertEquals(20, result.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id FROM uniq_mutations WHERE value=20", result));
    assertEquals(2, result.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id FROM uniq_mutations WHERE value=10", result));
    assertEquals(1, result.valueAt(0));

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertRow(
      RiverSession session,
      CommandResult result,
      long id,
      long a,
      long b,
      long amount,
      long day) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT a,b,amount,day FROM mutation_values WHERE id=" + id, result));
    assertEquals(a, result.valueAt(0));
    assertEquals(b, result.valueAt(1));
    assertEquals(amount, result.valueAt(2));
    assertEquals(day, result.valueAt(3));
    assertFalse(result.isNull(3));
  }

  private static void assertDate(
      RiverSession session, CommandResult result, long id, long day) {
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT day FROM guarded_values WHERE id=" + id, result));
    assertTrue(result.rowAvailable());
    assertEquals(day, result.valueAt(0));
  }

  private static void assertSingleRow(
      RiverSession session,
      CommandResult result,
      String sql,
      long expected) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.beginQuery(sql, opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    assertTrue(row.isAvailable());
    assertEquals(expected, row.valueAt(0));
    assertEquals(StatusCode.OK, query.next(row));
    assertFalse(row.isAvailable());
    assertEquals(StatusCode.OK, query.close(result));
  }

  private static long epochDay(int year, int month, int day) {
    return LocalDate.of(year, month, day).toEpochDay();
  }
}
