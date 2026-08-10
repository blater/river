package io.riverdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EmbeddedDatabaseTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(701, 709);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void createsTransactsClosesAndReopens(@TempDir Path root) {
    EmbeddedDatabaseOpenResult opened = new EmbeddedDatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedDatabase.create(root, DATABASE, GENERATION, 4, opened));
    EmbeddedDatabase database = opened.database();
    EmbeddedSessionOpenResult sessionResult = new EmbeddedSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(128, sessionResult));
    IndexedTransactionSession session = sessionResult.session();
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.insert(41, row(410)));
    assertEquals(StatusCode.CONFLICT, database.close());
    assertEquals(StatusCode.OK, session.commit(outcome));
    long committedAt = outcome.commitSequence();
    assertEquals(committedAt, database.currentCommitSequence());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(
        StatusCode.OK,
        EmbeddedDatabase.openExisting(root, DATABASE, GENERATION, 4, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, database.createSession(128, sessionResult));
    session = sessionResult.session();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, session.fetchByKey(41, fetched));
    assertEquals(410, value(fetched));
    assertEquals(StatusCode.OK, session.update(41, row(411)));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void strictCreateAndOpenDoNotInventOrReplaceDatabaseFiles(@TempDir Path root) {
    EmbeddedDatabaseOpenResult opened = new EmbeddedDatabaseOpenResult();
    assertEquals(
        StatusCode.CONFLICT,
        EmbeddedDatabase.openExisting(root, DATABASE, GENERATION, 2, opened));
    assertEquals(StatusCode.OK, EmbeddedDatabase.create(root, DATABASE, GENERATION, 2, opened));
    EmbeddedDatabase database = opened.database();
    assertEquals(StatusCode.OK, database.close());
    assertEquals(
        StatusCode.CONFLICT,
        EmbeddedDatabase.create(root, DATABASE, GENERATION, 2, opened));
    assertEquals(
        StatusCode.FENCED,
        EmbeddedDatabase.openExisting(
            root,
            DatabaseIncarnation.of(719, 727),
            GENERATION,
            2,
            opened));
    assertEquals(
        StatusCode.OK,
        EmbeddedDatabase.openExisting(root, DATABASE, GENERATION, 2, opened));
    assertEquals(StatusCode.OK, opened.database().close());
  }

  private static ByteBuffer row(long value) {
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    row.putLong(0, value);
    row.position(0);
    row.limit(Long.BYTES);
    return row;
  }

  private static long value(HeapRowResult result) {
    ByteBuffer row = ByteBuffer.allocate(Long.BYTES);
    assertEquals(StatusCode.OK, result.copyTo(row));
    return row.getLong(0);
  }
}
