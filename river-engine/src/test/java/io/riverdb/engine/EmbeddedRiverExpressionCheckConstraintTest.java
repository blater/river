package io.riverdb.engine;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.SessionOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EmbeddedRiverExpressionCheckConstraintTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4558505243484543L, 0x4b30303030303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void persistsAndEvaluatesDeterministicCheckPrograms(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult result = new CommandResult();

    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        session.execute(
            "CREATE TABLE rejected_numeric (id BIGINT "
                + "CHECK (CAST(id AS BIGINT)>0) PRIMARY KEY, value BIGINT)",
            result));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        session.execute(
            "CREATE TABLE rejected_text (id BIGINT PRIMARY KEY, label VARCHAR(8) "
                + "CHECK (label>'a'))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE checked_boolean (id BIGINT PRIMARY KEY, enabled BOOLEAN "
                + "CHECK (enabled>FALSE))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO checked_boolean VALUES (1,TRUE)", result));
    assertEquals(
        StatusCode.CHECK_VIOLATION,
        session.execute("INSERT INTO checked_boolean VALUES (2,FALSE)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE approximate_checks ("
                + "id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,"
                + "single_value REAL CHECK(single_value<0),"
                + "double_value DOUBLE PRECISION CHECK(double_value<0),"
                + "enabled BOOLEAN CHECK(enabled>FALSE))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO approximate_checks(single_value,double_value,enabled) "
                + "VALUES (-1.0,-2.0,TRUE)",
            result));
    assertEquals(
        StatusCode.CHECK_VIOLATION,
        session.execute(
            "INSERT INTO approximate_checks(single_value,double_value,enabled) "
                + "VALUES (1.0,-2.0,TRUE)",
            result));
    assertEquals(
        StatusCode.CHECK_VIOLATION,
        session.execute(
            "INSERT INTO approximate_checks(single_value,double_value,enabled) "
                + "VALUES (-1.0,2.0,TRUE)",
            result));
    assertEquals(
        StatusCode.CHECK_VIOLATION,
        session.execute(
            "INSERT INTO approximate_checks(single_value,double_value,enabled) "
                + "VALUES (-1.0,-2.0,FALSE)",
            result));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        session.execute(
            "CREATE TABLE rejected_zone (id BIGINT PRIMARY KEY, observed TIMESTAMP(6) "
                + "CHECK (observed AT TIME ZONE 'UTC'>"
                + "TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00:00'))",
            result));

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE checked_events ("
                + "id BIGINT PRIMARY KEY, "
                + "day DATE DEFAULT DATE '2024-02-10' "
                + "CHECK (EXTRACT(DAY FROM day)>=10), "
                + "next_day DATE CHECK (next_day+1<DATE '2024-03-01'), "
                + "observed TIMESTAMP(6) "
                + "CHECK (CAST(observed AS DATE)>=DATE '2024-01-01'), "
                + "captured TIMESTAMP(6) WITH TIME ZONE "
                + "CHECK (EXTRACT(YEAR FROM captured)>=1970))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO checked_events VALUES (1,DATE '2024-02-10',"
                + "DATE '2024-02-28',TIMESTAMP '2024-02-10 00:00:00',"
                + "TIMESTAMP WITH TIME ZONE '2024-02-10 00:00:00+00:00')",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO checked_events VALUES (2,DEFAULT,NULL,NULL,NULL)", result));
    assertEquals(
        StatusCode.CHECK_VIOLATION,
        session.execute(
            "INSERT INTO checked_events VALUES (3,DATE '2024-02-01',"
                + "DATE '2024-02-28',NULL,NULL)",
            result));
    assertEquals(
        StatusCode.CHECK_VIOLATION,
        session.execute(
            "INSERT INTO checked_events VALUES (3,DATE '2024-02-10',"
                + "DATE '2024-02-29',NULL,NULL)",
            result));
    assertEquals(
        StatusCode.DATETIME_FIELD_OVERFLOW,
        session.execute(
            "INSERT INTO checked_events VALUES (3,DATE '2024-02-10',NULL,"
                + "TIMESTAMP '2024-02-10 12:00:00',NULL)",
            result));
    assertEquals(
        StatusCode.DATETIME_FIELD_OVERFLOW,
        session.execute(
            "INSERT INTO checked_events VALUES (3,DATE '2024-02-10',"
                + "DATE '9999-12-31',NULL,NULL)",
            result));
    assertEquals(StatusCode.OK, session.execute("SET TIME ZONE '+14:00'", result));
    assertEquals(
        StatusCode.CHECK_VIOLATION,
        session.execute(
            "INSERT INTO checked_events VALUES (4,DATE '2024-02-10',NULL,NULL,"
                + "TIMESTAMP WITH TIME ZONE '1970-01-01 00:30:00+01:00')",
            result));
    assertEquals(StatusCode.OK, session.execute("SET TIME ZONE 'UTC'", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE mixed_check (id BIGINT PRIMARY KEY, alarm TIME(6) "
                + "CHECK (alarm>TIME '01:00:00.1'))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO mixed_check VALUES (1,TIME '01:00:00.100001')", result));
    assertEquals(
        StatusCode.CHECK_VIOLATION,
        session.execute(
            "INSERT INTO mixed_check VALUES (2,TIME '01:00:00.100000')", result));
    String bounded = "+1-1+1-1+1-1+1-1";
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE wider_checks (id BIGINT PRIMARY KEY, "
                + "a DATE CHECK (a" + bounded + ">DATE '0001-01-01'), "
                + "b DATE CHECK (b" + bounded + ">DATE '0001-01-01'))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO wider_checks VALUES "
                + "(1,DATE '2024-01-01',DATE '2024-01-02')",
            result));
    assertEquals(
        StatusCode.CHECK_VIOLATION,
        session.execute(
            "INSERT INTO wider_checks VALUES "
                + "(2,DATE '0001-01-01',DATE '2024-01-02')",
            result));

    assertEquals(
        StatusCode.CHECK_VIOLATION,
        session.execute(
            "UPDATE checked_events SET day=DATE '2024-02-01' WHERE id=1", result));
    assertEquals(
        StatusCode.CHECK_VIOLATION,
        session.execute(
            "UPDATE checked_events SET day=DATE '2024-02-01',"
                + "next_day=DATE '9999-12-31' WHERE id=1",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT day FROM checked_events WHERE id=1", result));
    assertEquals(19_763, result.valueAt(0));
    assertEquals(
        StatusCode.CHECK_VIOLATION,
        session.execute(
            "INSERT INTO checked_events VALUES "
                + "(10,DATE '2024-02-10',NULL,NULL,NULL),"
                + "(11,DATE '2024-02-01',NULL,NULL,NULL)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT COUNT(*) FROM checked_events WHERE id>=10", result));
    assertEquals(0, result.valueAt(0));

    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX checked_events_day ON checked_events(day)", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    session = sessionResult.session();
    assertEquals(
        StatusCode.CHECK_VIOLATION,
        session.execute(
            "UPDATE checked_events SET next_day=DATE '2024-02-29' WHERE id=1", result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO checked_events VALUES (3,DEFAULT,NULL,NULL,NULL)", result));
    assertEquals(
        StatusCode.CHECK_VIOLATION,
        session.execute(
            "INSERT INTO mixed_check VALUES (2,TIME '01:00:00.100000')", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }
}
