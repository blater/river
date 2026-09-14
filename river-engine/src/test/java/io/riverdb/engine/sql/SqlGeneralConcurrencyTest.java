package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.engine.sql.SqlConcurrencyFixture.Edge;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.io.TempDir;

final class SqlGeneralConcurrencyTest {
  private static final String RR = "REPEATABLE READ";
  private static final String S = "SERIALIZABLE";

  @Test
  void pointCyclesAndAdjacentControlsAcrossIsolationPairs(@TempDir Path root) throws Exception {
    String[][] pairs = {{RR, RR}, {RR, S}, {S, S}};
    for (int pair = 0; pair < pairs.length; pair++) {
      for (int repeat = 0; repeat < 2; repeat++) {
        try (var f = new SqlConcurrencyFixture(root.resolve("cycle-" + pair + "-" + repeat))) {
          f.begin(0, pairs[pair][0]);
          f.begin(1, pairs[pair][1]);
          f.exec(0, "UPDATE rows SET value=101 WHERE id=1");
          f.exec(1, "UPDATE rows SET value=201 WHERE id=2");
          var first = f.queue(0, "UPDATE rows SET value=202 WHERE id=2", 1);
          f.deadlock(1, "UPDATE rows SET value=102 WHERE id=1");
          f.completed(first);
          f.exec(1, "ROLLBACK");
          f.exec(0, "COMMIT");
          f.cycle(1,
              new Edge(0, 1, pair == 2 ? "TUPLE_KEY" : "KEY", "EXCLUSIVE", "EXCLUSIVE", "ORDINARY", pair == 2 ? 1 : 4294967297L, pair == 2 ? 0 : 2),
              new Edge(1, 0, pair == 2 ? "TUPLE_KEY" : "KEY", "EXCLUSIVE", "EXCLUSIVE", "ORDINARY", pair == 2 ? 1 : 4294967297L, pair == 2 ? 0 : 1));
          assertEquals(101, f.scalar(0, "SELECT value FROM rows WHERE id=1"));
          assertEquals(202, f.scalar(0, "SELECT value FROM rows WHERE id=2"));
          f.begin(1, pairs[pair][1]);
          f.exec(1, "UPDATE rows SET value=203 WHERE id=2");
          f.exec(1, "COMMIT");
          assertEquals(203, f.scalar(0, "SELECT value FROM rows WHERE id=2"));
        }
        try (var f = new SqlConcurrencyFixture(root.resolve("control-" + pair + "-" + repeat))) {
          f.begin(0, pairs[pair][0]);
          f.begin(1, pairs[pair][1]);
          f.exec(0, "UPDATE rows SET value=101 WHERE id=1");
          var waiting = f.queue(1, "UPDATE rows SET value=102 WHERE id=1", 1);
          f.exec(0, "COMMIT");
          f.completed(waiting);
          f.exec(1, "COMMIT");
          assertEquals(102, f.scalar(0, "SELECT value FROM rows WHERE id=1"));
          f.noVictim();
        }
      }
    }
  }

  @RepeatedTest(2)
  void secondaryIndexThreeOwnerCycle(@TempDir Path root) throws Exception {
    try (var f = new SqlConcurrencyFixture(root)) {
      f.secondaryPlan("SELECT id FROM rows WHERE category=20");
      for (int owner = 0; owner < 3; owner++) {
        f.begin(owner, S);
        f.exec(owner, "UPDATE rows SET value=value+1 WHERE category=" + ((owner + 1) * 10));
      }
      var first = f.queue(0, "UPDATE rows SET value=202 WHERE category=20", 1);
      var second = f.queue(1, "UPDATE rows SET value=302 WHERE category=30", 2);
      f.deadlock(2, "UPDATE rows SET value=102 WHERE category=10");
      f.exec(2, "ROLLBACK");
      f.completed(second);
      f.exec(1, "COMMIT");
      f.completed(first);
      f.exec(0, "COMMIT");
      f.cycle(2,
          new Edge(0, 1, "TUPLE_RANGE", "UPDATE", "UPDATE", "ORDINARY", 2, 0),
          new Edge(1, 2, "TUPLE_RANGE", "UPDATE", "UPDATE", "ORDINARY", 2, 0),
          new Edge(2, 0, "TUPLE_RANGE", "UPDATE", "UPDATE", "ORDINARY", 2, 0));
      assertEquals(List.of(101L, 202L, 302L), f.scan(0, "SELECT value FROM rows ORDER BY id"));
      f.begin(2, S);
      f.exec(2, "UPDATE rows SET value=303 WHERE category=30");
      f.exec(2, "COMMIT");
    }
  }

  @RepeatedTest(2)
  void conversionCycle(@TempDir Path root) throws Exception {
    try (var f = new SqlConcurrencyFixture(root)) {
      f.begin(0, S);
      f.begin(1, S);
      assertEquals(100, f.scalar(0, "SELECT value FROM rows WHERE id=1"));
      assertEquals(100, f.scalar(1, "SELECT value FROM rows WHERE id=1"));
      var first = f.queue(0, "UPDATE rows SET value=101 WHERE id=1", 1);
      f.deadlock(1, "UPDATE rows SET value=102 WHERE id=1");
      f.exec(1, "ROLLBACK");
      f.completed(first);
      f.exec(0, "COMMIT");
      f.cycle(1,
          new Edge(0, 1, "KEY", "EXCLUSIVE", "SHARED", "CONVERSION", 4294967297L, 1),
          new Edge(1, 0, "TUPLE_KEY", "EXCLUSIVE", "EXCLUSIVE", "ORDINARY", 1, 0));
      f.begin(1, S);
      assertEquals(101, f.scalar(1, "SELECT value FROM rows WHERE id=1"));
      f.exec(1, "COMMIT");
    }
  }

  @RepeatedTest(2)
  void queueOrderClosesRealSqlCycle(@TempDir Path root) throws Exception {
    try (var f = new SqlConcurrencyFixture(root)) {
      for (int owner = 0; owner < 3; owner++) f.begin(owner, S);
      f.scalar(0, "SELECT value FROM rows WHERE id=1");
      f.exec(2, "UPDATE rows SET value=201 WHERE id=2");
      var second = f.queue(1, "UPDATE rows SET value=101 WHERE id=1", 1);
      var first = f.queue(0, "UPDATE rows SET value=202 WHERE id=2", 2);
      f.deadlock(2, "SELECT value FROM rows WHERE id=1");
      f.exec(2, "ROLLBACK");
      f.completed(first);
      f.exec(0, "COMMIT");
      f.completed(second);
      f.exec(1, "COMMIT");
      f.cycle(2,
          new Edge(0, 2, "TUPLE_KEY", "EXCLUSIVE", "EXCLUSIVE", "ORDINARY", 1, 0),
          new Edge(1, 0, "KEY", "EXCLUSIVE", "SHARED", "ORDINARY", 4294967297L, 1),
          new Edge(2, 1, "KEY", "SHARED", "null", "ORDINARY", 4294967297L, 1));
      f.begin(2, S);
      assertEquals(101, f.scalar(2, "SELECT value FROM rows WHERE id=1"));
      f.exec(2, "COMMIT");
    }
  }
}
