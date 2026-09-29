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
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

  @Test
  void oneCommitReusesMultipleOtherTablePagesAndWalOnlyReplayPreservesRows(@TempDir Path root)
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
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.SERIALIZABLE));
    for (int row = 1; row <= 2; row++) assertEquals(StatusCode.OK,
        writer.descriptorRows().insert(second, overflowTextValues(second.descriptor(), row, large),
            new RelationalRowIdentityResult()));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    try (var paths = Files.walk(live)) {
      for (Path source : paths.toList()) {
        Path target = crash.resolve(live.relativize(source));
        if (Files.isDirectory(source)) Files.createDirectories(target);
        else Files.copy(source, target);
      }
    }
    assertEquals(StatusCode.OK, first.release());
    assertEquals(StatusCode.OK, second.release());
    assertEquals(StatusCode.OK, database.close());
    assertEquals(StatusCode.OK, RelationalDatabase.openExisting(databaseRequest(8), crash,
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
