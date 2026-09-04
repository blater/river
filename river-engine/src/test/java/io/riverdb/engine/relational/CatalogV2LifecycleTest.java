package io.riverdb.engine.relational;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
import io.riverdb.format.catalog.CatalogDefinitionManifest;
import io.riverdb.format.catalog.CatalogDefinitionManifestCodec;
import io.riverdb.format.catalog.CatalogDefinitionRecordCodec;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.format.catalog.CatalogObjectHead;
import io.riverdb.format.catalog.CatalogObjectHeadCodec;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CatalogV2LifecycleTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x434154414c4f4756L, 0x3254455354303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void wideDescriptorSurvivesWalOnlyAndCheckpointReopen(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin created = new SchemaPin();
    StatusDetail detail = new StatusDetail(128);
    assertEquals(StatusCode.OK,
        database.services().descriptors().create(
            wideDescriptor(), created, detail), detail.toString());
    long objectId = created.tableId();
    long schemaId = created.schemaId();
    assertEquals(schemaId, created.descriptor().schemaId());
    assertEquals(true, schemaId > 0);
    assertWide(created.descriptor());
    assertEquals(StatusCode.OK, created.release());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    SchemaPin walPin = new SchemaPin();
    assertEquals(StatusCode.OK,
        database.services().descriptors().open(objectId, walPin, detail), detail.toString());
    assertEquals(schemaId, walPin.schemaId());
    assertEquals(schemaId, walPin.descriptor().schemaId());
    assertWide(walPin.descriptor());
    assertEquals(StatusCode.OK, walPin.release());
    assertEquals(StatusCode.OK, database.checkpoint(new CheckpointResult()));
    assertEquals(StatusCode.OK, database.close());

    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    SchemaPin checkpointPin = new SchemaPin();
    assertEquals(StatusCode.OK,
        database.services().descriptors().open(
            objectId, checkpointPin, detail), detail.toString());
    assertEquals(schemaId, checkpointPin.schemaId());
    assertEquals(schemaId, checkpointPin.descriptor().schemaId());
    assertWide(checkpointPin.descriptor());
    assertEquals(StatusCode.OK, checkpointPin.release());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void committedPrivateDefinitionAndAbortedHeadRemainInvisible(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(4), root, DATABASE, GENERATION, 4, opened));
    RelationalDatabase database = opened.database();
    RelationalSessionOpenResult sessions = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessions));
    RelationalSession session = sessions.session();
    TransactionOutcome outcome = new TransactionOutcome();
    ByteBuffer privateRecord = ByteBuffer.allocateDirect(1);
    privateRecord.put(0, (byte) 1).position(0).limit(1);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.indexedSession().insert(
        CatalogKeyspace.DEFINITION_SPACE, 9_999, privateRecord));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.CONFLICT,
        database.services().descriptors().open(
            777, new SchemaPin(), new StatusDetail(32)));

    ByteBuffer head = ByteBuffer.allocateDirect(CatalogObjectHeadCodec.BYTES);
    assertEquals(StatusCode.OK, CatalogObjectHeadCodec.encode(head, 0,
        CatalogObjectHeadCodec.STATE_READY, 777, 777, 1, 9_999, new CRC32C()));
    head.position(0).limit(CatalogObjectHeadCodec.BYTES);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.indexedSession().insert(
        CatalogKeyspace.OBJECT_HEAD_SPACE, 777, head));
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(StatusCode.CONFLICT,
        database.services().descriptors().open(
            777, new SchemaPin(), new StatusDetail(32)));
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void corruptCommittedChildIsRejectedAfterReopen(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(6), root, DATABASE, GENERATION, 6, opened));
    RelationalDatabase database = opened.database();
    SchemaPin created = new SchemaPin();
    assertEquals(StatusCode.OK,
        database.services().descriptors().create(
            wideDescriptor(), created, new StatusDetail(64)));
    long objectId = created.tableId();
    assertEquals(StatusCode.OK, created.release());
    corruptFirstChild(database, objectId);
    assertEquals(StatusCode.OK, database.close());

    assertEquals(StatusCode.CORRUPTION,
        RelationalDatabase.openExisting(databaseRequest(6), root, DATABASE, GENERATION, 6, opened));
  }

  private static void corruptFirstChild(RelationalDatabase database, long objectId) {
    RelationalSessionOpenResult sessions = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessions));
    RelationalSession session = sessions.session();
    TransactionOutcome outcome = new TransactionOutcome();
    HeapRowResult row = new HeapRowResult();
    ByteBuffer bytes = ByteBuffer.allocateDirect(CatalogDefinitionRecordCodec.MAX_RECORD_BYTES);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.indexedSession().fetchByKey(
        CatalogKeyspace.OBJECT_HEAD_SPACE, objectId, row));
    assertEquals(StatusCode.OK, row.copyTo(bytes));
    bytes.flip();
    CatalogObjectHead head = new CatalogObjectHead();
    assertEquals(StatusCode.OK,
        CatalogObjectHeadCodec.decode(bytes, 0, head, new CRC32C()));
    bytes.clear();
    assertEquals(StatusCode.OK, session.indexedSession().fetchByKey(
        CatalogKeyspace.DEFINITION_SPACE, head.manifestRecordId(), row));
    assertEquals(StatusCode.OK, row.copyTo(bytes));
    bytes.flip();
    CatalogDefinitionManifest manifest = new CatalogDefinitionManifest();
    assertEquals(StatusCode.OK,
        CatalogDefinitionManifestCodec.decode(bytes, 0, manifest, new CRC32C()));
    bytes.clear();
    assertEquals(StatusCode.OK, session.indexedSession().fetchByKey(
        CatalogKeyspace.DEFINITION_SPACE, manifest.firstChildRecordId(), row));
    assertEquals(StatusCode.OK, row.copyTo(bytes));
    bytes.flip();
    bytes.put(CatalogDefinitionRecordCodec.HEADER_BYTES + 8,
        (byte) (bytes.get(CatalogDefinitionRecordCodec.HEADER_BYTES + 8) ^ 1));
    assertEquals(StatusCode.OK, session.indexedSession().update(
        CatalogKeyspace.DEFINITION_SPACE, manifest.firstChildRecordId(), bytes));
    assertEquals(StatusCode.OK, session.commit(outcome));
  }

  private static TableDescriptor wideDescriptor() {
    int count = 1_024;
    int[] types = new int[count];
    CharSequence[] names = new CharSequence[count];
    boolean[] nullable = new boolean[count];
    for (int index = 0; index < count; index++) {
      types[index] = index == 0 ? SqlTypeDescriptor.BIGINT : SqlTypeDescriptor.BOOLEAN;
      names[index] = "c" + index;
      nullable[index] = index != 0 && index % 3 == 0;
    }
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(types, names, nullable, columns));
    KeyDescriptor.Result primary = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.create(
        1, KeyDescriptor.KIND_PRIMARY, true, columns.value(), new int[] {0},
        0, primary, null));
    TableDescriptor.Result table = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.create(
        99, 101, 103, columns.value(), primary.value(), null, null, table, null));
    return table.value();
  }

  private static void assertWide(TableDescriptor table) {
    assertEquals(1_024, table.columnCount());
    assertEquals(SqlTypeDescriptor.BIGINT, table.typeDescriptorAt(0));
    for (int ordinal : new int[] {0, 7, 8, 63, 64, 255, 1_023}) {
      assertEquals("c" + ordinal, columnName(table.columns(), ordinal));
      assertEquals(ordinal != 0 && ordinal % 3 == 0, table.isNullable(ordinal));
    }
    assertEquals(1, table.primaryKey().partCount());
    assertEquals(0, table.primaryKey().columnOrdinalAt(0));
  }

  private static String columnName(ColumnDescriptorSet columns, int ordinal) {
    char[] chars = new char[16];
    int length = columns.copyNameChars(ordinal, chars, 0);
    return new String(chars, 0, length);
  }
}
