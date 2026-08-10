package io.riverdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.checkpoint.CheckpointControlStore;
import io.riverdb.engine.checkpoint.CheckpointResult;
import io.riverdb.format.wal.WalFileHeaderCodec;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalOpenResult;
import java.nio.ByteBuffer;
import java.nio.file.Files;
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

  @Test
  void checkpointsStablePagesRotatesWalAndReplaysNewSuffix(@TempDir Path root) throws Exception {
    EmbeddedDatabaseOpenResult opened = new EmbeddedDatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedDatabase.create(root, DATABASE, GENERATION, 4, opened));
    EmbeddedDatabase database = opened.database();
    EmbeddedSessionOpenResult sessionResult = new EmbeddedSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(128, sessionResult));
    IndexedTransactionSession session = sessionResult.session();
    TransactionOutcome outcome = new TransactionOutcome();

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.insert(41, row(410)));
    assertEquals(StatusCode.OK, session.insert(43, row(430)));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.update(41, row(411)));
    assertEquals(StatusCode.OK, session.delete(43));
    assertEquals(StatusCode.OK, session.commit(outcome));

    CheckpointResult checkpoint = new CheckpointResult();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.RETRY, database.checkpoint(checkpoint));
    assertEquals(StatusCode.OK, session.abort(outcome));
    long previousWalBytes = Files.size(root.resolve(LocalWal.FILE_NAME));
    assertEquals(StatusCode.OK, database.checkpoint(checkpoint));
    assertEquals(1, checkpoint.checkpointId());
    assertEquals(1, checkpoint.previousWalGeneration());
    assertEquals(2, checkpoint.walGeneration());
    assertEquals(true, checkpoint.previousWalBytes() > previousWalBytes);
    assertEquals(WalFileHeaderCodec.HEADER_BYTES, checkpoint.walBytes());
    assertEquals(2, checkpoint.rowsReclaimed());
    assertEquals(false, checkpoint.obsoleteFilesRetained());
    assertEquals(false, Files.exists(root.resolve(LocalWal.FILE_NAME)));
    assertEquals(true, Files.exists(root.resolve("river.wal.2")));
    assertEquals(true, Files.exists(root.resolve(CheckpointControlStore.FILE_NAME)));
    assertEquals(StatusCode.OK, database.close());
    assertEquals(
        StatusCode.CONFLICT,
        EmbeddedDatabase.create(root, DATABASE, GENERATION, 4, opened));

    assertEquals(
        StatusCode.OK,
        EmbeddedDatabase.openExisting(root, DATABASE, GENERATION, 4, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, database.createSession(128, sessionResult));
    session = sessionResult.session();
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.fetchByKey(41, fetched));
    assertEquals(411, value(fetched));
    assertEquals(StatusCode.CONFLICT, session.fetchByKey(43, fetched));
    assertEquals(StatusCode.OK, session.update(41, row(412)));
    assertEquals(StatusCode.OK, session.insert(47, row(470)));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, database.close());

    assertEquals(
        StatusCode.OK,
        EmbeddedDatabase.openExisting(root, DATABASE, GENERATION, 4, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, database.createSession(128, sessionResult));
    session = sessionResult.session();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.fetchByKey(41, fetched));
    assertEquals(412, value(fetched));
    assertEquals(StatusCode.OK, session.fetchByKey(47, fetched));
    assertEquals(470, value(fetched));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, database.checkpoint(checkpoint));
    assertEquals(2, checkpoint.checkpointId());
    assertEquals(3, checkpoint.walGeneration());
    assertEquals(false, Files.exists(root.resolve("river.wal.2")));
    assertEquals(
        false,
        Files.exists(root.resolve("river.indexed.pages.checkpoint.2")));
    assertEquals(
        true,
        Files.exists(root.resolve("river.indexed.pages.checkpoint.3")));
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void rejectsTruncatedCheckpointAuthority(@TempDir Path root) throws Exception {
    EmbeddedDatabaseOpenResult opened = new EmbeddedDatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedDatabase.create(root, DATABASE, GENERATION, 2, opened));
    EmbeddedDatabase database = opened.database();
    CheckpointResult checkpoint = new CheckpointResult();
    assertEquals(StatusCode.OK, database.checkpoint(checkpoint));
    assertEquals(0, checkpoint.rowsReclaimed());
    assertEquals(StatusCode.OK, database.close());

    Files.write(root.resolve(CheckpointControlStore.FILE_NAME), new byte[] {1});
    assertEquals(
        StatusCode.CORRUPTION,
        EmbeddedDatabase.openExisting(root, DATABASE, GENERATION, 2, opened));
  }

  @Test
  void rejectsMissingCheckpointBase(@TempDir Path root) throws Exception {
    EmbeddedDatabaseOpenResult opened = new EmbeddedDatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedDatabase.create(root, DATABASE, GENERATION, 2, opened));
    EmbeddedDatabase database = opened.database();
    assertEquals(StatusCode.OK, database.checkpoint(new CheckpointResult()));
    assertEquals(StatusCode.OK, database.close());

    Files.delete(root.resolve("river.indexed.pages.checkpoint.2"));
    assertEquals(
        StatusCode.CORRUPTION,
        EmbeddedDatabase.openExisting(root, DATABASE, GENERATION, 2, opened));
  }

  @Test
  void checkpointsVacuumedRowsAcrossHeapPages(@TempDir Path root) {
    EmbeddedDatabaseOpenResult opened = new EmbeddedDatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedDatabase.create(root, DATABASE, GENERATION, 6, opened));
    EmbeddedDatabase database = opened.database();
    EmbeddedSessionOpenResult sessionResult = new EmbeddedSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(256, sessionResult));
    IndexedTransactionSession session = sessionResult.session();
    TransactionOutcome outcome = new TransactionOutcome();
    ByteBuffer wideRow = ByteBuffer.allocateDirect(256);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    for (int key = 0; key < 64; key++) {
      prepareWideRow(wideRow, key * 10L);
      assertEquals(StatusCode.OK, session.insert(key, wideRow));
    }
    assertEquals(StatusCode.OK, session.commit(outcome));

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    for (int key = 0; key < 32; key++) {
      prepareWideRow(wideRow, key * 10L + 1);
      assertEquals(StatusCode.OK, session.update(key, wideRow));
    }
    assertEquals(StatusCode.OK, session.commit(outcome));
    CheckpointResult checkpoint = new CheckpointResult();
    assertEquals(StatusCode.OK, database.checkpoint(checkpoint));
    assertEquals(64, checkpoint.rowCount());
    assertEquals(32, checkpoint.rowsReclaimed());
    assertEquals(true, checkpoint.pageCount() >= 4);
    assertEquals(StatusCode.OK, database.close());

    assertEquals(
        StatusCode.OK,
        EmbeddedDatabase.openExisting(root, DATABASE, GENERATION, 6, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, database.createSession(256, sessionResult));
    session = sessionResult.session();
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.fetchByKey(0, fetched));
    assertEquals(1, value(fetched));
    assertEquals(StatusCode.OK, session.fetchByKey(31, fetched));
    assertEquals(311, value(fetched));
    assertEquals(StatusCode.OK, session.fetchByKey(63, fetched));
    assertEquals(630, value(fetched));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void ignoresUnreferencedNextGenerationAndCompletesRetry(@TempDir Path root) throws Exception {
    EmbeddedDatabaseOpenResult opened = new EmbeddedDatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedDatabase.create(root, DATABASE, GENERATION, 2, opened));
    EmbeddedDatabase database = opened.database();
    EmbeddedSessionOpenResult sessionResult = new EmbeddedSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(128, sessionResult));
    IndexedTransactionSession session = sessionResult.session();
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.insert(53, row(530)));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, database.close());

    NioDurableDirectory physical = openDirectory(root);
    LocalWalOpenResult staleWal = new LocalWalOpenResult();
    assertEquals(
        StatusCode.OK,
        LocalWal.createNamed(
            physical,
            "river.wal.2",
            DATABASE,
            WalGeneration.of(2),
            staleWal));
    assertEquals(StatusCode.OK, staleWal.wal().close());
    assertEquals(StatusCode.OK, physical.close());
    Files.write(root.resolve("river.indexed.pages.checkpoint.2"), new byte[] {1, 2, 3});

    assertEquals(
        StatusCode.OK,
        EmbeddedDatabase.openExisting(root, DATABASE, GENERATION, 2, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, database.createSession(128, sessionResult));
    session = sessionResult.session();
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.fetchByKey(53, fetched));
    assertEquals(530, value(fetched));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, database.checkpoint(new CheckpointResult()));
    assertEquals(StatusCode.OK, database.close());

    assertEquals(
        StatusCode.OK,
        EmbeddedDatabase.openExisting(root, DATABASE, GENERATION, 2, opened));
    assertEquals(StatusCode.OK, opened.database().close());
  }

  private static NioDurableDirectory openDirectory(Path root) {
    NioDirectoryOpenResult result = new NioDirectoryOpenResult();
    assertEquals(
        StatusCode.OK,
        NioDurableDirectory.openExisting(
            root,
            new FatalStateFence(),
            new NioIoCounters(),
            8,
            result));
    return result.directory();
  }

  private static ByteBuffer row(long value) {
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    row.putLong(0, value);
    row.position(0);
    row.limit(Long.BYTES);
    return row;
  }

  private static long value(HeapRowResult result) {
    ByteBuffer row = ByteBuffer.allocate(result.length());
    assertEquals(StatusCode.OK, result.copyTo(row));
    return row.getLong(0);
  }

  private static void prepareWideRow(ByteBuffer row, long value) {
    row.clear();
    row.putLong(0, value);
    row.position(0);
    row.limit(row.capacity());
  }
}
