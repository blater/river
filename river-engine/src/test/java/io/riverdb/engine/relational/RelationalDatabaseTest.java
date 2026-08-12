package io.riverdb.engine.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.checkpoint.CheckpointResult;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RelationalDatabaseTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(733, 739);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void commitsCatalogAndRowsThroughDurableWalQuorum(@TempDir Path root)
      throws IOException {
    Path primary = Files.createDirectory(root.resolve("primary"));
    Path followerOne = Files.createDirectory(root.resolve("follower-one"));
    Path followerTwo = Files.createDirectory(root.resolve("follower-two"));
    Path[] followers = {followerOne, followerTwo};
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.createWithDurableWalQuorum(
            primary, followers, 2, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    assertEquals(2, database.requiredDurableNodeCount());
    assertEquals(3, database.availableDurableNodeCount());
    assertEquals(true, database.quorumDurableCommitSequence() > 0);

    TableDefinition accounts = new TableDefinition();
    assertEquals(StatusCode.OK, database.createTable("accounts", accounts));
    RelationalSessionOpenResult sessionResult = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RelationalSession session = sessionResult.session();
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.insert(accounts, 7, row(700)));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(outcome.commitSequence(), database.quorumDurableCommitSequence());
    assertEquals(true, database.replicatedWalPayloadBytes() > 0);
    assertEquals(StatusCode.CONFLICT, database.checkpoint(new CheckpointResult()));
    assertEquals(StatusCode.OK, database.close());

    assertEquals(
        StatusCode.OK,
        RelationalDatabase.openWithDurableWalQuorum(
            primary, followers, 2, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    session = sessionResult.session();
    accounts.reset();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.resolveTable("accounts", accounts));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, session.fetch(accounts, 7, fetched));
    assertEquals(700, value(fetched));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(3, database.availableDurableNodeCount());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void resumesBoundedDroppingTableCleanupAfterReopen(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 6, opened));
    RelationalDatabase database = opened.database();
    TableDefinition accounts = new TableDefinition();
    assertEquals(StatusCode.OK, database.createTable("accounts", accounts));
    RelationalSessionOpenResult sessionResult = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RelationalSession session = sessionResult.session();
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    for (int key = 0; key < 60; key++) {
      assertEquals(StatusCode.OK, session.insert(accounts, key, row(key)));
    }
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(
        StatusCode.OK,
        database.createUniqueValueIndex("accounts_value", "accounts"));

    assertEquals(StatusCode.RETRY, database.dropTable("accounts", 1));
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    session = sessionResult.session();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(
        StatusCode.CONFLICT,
        session.resolveTable("accounts", new TableDefinition()));
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(StatusCode.OK, database.close());

    assertEquals(
        StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 6, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, database.dropTable("accounts"));
    assertEquals(StatusCode.CONFLICT, database.dropTable("accounts"));
    assertEquals(
        StatusCode.OK,
        database.createTable("accounts", new TableDefinition()));
    assertEquals(
        StatusCode.OK,
        database.createUniqueValueIndex("accounts_value", "accounts"));
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void catalogsTwoTablesAndCommitsAcrossBoth(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 6, opened));
    RelationalDatabase database = opened.database();
    TableDefinition accounts = new TableDefinition();
    TableDefinition papers = new TableDefinition();
    assertEquals(StatusCode.OK, database.createTable("accounts", accounts));
    assertEquals(StatusCode.OK, database.createTable("papers", papers));
    assertEquals(1, accounts.tableId());
    assertEquals(2, papers.tableId());
    assertEquals(
        StatusCode.CONFLICT,
        database.createTable("accounts", new TableDefinition()));

    RelationalSessionOpenResult sessionResult = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RelationalSession session = sessionResult.session();
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.insert(accounts, 7, row(700)));
    assertEquals(StatusCode.OK, session.insert(papers, 7, row(701)));
    assertEquals(StatusCode.OK, session.commit(outcome));
    long committedAt = outcome.commitSequence();
    CheckpointResult checkpoint = new CheckpointResult();
    assertEquals(StatusCode.OK, database.checkpoint(checkpoint));
    assertEquals(5, checkpoint.rowCount());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(
        StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 6, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    session = sessionResult.session();
    accounts.reset();
    papers.reset();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.resolveTable("accounts", accounts));
    assertEquals(StatusCode.OK, session.resolveTable("papers", papers));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, session.fetch(accounts, 7, fetched));
    assertEquals(700, value(fetched));
    assertEquals(StatusCode.OK, session.fetch(papers, 7, fetched));
    assertEquals(701, value(fetched));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(checkpoint.commitSequence(), outcome.commitSequence());
    assertEquals(true, checkpoint.commitSequence() >= committedAt);
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void rejectsInvalidNamesKeysAndForeignDefinitions(@TempDir Path firstRoot, @TempDir Path secondRoot) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(firstRoot, DATABASE, GENERATION, 4, opened));
    RelationalDatabase first = opened.database();
    TableDefinition table = new TableDefinition();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, first.createTable("bad-name", table));
    assertEquals(StatusCode.OK, first.createTable("good_name", table));

    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(
            secondRoot,
            DatabaseIncarnation.of(743, 751),
            GENERATION,
            4,
            opened));
    RelationalDatabase second = opened.database();
    TableDefinition local = new TableDefinition();
    assertEquals(StatusCode.OK, second.createTable("local_table", local));
    RelationalSessionOpenResult sessions = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, second.createSession(sessions));
    RelationalSession session = sessions.session();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, session.insert(table, 1, row(1)));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.insert(local, RelationalKey.MAXIMUM_USER_KEY + 1, row(1)));
    assertEquals(StatusCode.OK, session.abort(new TransactionOutcome()));
    assertEquals(StatusCode.OK, second.close());
    assertEquals(StatusCode.OK, first.close());
  }

  @Test
  void indexDdlWaitsForTransactionsAndFencesStaleDefinitions(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 4, opened));
    RelationalDatabase database = opened.database();
    TableDefinition stale = new TableDefinition();
    assertEquals(StatusCode.OK, database.createTable("accounts", stale));

    RelationalSessionOpenResult sessions = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessions));
    RelationalSession session = sessions.session();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.resolveTable("accounts", stale));
    assertEquals(
        StatusCode.RETRY,
        database.createUniqueValueIndex("accounts_value", "accounts"));
    assertEquals(StatusCode.OK, session.abort(new TransactionOutcome()));

    assertEquals(
        StatusCode.OK,
        database.createUniqueValueIndex("accounts_value", "accounts"));
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.insertLong(stale, 1, 10, row(10)));
    TableDefinition current = new TableDefinition();
    assertEquals(StatusCode.OK, session.resolveTable("accounts", current));
    assertEquals(StatusCode.OK, session.insertLong(current, 1, 10, row(10)));
    assertEquals(StatusCode.OK, session.commit(new TransactionOutcome()));
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void resumesBoundedLargeIndexBuildAfterReopen(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    TableDefinition events = new TableDefinition();
    assertEquals(StatusCode.OK, database.createTable("events", events));
    RelationalSessionOpenResult sessions = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessions));
    RelationalSession session = sessions.session();
    TransactionOutcome outcome = new TransactionOutcome();
    for (int first = 0; first < 300; first += 40) {
      assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
      for (int key = first; key < Math.min(first + 40, 300); key++) {
        assertEquals(StatusCode.OK, session.insertLong(events, key, key * 10L, row(key * 10L)));
      }
      assertEquals(StatusCode.OK, session.commit(outcome));
    }

    assertEquals(
        StatusCode.RETRY,
        database.createUniqueValueIndex("events_value", "events", 1));
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    TableDefinition building = new TableDefinition();
    assertEquals(StatusCode.OK, session.resolveTable("events", building));
    assertEquals(true, building.hasBuildingUniqueValueIndex());
    assertEquals(StatusCode.RETRY, session.updateLong(building, 7, 71, row(71)));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, session.fetch(building, 7, fetched));
    assertEquals(70, value(fetched));
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(StatusCode.OK, database.close());

    assertEquals(
        StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, database.createSession(sessions));
    session = sessions.session();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    building.reset();
    assertEquals(StatusCode.OK, session.resolveTable("events", building));
    assertEquals(StatusCode.RETRY, session.deleteLong(building, 9));
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(
        StatusCode.OK,
        database.createUniqueValueIndex("events_value", "events"));
    assertEquals(StatusCode.OK, database.createSession(sessions));
    session = sessions.session();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    events.reset();
    assertEquals(StatusCode.OK, session.resolveTable("events", events));
    assertEquals(false, events.hasBuildingUniqueValueIndex());
    assertEquals(true, events.hasUniqueValueIndex());
    ValueIndexLookupResult indexed = new ValueIndexLookupResult();
    assertEquals(StatusCode.OK, session.fetchByUniqueValue(events, 0, indexed));
    assertEquals(0, indexed.key());
    assertEquals(StatusCode.OK, session.fetchByUniqueValue(events, 470, indexed));
    assertEquals(47, indexed.key());
    assertEquals(StatusCode.OK, session.fetchByUniqueValue(events, 2990, indexed));
    assertEquals(299, indexed.key());
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void resumesBoundedDroppingIndexCleanupAfterReopen(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    TableDefinition events = new TableDefinition();
    assertEquals(StatusCode.OK, database.createTable("drop_events", events));
    RelationalSessionOpenResult sessions = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessions));
    RelationalSession session = sessions.session();
    TransactionOutcome outcome = new TransactionOutcome();
    for (int first = 0; first < 300; first += 40) {
      assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
      for (int key = first; key < Math.min(first + 40, 300); key++) {
        assertEquals(
            StatusCode.OK,
            session.insertLong(events, key, key * 10L, row(key * 10L)));
      }
      assertEquals(StatusCode.OK, session.commit(outcome));
    }
    assertEquals(
        StatusCode.OK,
        database.createUniqueValueIndex("drop_events_value", "drop_events"));
    assertEquals(
        StatusCode.RETRY,
        database.dropValueIndex("drop_events_value", "drop_events", 1));
    assertEquals(StatusCode.OK, database.createSession(sessions));
    session = sessions.session();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    TableDefinition dropping = new TableDefinition();
    assertEquals(StatusCode.OK, session.resolveTable("drop_events", dropping));
    assertEquals(false, dropping.hasUniqueValueIndex());
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(StatusCode.OK, database.close());

    assertEquals(
        StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(
        StatusCode.OK,
        database.dropValueIndex("drop_events_value", "drop_events"));
    assertEquals(
        StatusCode.CONFLICT,
        database.dropValueIndex("drop_events_value", "drop_events"));
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void resumesDuplicateIndexBuildWithoutRepeatingEntries(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 9, opened));
    RelationalDatabase database = opened.database();
    TableDefinition events = new TableDefinition();
    assertEquals(StatusCode.OK, database.createTable("events", events));
    RelationalSessionOpenResult sessions = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessions));
    RelationalSession session = sessions.session();
    TransactionOutcome outcome = new TransactionOutcome();
    for (int first = 0; first < 150; first += 50) {
      assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
      for (int key = first; key < first + 50; key++) {
        assertEquals(StatusCode.OK, session.insertLong(events, key, 10, row(10)));
      }
      assertEquals(StatusCode.OK, session.commit(outcome));
    }

    assertEquals(
        StatusCode.RETRY,
        database.createValueIndex("events_value", "events", 1));
    assertEquals(StatusCode.OK, database.close());
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 9, opened));
    database = opened.database();
    assertEquals(
        StatusCode.OK,
        database.createValueIndex("events_value", "events", 4));
    assertEquals(StatusCode.OK, database.createSession(sessions));
    session = sessions.session();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    events.reset();
    assertEquals(StatusCode.OK, session.resolveTable("events", events));
    RelationalScanCursor cursor = new RelationalScanCursor();
    RelationalScanResult scan = new RelationalScanResult();
    ValueIndexLookupResult indexed = new ValueIndexLookupResult();
    assertEquals(StatusCode.OK, session.beginValueScan(events, 1, 10, 11, cursor));
    boolean[] seen = new boolean[150];
    int count = 0;
    StatusCode status;
    while ((status = session.nextValueScan(events, cursor, scan, indexed)).isOk()) {
      assertEquals(10, value(indexed.row()));
      assertEquals(false, seen[(int) indexed.key()]);
      seen[(int) indexed.key()] = true;
      count++;
    }
    assertEquals(StatusCode.CONFLICT, status);
    assertEquals(150, count);
    assertEquals(StatusCode.OK, session.closeScan(cursor));
    RelationalScanCursor outer = new RelationalScanCursor();
    RelationalScanCursor exact = new RelationalScanCursor();
    assertEquals(StatusCode.OK, session.beginScan(events, outer));
    assertEquals(
        StatusCode.OK,
        session.beginNonUniqueValueLookup(events, 1, 10, exact));
    count = 0;
    while ((status = session.nextNonUniqueValueLookup(events, exact, indexed)).isOk()) {
      assertEquals(10, value(indexed.row()));
      count++;
    }
    assertEquals(StatusCode.CONFLICT, status);
    assertEquals(150, count);
    assertEquals(StatusCode.OK, session.closeScan(exact));
    assertEquals(StatusCode.OK, session.closeScan(outer));
    assertEquals(StatusCode.OK, exact.reset());
    assertEquals(
        StatusCode.CONFLICT,
        session.beginNonUniqueValueLookup(events, 1, 11, exact));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, database.close());
  }

  private static ByteBuffer row(long value) {
    ByteBuffer row = ByteBuffer.allocateDirect(2 * Long.BYTES);
    row.putLong(0, value);
    row.position(0);
    row.limit(2 * Long.BYTES);
    return row;
  }

  private static long value(HeapRowResult result) {
    ByteBuffer row = ByteBuffer.allocate(2 * Long.BYTES);
    assertEquals(StatusCode.OK, result.copyTo(row));
    return row.getLong(0);
  }
}
