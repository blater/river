package io.riverdb.engine.relational;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.checkpoint.CheckpointResult;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CatalogV2SuccessorTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x535543434553534fL, 0x52434154414c4f47L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void foreignPublishedPinCannotPrepareSuccessorDespiteCollidingIds(
      @TempDir Path root) throws java.io.IOException {
    RelationalDatabaseOpenResult firstOpen = new RelationalDatabaseOpenResult();
    RelationalDatabaseOpenResult secondOpen = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK, RelationalDatabase.create(
        databaseRequest(8),
        Files.createDirectory(root.resolve("first")),
        DATABASE, GENERATION, 8, firstOpen));
    assertEquals(StatusCode.OK, RelationalDatabase.create(
        databaseRequest(8),
        Files.createDirectory(root.resolve("second")),
        DatabaseIncarnation.of(0x535543434553534fL, 0x52434154414c4f48L),
        GENERATION, 8, secondOpen));
    RelationalDatabase first = firstOpen.database();
    RelationalDatabase second = secondOpen.database();
    createInitial(first);
    createInitial(second);
    SchemaPin foreign = open(first, "items");
    RelationalSession session = session(second);
    TransactionOutcome outcome = new TransactionOutcome();
    StatusDetail detail = new StatusDetail(128);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(0, session.indexedSession().pendingMutationCount());
    detail.set(StatusCode.CORRUPTION).append("stale");
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        session.prepareDescriptorSuccessor(
            "items", foreign, withIndex(foreign.descriptor()), detail));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, detail.code());
    assertEquals(0, detail.length());
    assertEquals(0, session.indexedSession().pendingMutationCount());
    assertEquals(StatusCode.OK, session.abort(outcome));
    SchemaPin unchanged = open(second, "items");
    assertEquals(1, unchanged.catalogGeneration());
    assertEquals(0, unchanged.descriptor().secondaryKeyCount());
    assertEquals(StatusCode.OK, unchanged.release());
    assertEquals(StatusCode.OK, foreign.release());
    assertEquals(StatusCode.OK, first.close());
    assertEquals(StatusCode.OK, second.close());
  }

  @Test
  void abortPreservesPredecessorAndCreateSuccessorSurvivesReopen(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    createInitial(database);

    SchemaPin predecessor = open(database, "items");
    long primaryKeyId = predecessor.descriptor().primaryKey().keyId();
    assertEquals(1, predecessor.catalogGeneration());
    assertEquals(0, predecessor.descriptor().secondaryKeyCount());
    replace(database, predecessor, withIndex(predecessor.descriptor()), false);
    assertEquals(StatusCode.OK, predecessor.release());
    SchemaPin afterAbort = open(database, "items");
    assertEquals(1, afterAbort.catalogGeneration());
    assertEquals(0, afterAbort.descriptor().secondaryKeyCount());

    SchemaPin indexed = replace(database, afterAbort, withIndex(afterAbort.descriptor()), true);
    assertEquals(StatusCode.OK, afterAbort.release());
    assertEquals(2, indexed.catalogGeneration());
    assertEquals(primaryKeyId, indexed.descriptor().primaryKey().keyId());
    assertEquals(1, indexed.descriptor().secondaryKeyCount());
    assertTrue(indexed.descriptor().secondaryKeyAt(0).matchesName("by_pair"));
    long indexKeyId = indexed.descriptor().secondaryKeyAt(0).keyId();
    assertTrue(indexKeyId > primaryKeyId);

    RelationalSession removal = session(database);
    TransactionOutcome removalOutcome = new TransactionOutcome();
    StatusDetail removalDetail = new StatusDetail(128);
    assertEquals(StatusCode.OK, removal.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        removal.prepareDescriptorSuccessor(
            "items", indexed, withoutIndexes(indexed.descriptor()), removalDetail));
    assertEquals(StatusCode.OK, removal.abort(removalOutcome));
    assertEquals(StatusCode.OK, indexed.release());

    assertEquals(StatusCode.OK, database.checkpoint(new CheckpointResult()));
    assertEquals(StatusCode.OK, database.close());
    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    SchemaPin reopened = open(database, "items");
    assertEquals(2, reopened.catalogGeneration());
    assertEquals(primaryKeyId, reopened.descriptor().primaryKey().keyId());
    assertEquals(1, reopened.descriptor().secondaryKeyCount());
    assertEquals(indexKeyId, reopened.descriptor().secondaryKeyAt(0).keyId());
    assertEquals(StatusCode.OK, reopened.release());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void thirtyTwoPartHighOrdinalIndexBindsAndSurvivesReopen(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    createInitial(database, "wide_items", wideInitial());
    SchemaPin current = open(database, "wide_items");
    int[] ordinals = new int[32];
    for (int index = 0; index < ordinals.length; index++) ordinals[index] = index + 8;
    TableDescriptor.Result proposal = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, new RelationalDescriptorIndexChange().add(
        current.descriptor(), "by_wide", true, ordinals, 0, ordinals.length,
        proposal, new StatusDetail(128)));
    SchemaPin indexed = replace(
        database, "wide_items", current, proposal.value(), true);
    long objectId = indexed.tableId();
    KeyDescriptor key = indexed.descriptor().secondaryKeyAt(0);
    assertTrue(key.keyId() > indexed.descriptor().primaryKey().keyId());
    assertEquals(32, key.partCount());
    assertEquals(39, key.columnOrdinalAt(31));
    assertEquals(StatusCode.OK, current.release());
    assertEquals(StatusCode.OK, indexed.release());
    assertEquals(StatusCode.OK, database.checkpoint(new CheckpointResult()));
    assertEquals(StatusCode.OK, database.close());

    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    SchemaPin reopened = open(database, "wide_items");
    assertEquals(objectId, reopened.tableId());
    key = reopened.descriptor().secondaryKeyAt(0);
    assertEquals(32, key.partCount());
    assertEquals(39, key.columnOrdinalAt(31));
    assertTrue(key.matchesName("by_wide"));
    assertEquals(StatusCode.OK, reopened.release());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void createInitial(RelationalDatabase database) {
    createInitial(database, "items", initial());
  }

  private static void createInitial(
      RelationalDatabase database, CharSequence name, TableDescriptor descriptor) {
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    StatusDetail detail = new StatusDetail(128);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        session.prepareDescriptorTable(name, descriptor, detail), detail.toString());
    assertEquals(StatusCode.OK, session.commit(outcome));
  }

  private static SchemaPin replace(
      RelationalDatabase database, SchemaPin current, TableDescriptor proposed,
      boolean commit) {
    return replace(database, "items", current, proposed, commit);
  }

  private static SchemaPin replace(
      RelationalDatabase database, CharSequence name, SchemaPin current,
      TableDescriptor proposed, boolean commit) {
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    StatusDetail detail = new StatusDetail(128);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        session.prepareDescriptorSuccessor(name, current, proposed, detail),
        detail.toString());
    if (!commit) {
      assertEquals(StatusCode.OK, session.abort(outcome));
      return null;
    }
    SchemaPin overlay = new SchemaPin();
    assertEquals(StatusCode.OK, session.resolveDescriptor(name, overlay, detail));
    assertTrue(!overlay.isPublished());
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertTrue(overlay.isPublished());
    return overlay;
  }

  private static SchemaPin open(RelationalDatabase database, String name) {
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    StatusDetail detail = new StatusDetail(128);
    SchemaPin pin = new SchemaPin();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.resolveDescriptor(name, pin, detail), detail.toString());
    assertEquals(StatusCode.OK, session.abort(outcome));
    return pin;
  }

  private static RelationalSession session(RelationalDatabase database) {
    RelationalSessionOpenResult opened = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(opened));
    return opened.session();
  }

  private static TableDescriptor initial() {
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.BIGINT},
        new CharSequence[] {"a", "b"}, new boolean[2], columns));
    KeyDescriptor.Result primary = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.createForTest(
        KeyDescriptor.KIND_PRIMARY, true, columns.value(), new int[] {0},
        primary, null));
    TableDescriptor.Result table = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.createForTest(
        columns.value(), primary.value(), null, null, table));
    return table.value();
  }

  private static TableDescriptor wideInitial() {
    int[] types = new int[40];
    CharSequence[] names = new CharSequence[40];
    boolean[] nullable = new boolean[40];
    for (int index = 0; index < types.length; index++) {
      types[index] = SqlTypeDescriptor.BIGINT;
      names[index] = "c" + index;
    }
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK,
        ColumnDescriptorSet.create(types, names, nullable, columns));
    KeyDescriptor.Result primary = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.createForTest(
        KeyDescriptor.KIND_PRIMARY, true, columns.value(), new int[] {0},
        primary, null));
    TableDescriptor.Result table = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.createForTest(
        columns.value(), primary.value(), null, null, table));
    return table.value();
  }

  private static TableDescriptor withIndex(TableDescriptor current) {
    KeyDescriptor.Result secondary = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.createNamedForTest(
        KeyDescriptor.KIND_SECONDARY, false, current.columns(), new int[] {0, 1},
        0, "by_pair", secondary, null));
    return proposed(current, new KeyDescriptor[] {secondary.value()});
  }

  private static TableDescriptor withoutIndexes(TableDescriptor current) {
    return proposed(current, null);
  }

  private static TableDescriptor proposed(
      TableDescriptor current, KeyDescriptor[] secondary) {
    TableDescriptor.Result table = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.createProposedSuccessor(
        current.tableId(), current.rowLayoutId(), current.catalogGeneration(),
        current.columns(), current.primaryKey(), secondary, null, table, null));
    return table.value();
  }
}
