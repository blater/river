package io.riverdb.engine;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.SessionOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EmbeddedRiverResultNullabilityTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4e554c4c4142494cL, 0x4954593030303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void derivesNullabilityForRowsAggregatesGroupsJoinsAndPlans(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult result = new CommandResult();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE nullable_values (id BIGINT PRIMARY KEY, "
                + "optional BIGINT, required BIGINT NOT NULL, observed TIMESTAMP(6))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE nullable_labels (id BIGINT PRIMARY KEY, label BIGINT NOT NULL)",
            result));

    assertNullability(
        session,
        "SELECT id,optional,required,observed,CAST(observed AS DATE) AS converted "
            + "FROM nullable_values ORDER BY id",
        false, true, false, true, true);
    assertNullability(session, "SELECT COUNT(*) FROM nullable_values", false);
    assertNullability(session, "SELECT SUM(optional) FROM nullable_values", true);
    assertNullability(
        session,
        "SELECT optional,COUNT(*) FROM nullable_values GROUP BY optional ORDER BY optional",
        true, false);
    assertNullability(
        session,
        "SELECT DISTINCT optional FROM nullable_values ORDER BY optional",
        true);
    assertNullability(
        session,
        "SELECT nullable_values.id,nullable_labels.label FROM nullable_values "
            + "LEFT JOIN nullable_labels ON nullable_values.id=nullable_labels.id",
        false, true);
    assertNullability(session, "EXPLAIN SELECT id FROM nullable_values", false, false, true);
    assertNullability(session, "SHOW TABLES", false, false);
    assertNullability(
        session, "SHOW INDEXES FROM nullable_values", true, false, false, false, false);

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertNullability(
      RiverSession session, String sql, boolean... expected) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.beginQuery(sql, opened), sql);
    RiverQuery query = opened.query();
    assertEquals(expected.length, query.columnCount(), sql);
    for (int index = 0; index < expected.length; index++) {
      assertEquals(expected[index], query.columnIsNullable(index), sql + " column " + index);
    }
    assertEquals(StatusCode.OK, query.close(new CommandResult()), sql);
  }
}
