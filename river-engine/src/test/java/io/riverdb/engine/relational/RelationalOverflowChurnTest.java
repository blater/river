package io.riverdb.engine.relational;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.EmbeddedLockDiagnosticsConfig;
import io.riverdb.engine.checkpoint.CheckpointResult;
import io.riverdb.engine.runtime.DatabasePageCacheTestPlan;
import io.riverdb.engine.runtime.DatabaseResourcePlan;
import io.riverdb.engine.runtime.DatabaseResourcePlanRequest;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class RelationalOverflowChurnTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(111, 222);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  @Test
  void repeatedMultiRowOverflowUpdatesKeepRetirementAndFileSizeBounded(@TempDir Path root)
      throws java.io.IOException {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        overflowTextDescriptor(), table, new StatusDetail(128)));
    RelationalSession writer = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    String large = "😀".repeat(3_400);
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.SERIALIZABLE));
    for (int row = 1; row <= 2; row++) assertEquals(StatusCode.OK,
        writer.descriptorRows().insert(table, overflowTextValues(row, large),
            new RelationalRowIdentityResult()));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    assertEquals(StatusCode.OK, database.checkpoint(new CheckpointResult()));
    int initialPageBytes = -1;
    for (int round = 0; round < 8; round++) {
      assertEquals(StatusCode.OK, database.checkpoint(new CheckpointResult()));
      assertEquals(StatusCode.OK, writer.begin(IsolationLevel.SERIALIZABLE));
      for (int row = 1; row <= 2; row++) assertEquals(StatusCode.OK,
          writer.descriptorRows().update(table, row, overflowTextValues(row, large)));
      assertEquals(StatusCode.OK, writer.commit(outcome));
      assertEquals(StatusCode.OK, database.checkpoint(new CheckpointResult()));
      ByteBuffer file = ByteBuffer.wrap(Files.readAllBytes(root.resolve("river.indexed.pages")));
      if (initialPageBytes < 0) initialPageBytes = file.limit();
      int live = 0;
      int retired = 0;
      for (int offset = 0; offset < file.limit();
          offset += io.riverdb.format.page.PageCodec.PAGE_BYTES) {
        if (io.riverdb.format.FormatBytes.getInt(file, offset + 84)
            != io.riverdb.format.page.PageCodec.PAYLOAD_KIND_TUPLE_OVERFLOW) continue;
        long removedAt = io.riverdb.format.FormatBytes.getLong(
            file, offset + io.riverdb.format.page.PageCodec.HEADER_BYTES + 24);
        if (removedAt == 0) live++; else retired++;
      }
      System.out.println("overflow update round=" + round + ", live=" + live
          + ", checkpointed retired=" + retired);
      assertEquals(2, live);
      assertEquals(2, retired);
      assertEquals(initialPageBytes, file.limit());
    }
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void oneCommitReusesMultipleOtherTablePagesAndWalOnlyReplayPreservesRows(
      boolean spill, @TempDir Path root)
      throws java.io.IOException {
    Path live = Files.createDirectory(root.resolve("live"));
    Path crash = Files.createDirectory(root.resolve("crash"));
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), live, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin first = new SchemaPin();
    SchemaPin second = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        overflowTextDescriptor(), first, new StatusDetail(128)));
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        overflowTextDescriptor(2), second, new StatusDetail(128)));
    long firstId = first.tableId();
    long secondId = second.tableId();
    RelationalSession writer = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    String large = "😀".repeat(3_400);
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.SERIALIZABLE));
    for (int row = 1; row <= 2; row++) assertEquals(StatusCode.OK,
        writer.descriptorRows().insert(first, overflowTextValues(row, large),
            new RelationalRowIdentityResult()));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.SERIALIZABLE));
    for (int row = 1; row <= 2; row++) assertEquals(StatusCode.OK,
        writer.descriptorRows().update(first, row, overflowTextValues(row, "small")));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    assertEquals(StatusCode.OK, database.checkpoint(new CheckpointResult()));
    assertEquals(StatusCode.OK, first.release());
    assertEquals(StatusCode.OK, second.release());
    assertEquals(StatusCode.OK, database.close());
    // Capture the durable checkpoint before the narrow cache creates sparse spill scratch.
    try (var paths = Files.walk(live)) {
      for (Path source : paths.toList()) {
        Path target = crash.resolve(live.relativize(source));
        if (Files.isDirectory(source)) Files.createDirectories(target);
        else Files.copy(source, target);
      }
    }
    assertEquals(StatusCode.OK, RelationalDatabase.openExisting(overflowRequest(spill), live,
        DATABASE, GENERATION, 8, EmbeddedLockDiagnosticsConfig.disabled(), opened));
    database = opened.database();
    first = new SchemaPin();
    second = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().open(
        firstId, first, new StatusDetail(128)));
    assertEquals(StatusCode.OK, database.services().descriptors().open(
        secondId, second, new StatusDetail(128)));
    writer = session(database);
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.SERIALIZABLE));
    for (int row = 1; row <= 2; row++) assertEquals(StatusCode.OK,
        writer.descriptorRows().insert(second, overflowTextValues(second.descriptor(), row, large),
            new RelationalRowIdentityResult()));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    // Only the WAL decision advances the captured checkpoint; spill bytes are not recovery input.
    try (var paths = Files.list(live)) {
      for (Path source : paths.toList()) {
        if (source.getFileName().toString().startsWith("river.wal")) {
          Files.copy(source, crash.resolve(source.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
    assertEquals(StatusCode.OK, first.release());
    assertEquals(StatusCode.OK, second.release());
    assertEquals(StatusCode.OK, database.close());
    assertEquals(StatusCode.OK, RelationalDatabase.openExisting(overflowRequest(spill), crash,
        DATABASE, GENERATION, 8, EmbeddedLockDiagnosticsConfig.disabled(), opened));
    database = opened.database();
    first = new SchemaPin();
    second = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().open(
        firstId, first, new StatusDetail(128)));
    assertEquals(StatusCode.OK, database.services().descriptors().open(
        secondId, second, new StatusDetail(128)));
    writer = session(database);
    StoredTableRowView fetched = new StoredTableRowView();
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    for (int row = 1; row <= 2; row++) {
      assertEquals(StatusCode.OK, writer.descriptorRows().fetch(first, row, fetched));
      assertEquals(5, fetched.textByteLengthAt(1));
      assertEquals(StatusCode.OK, writer.descriptorRows().fetch(second, row, fetched));
      assertEquals(13_600, fetched.textByteLengthAt(1));
      assertEquals(StatusCode.OK, writer.descriptorRows().fetchByLogicalRowId(second, row, fetched));
      assertEquals(row, fetched.valueAt(0));
    }
    assertEquals(StatusCode.OK, writer.commit(outcome));
    assertEquals(StatusCode.OK, database.checkpoint(new CheckpointResult()));
    ByteBuffer file = ByteBuffer.wrap(Files.readAllBytes(crash.resolve("river.indexed.pages")));
    int retired = 0;
    for (int offset = 0; offset < file.limit();
        offset += io.riverdb.format.page.PageCodec.PAGE_BYTES) {
      if (io.riverdb.format.FormatBytes.getInt(file, offset + 84)
          == io.riverdb.format.page.PageCodec.PAYLOAD_KIND_TUPLE_OVERFLOW
          && io.riverdb.format.FormatBytes.getLong(file,
              offset + io.riverdb.format.page.PageCodec.HEADER_BYTES + 24) != 0) retired++;
    }
    assertEquals(0, retired);
    assertEquals(StatusCode.OK, first.release());
    assertEquals(StatusCode.OK, second.release());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void multiChunkCommitRecoversPrimarySecondaryIdentityAndOverflow(@TempDir Path root)
      throws java.io.IOException {
    Path live = Files.createDirectory(root.resolve("live"));
    Path crash = Files.createDirectory(root.resolve("crash"));
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), live, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    TableDescriptor plain = overflowTextDescriptor();
    KeyDescriptor.Result secondary = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.create(2, KeyDescriptor.KIND_SECONDARY,
        false, plain.columns(), new int[] {0}, 0, secondary, null));
    TableDescriptor.Result indexed = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.create(1, 1, 1, plain.columns(),
        plain.primaryKey(), new KeyDescriptor[] {secondary.value()}, null, indexed, null));
    SchemaPin table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        indexed.value(), table, new StatusDetail(128)));
    long tableId = table.tableId();
    assertEquals(StatusCode.OK, database.checkpoint(new CheckpointResult()));
    RelationalSession writer = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    String large = "😀".repeat(3_400);
    // Row values alone exceed a single physical WAL payload; all three trees share the group.
    org.junit.jupiter.api.Assertions.assertTrue(
        80L * 13_600 > io.riverdb.format.wal.WalRecordCodec.MAX_PAYLOAD_BYTES);
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.SERIALIZABLE));
    for (int row = 1; row <= 80; row++) assertEquals(StatusCode.OK,
        writer.descriptorRows().insert(table, overflowTextValues(table.descriptor(), row, large),
            new RelationalRowIdentityResult()));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    try (var paths = Files.walk(live)) {
      for (Path source : paths.toList()) {
        Path target = crash.resolve(live.relativize(source));
        if (Files.isDirectory(source)) Files.createDirectories(target);
        else Files.copy(source, target);
      }
    }
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());
    assertEquals(StatusCode.OK, RelationalDatabase.openExisting(databaseRequest(8), crash,
        DATABASE, GENERATION, 8, EmbeddedLockDiagnosticsConfig.disabled(), opened));
    database = opened.database();
    table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().open(
        tableId, table, new StatusDetail(128)));
    writer = session(database);
    StoredTableRowView fetched = new StoredTableRowView();
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    for (int row = 1; row <= 80; row++) {
      assertEquals(StatusCode.OK, writer.descriptorRows().fetch(table, row, fetched));
      assertEquals(13_600, fetched.textByteLengthAt(1));
      assertEquals(StatusCode.OK, writer.descriptorRows().fetchByLogicalRowId(table, row, fetched));
      assertEquals(row, fetched.valueAt(0));
    }
    RelationalDescriptorIndexBounds bounds = new RelationalDescriptorIndexBounds();
    assertEquals(StatusCode.OK, bounds.set(table.descriptor().secondaryKeyAt(0),
        null, 0, true, null, 0, true, io.riverdb.storage.btree.TupleBTreeScanBounds.FORWARD));
    RelationalDescriptorScanCursor cursor = new RelationalDescriptorScanCursor();
    assertEquals(StatusCode.OK, writer.descriptorRows().beginIndexScan(
        table, bounds, io.riverdb.tx.api.lock.LockMode.SHARED, cursor));
    RelationalRowIdentityResult identity = new RelationalRowIdentityResult();
    for (int row = 1; row <= 80; row++) {
      assertEquals(StatusCode.OK, writer.descriptorRows().nextScan(cursor, fetched, identity));
      assertEquals(row, fetched.valueAt(0));
      assertEquals(row, identity.logicalRowId());
      assertEquals(13_600, fetched.textByteLengthAt(1));
    }
    assertEquals(StatusCode.CONFLICT, writer.descriptorRows().nextScan(cursor, fetched, identity));
    assertEquals(StatusCode.OK, writer.descriptorRows().closeScan(cursor));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    assertEquals(StatusCode.OK, database.close());
  }

  private static DatabaseResourcePlanRequest overflowRequest(boolean spill) {
    DatabaseResourcePlanRequest request = databaseRequest(8);
    if (spill) {
      var geometry = DatabasePageCacheTestPlan.geometry(32, 4, 800);
      request.indexedPageCache(geometry.maximumRetainedBytes(), geometry.stagingRetainedBytes());
      DatabaseResourcePlan.Result compiled = new DatabaseResourcePlan.Result();
      assertEquals(StatusCode.OK, DatabaseResourcePlan.compile(request, compiled));
      assertEquals(4, compiled.plan().indexedPageCache().stagingFrames());
    }
    return request;
  }

  private static RelationalSession session(RelationalDatabase database) {
    RelationalSessionOpenResult opened = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(opened));
    return opened.session();
  }

  private static TableDescriptor overflowTextDescriptor() { return overflowTextDescriptor(1); }

  private static TableDescriptor overflowTextDescriptor(long tableId) {
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.varchar(4_000)},
        new CharSequence[] {"id", "value"}, new boolean[] {false, false}, columns));
    KeyDescriptor.Result primary = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.create(
        1, KeyDescriptor.KIND_PRIMARY, true, columns.value(), new int[] {0},
        0, primary, null));
    TableDescriptor.Result table = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.create(
        tableId, 1, tableId, columns.value(), primary.value(), null, null, table, null));
    return table.value();
  }

  private static SqlMutationValues overflowTextValues(long key, String text) {
    return overflowTextValues(overflowTextDescriptor(), key, text);
  }

  private static SqlMutationValues overflowTextValues(TableDescriptor table, long key, String text) {
    SqlMutationValues values = new SqlMutationValues();
    assertEquals(StatusCode.OK, values.reserve(table, text.length() * 2));
    assertEquals(StatusCode.OK, values.begin(table, null));
    assertEquals(StatusCode.OK, values.setFixed(0, SqlTypeDescriptor.BIGINT, key));
    assertEquals(StatusCode.OK,
        values.setText(1, SqlTypeDescriptor.varchar(4_000), text));
    return values;
  }
}
