package io.riverdb.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.EmbeddedRiver;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.LoopbackServerOpenResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RiverSqlMainTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x434c495445535430L, 0x3030303030303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void executesScriptThroughRealRemoteDatabase(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackServerOpenResult listener = new LoopbackServerOpenResult();
    assertEquals(StatusCode.OK, LoopbackRiverServer.start(database, 0, listener));
    LoopbackRiverServer server = listener.server();

    String script = """
        CREATE TABLE accounts
          (id BIGINT PRIMARY KEY, balance BIGINT, region BIGINT);
        INSERT INTO accounts VALUES (1, 100, 7), (2, 200, 7), (3, 300, 8);
        CREATE INDEX accounts_region ON accounts(region);
        CREATE TABLE regions (id BIGINT PRIMARY KEY, code BIGINT);
        INSERT INTO regions VALUES (7, 7000), (8, 8000);
        SELECT accounts.id, regions.code FROM accounts
          JOIN regions ON accounts.region=regions.id LIMIT 2;
        SELECT region, COUNT(*) FROM accounts GROUP BY region ORDER BY region;
        """;
    ByteArrayOutputStream standardOutput = new ByteArrayOutputStream();
    ByteArrayOutputStream standardError = new ByteArrayOutputStream();
    int exit = RiverSqlMain.run(
        server.port(),
        new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)),
        new PrintStream(standardOutput, true, StandardCharsets.UTF_8),
        new PrintStream(standardError, true, StandardCharsets.UTF_8));

    assertEquals(0, exit);
    assertEquals("", standardError.toString(StandardCharsets.UTF_8));
    String output = standardOutput.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("id\tcode\n1\t7000\n2\t7000\nROWS\t2\n"));
    assertTrue(output.contains("region\tcount\n7\t2\n8\t1\nROWS\t2\n"));
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }
}
