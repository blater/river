package io.riverdb.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.server.app.GeneratedClientFileTestFixture;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RiverSqlMainTest {
  @Test
  void executesScriptThroughRealRemoteDatabase(@TempDir Path root) {
    GeneratedClientFileTestFixture fixture;
    try {
      fixture = GeneratedClientFileTestFixture.open(root);
    } catch (Exception failure) {
      throw new AssertionError("open generated client-file fixture", failure);
    }

    try {
      String script = """
        CREATE TABLE accounts
          (id BIGINT PRIMARY KEY, balance BIGINT, region BIGINT);
        INSERT INTO accounts VALUES (1, 300, 7), (2, 100, 7), (3, 200, 8);
        CREATE INDEX accounts_region ON accounts(region);
        CREATE TABLE regions (id BIGINT PRIMARY KEY, code BIGINT);
        INSERT INTO regions VALUES (7, 7000), (8, 8000);
        CREATE TABLE labels
          (id BIGINT PRIMARY KEY, name VARCHAR(32), state VARCHAR(12) DEFAULT '新規');
        INSERT INTO labels (id, name) VALUES (1, '河川データ庫');
        INSERT INTO labels VALUES (2, NULL, 'old');
        SELECT id, name, state FROM labels ORDER BY id;
        CREATE TABLE typed_output
          (id BIGINT PRIMARY KEY, flag BOOLEAN, amount DECIMAL(6,2), day DATE,
          \sclock TIME(6), observed TIMESTAMP(6),
          \scaptured TIMESTAMP(6) WITH TIME ZONE, note VARCHAR(32));
        INSERT INTO typed_output VALUES
          (1, TRUE, 12.30, DATE '2024-02-29', TIME '01:02:03.123456',
          \sTIMESTAMP '2024-02-29 01:02:03.123456',
          \sTIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00.123456+01:00',
          \s'semi;colon');
        SHOW COLUMNS FROM typed_output;
        SELECT flag,amount,day,clock,observed,captured,note FROM typed_output;
        SELECT accounts.id, regions.code FROM accounts
          JOIN regions ON accounts.region=regions.id
          WHERE accounts.id >= 1 AND accounts.id < 4
            AND accounts.region=7 LIMIT 2;
        SELECT region, COUNT(*) FROM accounts
          WHERE balance >= 150 AND balance < 350
          GROUP BY region ORDER BY region;
        SELECT id, balance FROM accounts ORDER BY balance;
        SELECT d.id, d.balance FROM
          (SELECT id, balance, region FROM accounts WHERE region=7) d
          ORDER BY balance;
        SELECT id, balance FROM accounts WHERE balance=
          (SELECT balance FROM accounts WHERE id=3);
        SELECT id FROM accounts WHERE EXISTS
          (SELECT id FROM accounts WHERE region=8) ORDER BY id;
        """;
      ByteArrayOutputStream standardOutput = new ByteArrayOutputStream();
      ByteArrayOutputStream standardError = new ByteArrayOutputStream();
      int exit = RiverSqlMain.runClientFile(
          fixture.clientFile().toString(),
          new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)),
          new PrintStream(standardOutput, true, StandardCharsets.UTF_8),
          new PrintStream(standardError, true, StandardCharsets.UTF_8));

      assertEquals(0, exit);
      assertEquals("", standardError.toString(StandardCharsets.UTF_8));
      String output = standardOutput.toString(StandardCharsets.UTF_8);
      assertTrue(
          output.contains("id\tcode\n1\t7000\n2\t7000\nROWS\t2\n")
              || output.contains("id\tcode\n2\t7000\n1\t7000\nROWS\t2\n"));
      assertTrue(output.contains("region\tcount\n7\t1\n8\t1\nROWS\t2\n"));
      assertTrue(
          output.contains("id\tbalance\n2\t100\n3\t200\n1\t300\nROWS\t3\n"));
      assertTrue(output.contains("id\tbalance\n2\t100\n1\t300\nROWS\t2\n"));
      assertTrue(output.contains("id\tbalance\n3\t200\nROWS\t1\n"));
      assertTrue(output.contains("id\n1\n2\n3\nROWS\t3\n"));
      assertTrue(output.contains(
          "id\tname\tstate\n1\t河川データ庫\t新規\n2\tNULL\told\nROWS\t2\n"));
      assertTrue(output.contains(
          "column_name\ttype\tis_nullable\tordinal\n"
              + "id\tBIGINT\tFALSE\t1\n"
              + "flag\tBOOLEAN\tTRUE\t2\n"
              + "amount\tDECIMAL(6,2)\tTRUE\t3\n"
              + "day\tDATE\tTRUE\t4\n"
              + "clock\tTIME(6)\tTRUE\t5\n"
              + "observed\tTIMESTAMP(6)\tTRUE\t6\n"
              + "captured\tTIMESTAMP(6) WITH TIME ZONE\tTRUE\t7\n"
              + "note\tVARCHAR(32)\tTRUE\t8\nROWS\t8\n"));
      assertTrue(output.contains(
          "flag\tamount\tday\tclock\tobserved\tcaptured\tnote\n"
              + "TRUE\t12.30\t2024-02-29\t01:02:03.123456\t"
              + "2024-02-29 01:02:03.123456\t"
              + "2023-12-31 23:00:00.123456+00:00\tsemi;colon\nROWS\t1\n"));
    } finally {
      assertEquals(StatusCode.OK, fixture.close());
    }
  }

  @Test
  void executesThroughAuthenticatedTlsWithoutAnArgumentToken(@TempDir Path root)
      throws Exception {
    GeneratedClientFileTestFixture fixture = GeneratedClientFileTestFixture.open(root);
    try {
      ByteArrayOutputStream standardOutput = new ByteArrayOutputStream();
      ByteArrayOutputStream standardError = new ByteArrayOutputStream();
      String script = """
        CREATE TABLE authenticated_types
          (id BIGINT PRIMARY KEY, flag BOOLEAN, amount DECIMAL(6,2),
          \snote VARCHAR(16), day DATE, clock TIME(6),
          \sobserved TIMESTAMP(6), captured TIMESTAMP(6) WITH TIME ZONE);
        INSERT INTO authenticated_types VALUES
          (1, TRUE, 42.70, 'secure', DATE '2024-02-29',
          \sTIME '01:02:03.123456', TIMESTAMP '2024-02-29 01:02:03.123456',
          \sTIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00.123456+01:00');
        SELECT id,flag,amount,note,day,clock,observed,captured
          FROM authenticated_types;
        """;
      int exit = RiverSqlMain.runClientFile(
          fixture.clientFile().toString(),
          new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)),
          new PrintStream(standardOutput, true, StandardCharsets.UTF_8),
          new PrintStream(standardError, true, StandardCharsets.UTF_8));

      assertEquals(0, exit);
      assertEquals("", standardError.toString(StandardCharsets.UTF_8));
      assertTrue(standardOutput.toString(StandardCharsets.UTF_8).contains(
          "id\tflag\tamount\tnote\tday\tclock\tobserved\tcaptured\n"
              + "1\tTRUE\t42.70\tsecure\t2024-02-29\t01:02:03.123456\t"
              + "2024-02-29 01:02:03.123456\t"
              + "2023-12-31 23:00:00.123456+00:00\nROWS\t1\n"));
    } finally {
      assertEquals(StatusCode.OK, fixture.close());
    }
  }
}
