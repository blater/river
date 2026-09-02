package io.riverdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DatabaseOpenDiagnosticTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(8_101, 8_107);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void publicOpenCopiesConfigurationDetailAndPublishesNoHandle(@TempDir Path root)
      throws IOException {
    Files.writeString(
        root.resolve("river.properties"),
        "river.sql.materialized.page=64001\n",
        StandardCharsets.UTF_8);
    DatabaseOpenResult opened = new DatabaseOpenResult();

    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 4, opened));
    assertNull(opened.database());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, opened.detail().code());
    assertTrue(opened.detail().asString().contains("materialized.page"));
    try (Stream<Path> files = Files.list(root)) {
      assertEquals(1, files.count());
    }

    Files.delete(root.resolve("river.properties"));
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 4, opened));
    assertEquals(StatusCode.OK, opened.detail().code());
    assertEquals(0, opened.detail().length());
    assertEquals(StatusCode.OK, opened.database().close());
  }

  @Test
  void relationalResultClearsPriorDetailOnSuccessfulReuse(@TempDir Path root)
      throws IOException {
    Path properties = root.resolve("river.properties");
    Files.writeString(
        properties,
        "river.sql.join.hash-buckets=3\n",
        StandardCharsets.UTF_8);
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();

    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        RelationalDatabase.create(root, DATABASE, GENERATION, 4, opened));
    assertNull(opened.database());
    assertTrue(opened.detail().length() > 0);

    Files.delete(properties);
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 4, opened));
    assertEquals(StatusCode.OK, opened.detail().code());
    assertEquals(0, opened.detail().length());
    assertEquals(StatusCode.OK, opened.database().close());
  }

  @Test
  void directRelationalInvalidDirectorySetsMatchingDetail() {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();

    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        RelationalDatabase.create(null, DATABASE, GENERATION, 4, opened));
    assertNull(opened.database());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, opened.detail().code());
  }

  @Test
  void quorumOpenReadsOnlyPrimaryConfiguration(@TempDir Path root) throws IOException {
    Path primary = root.resolve("primary");
    Path follower = root.resolve("follower");
    Files.createDirectories(primary);
    Files.createDirectories(follower);
    Files.writeString(
        primary.resolve("river.properties"),
        "river.sql.materialized.page=8KB\n"
            + "river.sql.materialized.cache=1MB\n"
            + "river.sql.materialized.sort-run=16KB\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        follower.resolve("river.properties"),
        "river.sql.materialized.page=invalid\n",
        StandardCharsets.UTF_8);
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();

    assertEquals(
        StatusCode.OK,
        RelationalDatabase.createWithDurableWalQuorum(
            primary,
            new Path[] {follower},
            2,
            DATABASE,
            GENERATION,
            4,
            opened));
    assertEquals(StatusCode.OK, opened.database().close());
  }
}
