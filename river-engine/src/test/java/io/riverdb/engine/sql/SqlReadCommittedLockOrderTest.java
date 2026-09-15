package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.engine.sql.SqlConcurrencyFixture.Edge;
import java.nio.file.Path;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.io.TempDir;

final class SqlReadCommittedLockOrderTest {
  @RepeatedTest(2)
  void oppositeRowOrdersProduceACycleAndReleaseTheVictim(@TempDir Path root) throws Exception {
    try (var f = new SqlConcurrencyFixture(root)) {
      f.begin(0, "READ COMMITTED");
      f.begin(1, "READ COMMITTED");
      assertEquals(100, f.scalar(0, "SELECT value FROM rows WHERE id=1 FOR UPDATE"));
      f.exec(0, "UPDATE rows SET value=101 WHERE id=1");
      assertEquals(200, f.scalar(1, "SELECT value FROM rows WHERE id=2 FOR UPDATE"));
      f.exec(1, "UPDATE rows SET value=201 WHERE id=2");
      var waiting = f.queue(0, "SELECT value FROM rows WHERE id=2 FOR UPDATE", 1);
      f.deadlock(1, "SELECT value FROM rows WHERE id=1 FOR UPDATE");
      f.exec(1, "ROLLBACK");
      f.completed(waiting);
      assertEquals(200, f.scalar(0, "SELECT value FROM rows WHERE id=2"));
      f.exec(0, "UPDATE rows SET value=202 WHERE id=2");
      f.exec(0, "COMMIT");
      f.cycle(1,
          new Edge(0, 1, "KEY", "EXCLUSIVE", "EXCLUSIVE", "ORDINARY", 4294967297L, 2),
          new Edge(1, 0, "KEY", "EXCLUSIVE", "EXCLUSIVE", "ORDINARY", 4294967297L, 1));
      assertEquals(101, f.scalar(0, "SELECT value FROM rows WHERE id=1"));
      assertEquals(202, f.scalar(0, "SELECT value FROM rows WHERE id=2"));
      f.begin(1, "READ COMMITTED");
      assertEquals(101, f.scalar(1, "SELECT value FROM rows WHERE id=1 FOR UPDATE"));
      f.exec(1, "UPDATE rows SET value=102 WHERE id=1");
      f.exec(1, "COMMIT");
      assertEquals(102, f.scalar(0, "SELECT value FROM rows WHERE id=1"));
    }
  }

  @RepeatedTest(2)
  void matchingRowOrderWaitsWithoutSelectingAVictim(@TempDir Path root) throws Exception {
    try (var f = new SqlConcurrencyFixture(root)) {
      f.begin(0, "READ COMMITTED");
      f.begin(1, "READ COMMITTED");
      assertEquals(100, f.scalar(0, "SELECT value FROM rows WHERE id=1 FOR UPDATE"));
      f.exec(0, "UPDATE rows SET value=101 WHERE id=1");
      var waiting = f.queue(1, "SELECT value FROM rows WHERE id=1 FOR UPDATE", 1);
      assertEquals(200, f.scalar(0, "SELECT value FROM rows WHERE id=2 FOR UPDATE"));
      f.exec(0, "UPDATE rows SET value=201 WHERE id=2");
      f.exec(0, "COMMIT");
      f.completed(waiting);
      f.exec(1, "UPDATE rows SET value=102 WHERE id=1");
      assertEquals(201, f.scalar(1, "SELECT value FROM rows WHERE id=2 FOR UPDATE"));
      f.exec(1, "UPDATE rows SET value=202 WHERE id=2");
      f.exec(1, "COMMIT");
      f.noVictim();
      assertEquals(102, f.scalar(0, "SELECT value FROM rows WHERE id=1"));
      assertEquals(202, f.scalar(0, "SELECT value FROM rows WHERE id=2"));
    }
  }
}
