package io.riverdb.engine.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.runtime.RiverRuntimeConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RiverRuntimeConfigTest {
  private static final long TEST_MAXIMUM_MEMORY = 2_048_000_000L;

  @Test
  void missingFileUsesRoundedDefaults(@TempDir Path root) throws IOException {
    RiverRuntimeConfig.Result result = new RiverRuntimeConfig.Result();
    StatusDetail detail = new StatusDetail(512);

    assertEquals(
        StatusCode.OK,
        RiverRuntimeConfig.load(
            root, TEST_MAXIMUM_MEMORY, root.toString(), result, detail));
    RiverRuntimeConfig config = result.config();
    assertEquals(64_000, config.pageBytes());
    assertEquals(256_000_000L, config.cacheBytes());
    assertEquals(32_000_000L, config.schemaCacheBytes());
    assertEquals(64_000_000L, config.sessionShapeCacheBytes());
    assertEquals(4_000, config.cachePages());
    assertEquals(64_000_000L, config.sortRunBytes());
    assertEquals(1_000, config.sortRunPages());
    assertEquals(1_024, config.hashBuildRows());
    assertEquals(2_048, config.hashBuckets());
    assertEquals(1, config.hashPages());
    assertEquals(5_000_000_000L, config.lockWaitTimeoutNanos());
    assertEquals(root.toRealPath(), config.spillDirectory());
    assertEquals(StatusCode.OK, detail.code());
    assertEquals(0, detail.length());
  }

  @Test
  void parsesPositiveLockWaitDurationsAndRejectsInvalidValues(@TempDir Path root)
      throws IOException {
    Path source = root.resolve(RiverRuntimeConfig.FILE_NAME);
    RiverRuntimeConfig.Result result = new RiverRuntimeConfig.Result();
    StatusDetail detail = new StatusDetail(512);
    String[] values = {"1ns", "2us", "3ms", "4s", "5m", "6h"};
    long[] expected = {
        1L,
        2_000L,
        3_000_000L,
        4_000_000_000L,
        300_000_000_000L,
        21_600_000_000_000L
    };
    for (int index = 0; index < values.length; index++) {
      Files.writeString(source,
          "river.tx.lock-wait-timeout=" + values[index] + "\n",
          StandardCharsets.UTF_8);
      assertEquals(StatusCode.OK, RiverRuntimeConfig.load(
          root, TEST_MAXIMUM_MEMORY, root.toString(), result, detail));
      assertEquals(expected[index], result.config().lockWaitTimeoutNanos());
    }

    String[] invalid = {"0ms", "-1ms", "1", "1MS", "9223372036854775807h"};
    for (String value : invalid) {
      Files.writeString(source,
          "river.tx.lock-wait-timeout=" + value + "\n",
          StandardCharsets.UTF_8);
      assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, RiverRuntimeConfig.load(
          root, TEST_MAXIMUM_MEMORY, root.toString(), result, detail), value);
      assertNull(result.config(), value);
      assertTrue(detail.asString().contains("river.tx.lock-wait-timeout"), value);
    }
  }

  @Test
  void uncappedAutoValuesRoundDownToWholePages(@TempDir Path root) {
    RiverRuntimeConfig.Result result = new RiverRuntimeConfig.Result();

    assertEquals(
        StatusCode.OK,
        RiverRuntimeConfig.load(
            root,
            64_000_000L,
            root.toString(),
            result,
            new StatusDetail(512)));
    assertEquals(8_000_000L, result.config().cacheBytes());
    assertEquals(125, result.config().cachePages());
    assertEquals(1_984_000L, result.config().sortRunBytes());
    assertEquals(31, result.config().sortRunPages());
    assertEquals(8_000_000L, result.config().schemaCacheBytes());
    assertEquals(8_000_000L, result.config().sessionShapeCacheBytes());
  }

  @Test
  void parsesDecimalUnitsFirstEqualsAndRelativeSpillOnce(@TempDir Path root)
      throws IOException {
    Files.writeString(
        root.resolve(RiverRuntimeConfig.FILE_NAME),
        "# materialized settings\r\n"
            + "river.sql.materialized.page = 8KB\r\n"
            + "river.sql.materialized.cache=1MB\n"
            + "river.sql.schema-cache=16MB\n"
            + "river.sql.session-shape-cache=24MB\n"
            + "river.sql.materialized.sort-run=16KB\n"
            + "river.sql.join.hash-build-rows=10\n"
            + "river.sql.join.hash-buckets=16\n"
            + "river.sql.materialized.spill-directory=spill=area\n",
        StandardCharsets.UTF_8);
    RiverRuntimeConfig.Result result = new RiverRuntimeConfig.Result();
    StatusDetail detail = new StatusDetail(512);

    assertEquals(
        StatusCode.OK,
        RiverRuntimeConfig.load(
            root, TEST_MAXIMUM_MEMORY, root.toString(), result, detail));
    RiverRuntimeConfig config = result.config();
    assertEquals(8_000, config.pageBytes());
    assertEquals(1_000_000L, config.cacheBytes());
    assertEquals(16_000_000L, config.schemaCacheBytes());
    assertEquals(24_000_000L, config.sessionShapeCacheBytes());
    assertEquals(125, config.cachePages());
    assertEquals(16_000L, config.sortRunBytes());
    assertEquals(2, config.sortRunPages());
    assertEquals(10, config.hashBuildRows());
    assertEquals(16, config.hashBuckets());
    assertEquals(1, config.hashPages());
    assertEquals(root.resolve("spill=area").toRealPath(), config.spillDirectory());

    Files.writeString(
        root.resolve(RiverRuntimeConfig.FILE_NAME),
        "river.sql.materialized.page=16KB\n",
        StandardCharsets.UTF_8);
    assertEquals(8_000, config.pageBytes());
    assertEquals(1_000_000L, config.cacheBytes());
  }

  @Test
  void rejectsMalformedUnknownDuplicateAndIncompatibleValues(@TempDir Path root)
      throws IOException {
    String[] invalid = {
        "missing_equals\n",
        "unknown.setting=1\n",
        "river.sql.materialized.page=8KB\nriver.sql.materialized.page=16KB\n",
        "river.tx.lock-wait-timeout=1ms\nriver.tx.lock-wait-timeout=2ms\n",
        "river.sql.materialized.page=4KiB\n",
        "river.sql.materialized.page=8kb\n",
        "river.sql.materialized.page=8.5KB\n",
        "river.sql.materialized.page=8 KB\n",
        "river.sql.materialized.page=64001\n",
        "river.sql.materialized.cache=-1\n",
        "river.sql.materialized.cache=AUTO\n",
        "river.sql.materialized.cache=999999999999999999999GB\n",
        "river.sql.schema-cache=7MB\n",
        "river.sql.schema-cache=1001MB\n",
        "river.sql.schema-cache=AUTO\n",
        "river.sql.session-shape-cache=7MB\n",
        "river.sql.session-shape-cache=8000KB\n",
        "river.sql.session-shape-cache=1001MB\n",
        "river.sql.session-shape-cache=AUTO\n",
        "river.sql.materialized.page=8KB\\\n",
        "river.sql.materialized.sort-run=1KB\n",
        "river.sql.join.hash-build-rows=0\n",
        "river.sql.join.hash-build-rows=1048577\n",
        "river.sql.join.hash-buckets=3\n",
        "river.sql.materialized.cache=256KB\n"
            + "river.sql.join.hash-build-rows=10000\n",
        "river.sql.materialized.spill-directory=\n"
    };
    RiverRuntimeConfig.Result result = new RiverRuntimeConfig.Result();
    StatusDetail detail = new StatusDetail(512);
    Path source = root.resolve(RiverRuntimeConfig.FILE_NAME);

    for (String text : invalid) {
      Files.writeString(source, text, StandardCharsets.UTF_8);
      assertEquals(
          StatusCode.INVALID_EXTERNAL_INPUT,
          RiverRuntimeConfig.load(
              root, TEST_MAXIMUM_MEMORY, root.toString(), result, detail),
          text);
      assertNull(result.config(), text);
      assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, detail.code(), text);
      assertTrue(detail.length() > 0, text);
    }
  }

  @Test
  void autoSessionShapeCacheUsesClampedHeapEighth(@TempDir Path root) {
    RiverRuntimeConfig.Result result = new RiverRuntimeConfig.Result();
    StatusDetail detail = new StatusDetail(512);

    assertEquals(StatusCode.OK, RiverRuntimeConfig.load(
        root, 400_000_000L, root.toString(), result, detail));
    assertEquals(50_000_000L, result.config().sessionShapeCacheBytes());

    assertEquals(StatusCode.OK, RiverRuntimeConfig.load(
        root, 8_000_000_000L, root.toString(), result, detail));
    assertEquals(64_000_000L, result.config().sessionShapeCacheBytes());
  }

  @Test
  void acceptsSessionShapeCacheBytesAndGigabytes(@TempDir Path root)
      throws IOException {
    Path source = root.resolve(RiverRuntimeConfig.FILE_NAME);
    RiverRuntimeConfig.Result result = new RiverRuntimeConfig.Result();
    StatusDetail detail = new StatusDetail(512);

    Files.writeString(source,
        "river.sql.session-shape-cache=8000000\n", StandardCharsets.UTF_8);
    assertEquals(StatusCode.OK, RiverRuntimeConfig.load(
        root, TEST_MAXIMUM_MEMORY, root.toString(), result, detail));
    assertEquals(8_000_000L, result.config().sessionShapeCacheBytes());

    Files.writeString(source,
        "river.sql.session-shape-cache=1GB\n", StandardCharsets.UTF_8);
    assertEquals(StatusCode.OK, RiverRuntimeConfig.load(
        root, 4_096_000_000L, root.toString(), result, detail));
    assertEquals(1_000_000_000L, result.config().sessionShapeCacheBytes());
  }

  @Test
  void rejectsUnsupportedHeapAndSchemaCacheAboveHalfHeap(@TempDir Path root)
      throws IOException {
    RiverRuntimeConfig.Result result = new RiverRuntimeConfig.Result();
    StatusDetail detail = new StatusDetail(512);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, RiverRuntimeConfig.load(
        root, 31_999_999L, root.toString(), result, detail));
    assertTrue(detail.asString().contains("at least 32MB"));

    Files.writeString(root.resolve(RiverRuntimeConfig.FILE_NAME),
        "river.sql.schema-cache=33MB\n", StandardCharsets.UTF_8);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, RiverRuntimeConfig.load(
        root, 64_000_000L, root.toString(), result, detail));
    assertTrue(detail.asString().contains("half maximum heap"));

    Files.writeString(root.resolve(RiverRuntimeConfig.FILE_NAME),
        "river.sql.schema-cache=24MB\n"
            + "river.sql.session-shape-cache=9MB\n", StandardCharsets.UTF_8);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, RiverRuntimeConfig.load(
        root, 64_000_000L, root.toString(), result, detail));
    assertTrue(detail.asString().contains("combined budget exceeds half maximum heap"));
  }

  @Test
  void relationalOpenOwnsTheConfiguredSchemaCacheBudget(@TempDir Path root)
      throws IOException {
    Files.writeString(root.resolve(RiverRuntimeConfig.FILE_NAME),
        "river.sql.schema-cache=16MB\n", StandardCharsets.UTF_8);
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK, RelationalDatabase.create(
        root, DatabaseIncarnation.of(8_101, 8_103), WalGeneration.of(1), 2, opened));
    assertEquals(16_000_000, opened.database().services().schemaCacheBudgetBytes());
    assertTrue(opened.database().services().schemaCacheMaximumBytes() < 16_000_000);
    assertEquals(StatusCode.OK, opened.database().close());
  }

  @Test
  void acceptsExactBytesAndDecimalGigabytes(@TempDir Path root) throws IOException {
    Files.writeString(
        root.resolve(RiverRuntimeConfig.FILE_NAME),
        "river.sql.materialized.page=8000\n"
            + "river.sql.materialized.cache=1GB\n"
            + "river.sql.materialized.sort-run=16000\n",
        StandardCharsets.UTF_8);
    RiverRuntimeConfig.Result result = new RiverRuntimeConfig.Result();
    StatusDetail detail = new StatusDetail(512);

    assertEquals(
        StatusCode.OK,
        RiverRuntimeConfig.load(
            root, TEST_MAXIMUM_MEMORY, root.toString(), result, detail));
    assertEquals(8_000, result.config().pageBytes());
    assertEquals(1_000_000_000L, result.config().cacheBytes());
    assertEquals(16_000L, result.config().sortRunBytes());
  }

  @Test
  void invalidLoadArgumentsSetDetailAndResetResult(@TempDir Path root) {
    RiverRuntimeConfig.Result result = new RiverRuntimeConfig.Result();
    StatusDetail detail = new StatusDetail(512);

    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        RiverRuntimeConfig.load(null, result, detail));
    assertNull(result.config());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, detail.code());
    assertTrue(detail.length() > 0);
  }

  @Test
  void invalidReloadClearsResultBeforeAnySpillSideEffect(@TempDir Path root)
      throws IOException {
    RiverRuntimeConfig.Result result = new RiverRuntimeConfig.Result();
    StatusDetail detail = new StatusDetail(512);
    assertEquals(
        StatusCode.OK,
        RiverRuntimeConfig.load(
            root, TEST_MAXIMUM_MEMORY, root.toString(), result, detail));

    Path spill = root.resolve("must-not-exist");
    Files.writeString(
        root.resolve(RiverRuntimeConfig.FILE_NAME),
        "river.sql.materialized.page=invalid\n"
            + "river.sql.materialized.spill-directory=must-not-exist\n",
        StandardCharsets.UTF_8);

    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        RiverRuntimeConfig.load(
            root, TEST_MAXIMUM_MEMORY, root.toString(), result, detail));
    assertNull(result.config());
    assertFalse(Files.exists(spill));
    assertTrue(detail.asString().contains("river.sql.materialized.page"));
  }

  @Test
  void boundsBytesLinesAndUtf8BeforeParsing(@TempDir Path root) throws IOException {
    RiverRuntimeConfig.Result result = new RiverRuntimeConfig.Result();
    StatusDetail detail = new StatusDetail(512);
    Path source = root.resolve(RiverRuntimeConfig.FILE_NAME);

    Files.write(source, new byte[RiverRuntimeConfig.MAXIMUM_CONFIG_BYTES + 1]);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        RiverRuntimeConfig.load(
            root, TEST_MAXIMUM_MEMORY, root.toString(), result, detail));
    assertTrue(detail.asString().contains("16384"));

    Files.writeString(
        source,
        "#" + "x".repeat(RiverRuntimeConfig.MAXIMUM_LINE_BYTES) + "\n",
        StandardCharsets.UTF_8);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        RiverRuntimeConfig.load(
            root, TEST_MAXIMUM_MEMORY, root.toString(), result, detail));
    assertTrue(detail.asString().contains("4096"));

    Files.write(source, new byte[] {(byte) 0xc3, (byte) 0x28});
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        RiverRuntimeConfig.load(
            root, TEST_MAXIMUM_MEMORY, root.toString(), result, detail));
    assertTrue(detail.asString().contains("UTF-8"));
  }

  @Test
  void rejectsEmptyDefaultTemporaryDirectoryAndNonDirectorySpill(@TempDir Path root)
      throws IOException {
    RiverRuntimeConfig.Result result = new RiverRuntimeConfig.Result();
    StatusDetail detail = new StatusDetail(512);

    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        RiverRuntimeConfig.load(root, TEST_MAXIMUM_MEMORY, " \t", result, detail));
    assertTrue(detail.asString().contains("java.io.tmpdir"));

    Path file = root.resolve("not-a-directory");
    Files.writeString(file, "occupied", StandardCharsets.UTF_8);
    Files.writeString(
        root.resolve(RiverRuntimeConfig.FILE_NAME),
        "river.sql.materialized.spill-directory=not-a-directory\n",
        StandardCharsets.UTF_8);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        RiverRuntimeConfig.load(
            root, TEST_MAXIMUM_MEMORY, root.toString(), result, detail));
    assertTrue(detail.asString().contains("not a directory"));
    assertFalse(Files.isDirectory(file));
  }


  @Test
  void configurationReadFailureReturnsIoDetail(@TempDir Path root) throws IOException {
    Files.createDirectory(root.resolve(RiverRuntimeConfig.FILE_NAME));
    RiverRuntimeConfig.Result result = new RiverRuntimeConfig.Result();
    StatusDetail detail = new StatusDetail(512);

    assertEquals(
        StatusCode.IO_FAILURE,
        RiverRuntimeConfig.load(
            root, TEST_MAXIMUM_MEMORY, root.toString(), result, detail));
    assertNull(result.config());
    assertEquals(StatusCode.IO_FAILURE, detail.code());
    assertTrue(detail.asString().contains("cannot read configuration"));
  }
}
