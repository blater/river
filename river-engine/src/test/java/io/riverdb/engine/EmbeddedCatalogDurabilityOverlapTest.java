package io.riverdb.engine;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.IsolationLevel;
import io.riverdb.engine.api.PreparedOpenResult;
import io.riverdb.engine.api.ProgramOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.SessionOpenResult;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramAction;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.TransactionProgramResult;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.RelationalSessionOpenResult;
import io.riverdb.engine.schema.cache.SchemaCache;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.schema.catalog.CatalogTableLifecycle;
import io.riverdb.engine.table.IndexedGroupCommitTelemetry;
import io.riverdb.engine.table.IndexedTable;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.tx.api.TransactionOutcome;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class EmbeddedCatalogDurabilityOverlapTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void blockedProgramResolvesForeignKeyAndEnqueuesBeforePredecessorForce(
      boolean failForce, @TempDir Path root) throws Exception {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(databaseRequest(8), root,
        DatabaseIncarnation.of(1_211, 1_213), WalGeneration.of(1), 8, opened));
    RiverDatabase database = opened.database();
    RiverSession predecessor = session(database);
    RiverSession successor = session(database);
    execute(predecessor, "CREATE TABLE account (id INTEGER PRIMARY KEY,balance BIGINT NOT NULL)");
    execute(predecessor, "CREATE TABLE child (id INTEGER PRIMARY KEY,account_id INTEGER "
        + "REFERENCES account(id))");
    execute(predecessor, "INSERT INTO account VALUES (1,100)");
    long program = program(successor,
        "UPDATE account SET balance=balance+1 WHERE id=1",
        "INSERT INTO child VALUES (1,1)");
    RelationalDatabase relational = (RelationalDatabase) field(database, "database");
    EmbeddedDatabase embedded = (EmbeddedDatabase) field(relational, "embedded");
    RelationalSessionOpenResult readerOpened = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, relational.createSession(readerOpened));
    RelationalSession reader = readerOpened.session();
    IndexedTransactionSession indexedReader = (IndexedTransactionSession) field(reader, "session");
    assertEquals(StatusCode.OK, reader.begin(io.riverdb.tx.api.IsolationLevel.SERIALIZABLE));
    SchemaPin identity = new SchemaPin();
    assertEquals(StatusCode.OK, reader.resolveDescriptor("account", identity, null));
    long objectId = identity.tableId();
    assertEquals(StatusCode.OK, identity.release());
    assertEquals(StatusCode.OK, reader.abort(new TransactionOutcome()));
    SchemaCache.Result cacheResult = new SchemaCache.Result();
    assertEquals(StatusCode.OK, SchemaCache.create(2, 1_048_576, cacheResult, null));
    SchemaCache cache = cacheResult.value();
    CatalogTableLifecycle catalog = new CatalogTableLifecycle(embedded, cache);
    IndexedTable table = (IndexedTable) field(embedded, "table");
    LocalWal wal = (LocalWal) field(embedded, "wal");
    HeldForce file = new HeldForce((DurableFile) field(wal, "file"), failForce);
    Field walFile = LocalWal.class.getDeclaredField("file");
    walFile.setAccessible(true);
    walFile.set(wal, file);
    execute(predecessor, "BEGIN");
    execute(predecessor, "UPDATE account SET balance=balance+1 WHERE id=1");
    long enqueues = enqueues(table);
    TransactionProgramResult result = new TransactionProgramResult();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        var next = executor.submit(() -> successor.executeProgram(program,
            IsolationLevel.SERIALIZABLE, new TransactionProgramArguments(), result));
        await(() -> database.waitingLockCount() > 0, "successor must first block on the row");
        var first = executor.submit(() -> predecessor.execute("COMMIT", new CommandResult()));
        assertTrue(file.entered.await(5, TimeUnit.SECONDS));
        await(() -> enqueues(table) == enqueues + 2,
            "successor must finish descriptor/FK reads and enqueue while force is held");
        assertFalse(first.isDone());
        assertFalse(next.isDone());
        assertEquals(0, result.commitSequence());
        assertEquals(StatusCode.OK, executor.submit(() -> {
          assertEquals(StatusCode.OK, reader.begin(io.riverdb.tx.api.IsolationLevel.SERIALIZABLE));
          assertEquals(StatusCode.OK, reader.beginStatement());
          assertEquals(0, cache.size());
          SchemaPin cold = new SchemaPin();
          assertEquals(StatusCode.OK,
              catalog.openInTransaction(indexedReader, objectId, cold, null));
          var descriptor = cold.descriptor();
          assertEquals(StatusCode.OK, cold.release());
          assertEquals(1, cache.size());
          assertEquals(StatusCode.OK,
              catalog.openInTransaction(indexedReader, objectId, cold, null));
          assertSame(descriptor, cold.descriptor());
          assertEquals(StatusCode.OK, cold.release());
          assertEquals(StatusCode.CONFLICT,
              catalog.openInTransaction(indexedReader, 999_999, cold, null));
          assertFalse(cold.isActive());
          assertEquals(0, cache.reservedSlots());
          assertTrue(reader.isTransactionActive());
          return reader.completeStatement(false);
        }).get(5, TimeUnit.SECONDS));
        SchemaPin standalonePin = new SchemaPin();
        CountDownLatch standaloneStarted = new CountDownLatch(1);
        var standalone = executor.submit(() -> {
          standaloneStarted.countDown();
          return catalog.open(objectId, standalonePin, null);
        });
        assertTrue(standaloneStarted.await(5, TimeUnit.SECONDS));
        assertEquals(StatusCode.OK, standalone.get(5, TimeUnit.SECONDS));
        assertTrue(standalonePin.isActive());
        assertEquals(StatusCode.OK, standalonePin.release());
        file.release.countDown();
        assertEquals(failForce ? StatusCode.IO_FAILURE : StatusCode.OK,
            first.get(5, TimeUnit.SECONDS));
        assertEquals(failForce ? StatusCode.FENCED : StatusCode.OK,
            next.get(5, TimeUnit.SECONDS));

        assertEquals(StatusCode.OK, reader.abort(new TransactionOutcome()));
        if (!failForce) {
          assertTrue(result.commitSequence() > 0);
          CommandResult rows = new CommandResult();
          assertEquals(StatusCode.OK, successor.execute("SELECT balance FROM account", rows));
          assertEquals(102, rows.valueAt(0));
          assertEquals(StatusCode.OK, successor.execute("SELECT COUNT(*) FROM child", rows));
          assertEquals(1, rows.valueAt(0));
        }
      } finally {
        file.release.countDown();
      }
    } finally {
      reader.close();
      catalog.close();
      predecessor.close();
      successor.close();
      assertEquals(0, database.activeTransactionCount());
      assertEquals(0, database.activeLockCount());
      assertEquals(0, database.waitingLockCount());
      database.close();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void sqlReadsWaitOnlyForObservedRowsAndTupleRoots(boolean failForce, @TempDir Path root)
      throws Exception {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(databaseRequest(8), root,
        DatabaseIncarnation.of(1_217, 1_223), WalGeneration.of(1), 8, opened));
    RiverDatabase database = opened.database();
    RiverSession writer = session(database);
    RiverSession independent = session(database);
    RiverSession rowReader = session(database);
    RiverSession missingReader = session(database);
    RiverSession rangeReader = session(database);
    execute(writer, "CREATE TABLE changing (id INTEGER PRIMARY KEY,value BIGINT NOT NULL)");
    execute(writer, "CREATE TABLE stable (id INTEGER PRIMARY KEY,value BIGINT NOT NULL)");
    execute(writer, "INSERT INTO changing VALUES (1,100)");
    execute(writer, "INSERT INTO changing VALUES (2,200)");
    execute(writer, "INSERT INTO stable VALUES (1,300)");
    RelationalDatabase relational = (RelationalDatabase) field(database, "database");
    EmbeddedDatabase embedded = (EmbeddedDatabase) field(relational, "embedded");
    LocalWal wal = (LocalWal) field(embedded, "wal");
    HeldForce file = new HeldForce((DurableFile) field(wal, "file"), failForce);
    Field walFile = LocalWal.class.getDeclaredField("file");
    walFile.setAccessible(true);
    walFile.set(wal, file);
    execute(writer, "BEGIN");
    execute(writer, "UPDATE changing SET value=101 WHERE id=1");
    execute(writer, "DELETE FROM changing WHERE id=2");
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        var commit = executor.submit(() -> writer.execute("COMMIT", new CommandResult()));
        assertTrue(file.entered.await(5, TimeUnit.SECONDS));
        assertEquals(StatusCode.OK, executor.submit(() -> {
          CommandResult result = new CommandResult();
          assertEquals(StatusCode.OK, independent.execute("SELECT value FROM stable WHERE id=1", result));
          assertEquals(300, result.valueAt(0));
          execute(independent, "SELECT COUNT(*) FROM stable WHERE id=999");
          execute(independent, "BEGIN");
          execute(independent, "SELECT value FROM stable WHERE id=1");
          execute(independent, "SELECT COUNT(*) FROM stable WHERE id>9");
          return independent.execute("COMMIT", new CommandResult());
        }).get(5, TimeUnit.SECONDS));
        CommandResult rowResult = new CommandResult();
        CommandResult missingResult = new CommandResult();
        CommandResult rangeResult = new CommandResult();
        var row = executor.submit(() -> rowReader.execute(
            "SELECT value FROM changing WHERE id=1", rowResult));
        var missing = executor.submit(() -> missingReader.execute(
            "SELECT COUNT(*) FROM changing WHERE id=2", missingResult));
        var range = executor.submit(() -> rangeReader.execute(
            "SELECT COUNT(*) FROM changing WHERE id>1", rangeResult));
        assertThrows(TimeoutException.class, () -> row.get(100, TimeUnit.MILLISECONDS));
        assertThrows(TimeoutException.class, () -> missing.get(100, TimeUnit.MILLISECONDS));
        assertThrows(TimeoutException.class, () -> range.get(100, TimeUnit.MILLISECONDS));
        assertFalse(commit.isDone());
        file.release.countDown();
        assertEquals(failForce ? StatusCode.IO_FAILURE : StatusCode.OK,
            commit.get(5, TimeUnit.SECONDS));
        StatusCode expected = failForce ? StatusCode.FENCED : StatusCode.OK;
        assertEquals(expected, row.get(5, TimeUnit.SECONDS));
        assertEquals(expected, missing.get(5, TimeUnit.SECONDS));
        assertEquals(expected, range.get(5, TimeUnit.SECONDS));
        if (!failForce) {
          assertEquals(101, rowResult.valueAt(0));
          assertEquals(0, missingResult.valueAt(0));
          assertEquals(0, rangeResult.valueAt(0));
        }
      } finally {
        file.release.countDown();
      }
    } finally {
      assertEquals(StatusCode.OK, writer.close());
      assertEquals(StatusCode.OK, independent.close());
      assertEquals(StatusCode.OK, rowReader.close());
      assertEquals(StatusCode.OK, missingReader.close());
      assertEquals(StatusCode.OK, rangeReader.close());
      assertEquals(0, database.activeTransactionCount());
      assertEquals(0, database.activeLockCount());
      assertEquals(failForce ? StatusCode.FENCED : StatusCode.OK, database.close());
    }
  }

  private static RiverSession session(RiverDatabase database) {
    SessionOpenResult result = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(result));
    return result.session();
  }

  private static void execute(RiverSession session, String sql) {
    assertEquals(StatusCode.OK, session.execute(sql, new CommandResult()), sql);
  }

  private static long program(RiverSession session, String... commands) {
    TransactionProgram program = new TransactionProgram();
    for (String sql : commands) {
      PreparedOpenResult prepared = new PreparedOpenResult();
      assertEquals(StatusCode.OK, session.prepare(sql, prepared));
      assertEquals(StatusCode.OK,
          program.beginStep(prepared.handle(), TransactionProgramAction.COMMAND));
      assertEquals(StatusCode.OK, program.endStep());
    }
    assertEquals(StatusCode.OK, program.freeze());
    ProgramOpenResult result = new ProgramOpenResult();
    assertEquals(StatusCode.OK, session.prepareProgram(program, result));
    return result.handle();
  }

  private static Object field(Object owner, String name) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static long enqueues(IndexedTable table) {
    IndexedGroupCommitTelemetry telemetry = new IndexedGroupCommitTelemetry();
    assertEquals(StatusCode.OK, table.copyCommitTelemetry(telemetry));
    return telemetry.queue().enqueues();
  }

  private static void await(BooleanSupplier condition, String message) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1);
    assertTrue(condition.getAsBoolean(), message);
  }

  /** Test-only adapter holds the real WAL file force after production publication/lock handoff. */
  private static final class HeldForce implements DurableFile {
    private final DurableFile delegate;
    private final boolean fail;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    private HeldForce(DurableFile file, boolean failForce) {
      delegate = file;
      fail = failForce;
    }

    @Override public StatusCode force(ForceMode mode) {
      entered.countDown();
      try {
        if (!release.await(15, TimeUnit.SECONDS)) return StatusCode.IO_FAILURE;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return StatusCode.CANCELLED;
      }
      return fail ? StatusCode.IO_FAILURE : delegate.force(mode);
    }

    @Override public StatusCode read(long offset, ByteBuffer target, IoResult result) {
      return delegate.read(offset, target, result);
    }
    @Override public StatusCode write(long offset, ByteBuffer source, IoResult result) {
      return delegate.write(offset, source, result);
    }
    @Override public StatusCode truncate(long bytes) { return delegate.truncate(bytes); }
    @Override public StatusCode size(FileSizeResult result) { return delegate.size(result); }
    @Override public StatusCode close() { return delegate.close(); }
  }
}
