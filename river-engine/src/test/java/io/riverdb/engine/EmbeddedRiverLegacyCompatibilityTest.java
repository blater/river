package io.riverdb.engine;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
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

/** Independent River fixtures selected from the approved Ingres test oracles. */
final class EmbeddedRiverLegacyCompatibilityTest {
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void distinctCollapsesDuplicateNullValues(@TempDir Path root) {
    try (Fixture fixture = Fixture.create(root, 1)) {
      CommandResult command = new CommandResult();
      assertEquals(
          StatusCode.OK,
          fixture.session.execute(
              "CREATE TABLE legacy_null_values "
                  + "(id BIGINT PRIMARY KEY, optional BIGINT)",
              command));
      assertEquals(
          StatusCode.OK,
          fixture.session.execute(
              "INSERT INTO legacy_null_values VALUES (1,NULL),(2,NULL),(3,7)",
              command));

      QueryOpenResult opened = new QueryOpenResult();
      assertEquals(
          StatusCode.OK,
          fixture.session.beginQuery(
              "SELECT DISTINCT optional FROM legacy_null_values",
              opened));
      RiverQuery query = opened.query();
      RowResult row = new RowResult();
      int nullRows = 0;
      int valueRows = 0;
      while (true) {
        assertEquals(StatusCode.OK, query.next(row));
        if (!row.isAvailable()) break;
        if (row.isNull(0)) {
          nullRows++;
        } else {
          assertEquals(7, row.valueAt(0));
          valueRows++;
        }
      }
      assertEquals(1, nullRows);
      assertEquals(1, valueRows);
      assertEquals(StatusCode.OK, query.close(command));
    }
  }

  @Test
  void leftJoinRetainsDuplicateAndUnmatchedOuterRows(@TempDir Path root) {
    try (Fixture fixture = Fixture.create(root, 2)) {
      CommandResult command = new CommandResult();
      assertEquals(
          StatusCode.OK,
          fixture.session.execute(
              "CREATE TABLE legacy_outer "
                  + "(id BIGINT PRIMARY KEY, join_key BIGINT NOT NULL)",
              command));
      assertEquals(
          StatusCode.OK,
          fixture.session.execute(
              "CREATE TABLE legacy_inner "
                  + "(id BIGINT PRIMARY KEY, join_key BIGINT NOT NULL)",
              command));
      assertEquals(
          StatusCode.OK,
          fixture.session.execute(
              "INSERT INTO legacy_outer VALUES (1,10),(2,10),(3,20)",
              command));
      assertEquals(
          StatusCode.OK,
          fixture.session.execute(
              "INSERT INTO legacy_inner VALUES (100,10)",
              command));

      QueryOpenResult opened = new QueryOpenResult();
      assertEquals(
          StatusCode.OK,
          fixture.session.beginQuery(
              "SELECT legacy_outer.id,legacy_inner.id FROM legacy_outer "
                  + "LEFT JOIN legacy_inner "
                  + "ON legacy_outer.join_key=legacy_inner.join_key",
              opened));
      RiverQuery query = opened.query();
      RowResult row = new RowResult();
      boolean firstMatch = false;
      boolean duplicateMatch = false;
      boolean unmatched = false;
      int rows = 0;
      while (true) {
        assertEquals(StatusCode.OK, query.next(row));
        if (!row.isAvailable()) break;
        rows++;
        long outer = row.valueAt(0);
        if (outer == 1 || outer == 2) {
          assertFalse(row.isNull(1));
          assertEquals(100, row.valueAt(1));
          firstMatch |= outer == 1;
          duplicateMatch |= outer == 2;
        } else {
          assertEquals(3, outer);
          assertTrue(row.isNull(1));
          unmatched = true;
        }
      }
      assertEquals(3, rows);
      assertTrue(firstMatch);
      assertTrue(duplicateMatch);
      assertTrue(unmatched);
      assertEquals(StatusCode.OK, query.close(command));
    }
  }

  @Test
  void groupedAggregateHavingEmitsOnlyQualifyingGroups(@TempDir Path root) {
    try (Fixture fixture = Fixture.create(root, 3)) {
      CommandResult command = new CommandResult();
      assertEquals(
          StatusCode.OK,
          fixture.session.execute(
              "CREATE TABLE legacy_group_values "
                  + "(id BIGINT PRIMARY KEY, group_id BIGINT NOT NULL, "
                  + "amount BIGINT NOT NULL)",
              command));
      assertEquals(
          StatusCode.OK,
          fixture.session.execute(
              "INSERT INTO legacy_group_values VALUES "
                  + "(1,1,20),(2,1,30),(3,2,5),(4,2,10)",
              command));

      QueryOpenResult opened = new QueryOpenResult();
      assertEquals(
          StatusCode.OK,
          fixture.session.beginQuery(
              "SELECT group_id,SUM(amount) FROM legacy_group_values "
                  + "GROUP BY group_id HAVING SUM(amount)>20",
              opened));
      RiverQuery query = opened.query();
      RowResult row = new RowResult();
      assertEquals(StatusCode.OK, query.next(row));
      assertTrue(row.isAvailable());
      assertEquals(1, row.valueAt(0));
      assertEquals(50, row.valueAt(1));
      assertEquals(StatusCode.OK, query.next(row));
      assertFalse(row.isAvailable());
      assertEquals(StatusCode.OK, query.close(command));
    }
  }

  private static final class Fixture implements AutoCloseable {
    private final RiverDatabase database;
    private final RiverSession session;

    private Fixture(RiverDatabase openedDatabase, RiverSession openedSession) {
      database = openedDatabase;
      session = openedSession;
    }

    static Fixture create(Path root, long discriminator) {
      DatabaseOpenResult databaseResult = new DatabaseOpenResult();
      DatabaseIncarnation incarnation = DatabaseIncarnation.of(
          0x4c45474143593030L + discriminator,
          0x554f354649585430L + discriminator);
      assertEquals(
          StatusCode.OK,
          EmbeddedRiver.create(databaseRequest(4), root, incarnation, GENERATION, 4, databaseResult));
      RiverDatabase database = databaseResult.database();
      SessionOpenResult sessionResult = new SessionOpenResult();
      assertEquals(StatusCode.OK, database.createSession(sessionResult));
      return new Fixture(database, sessionResult.session());
    }

    @Override
    public void close() {
      assertEquals(StatusCode.OK, session.close());
      assertEquals(StatusCode.OK, database.close());
    }
  }
}
