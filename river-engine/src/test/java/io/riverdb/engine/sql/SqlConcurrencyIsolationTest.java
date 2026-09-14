package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.io.TempDir;

final class SqlConcurrencyIsolationTest {
  @RepeatedTest(2)
  void repeatableReadsSurviveCommittedPointUpdates(@TempDir Path root) throws Exception {
    for (String writer : List.of("REPEATABLE READ", "SERIALIZABLE")) {
      for (String key : List.of("id=1", "category=10")) {
        try (var f = new SqlConcurrencyFixture(root.resolve(writer + key))) {
          String query = "SELECT value FROM rows WHERE " + key;
          if (key.startsWith("category")) {
            f.secondaryPlan(query);
          }
          f.begin(0, "REPEATABLE READ");
          assertEquals(List.of(100L), f.scan(0, query));
          f.begin(1, writer);
          f.exec(1, "UPDATE rows SET value=101 WHERE " + key);
          f.exec(1, "COMMIT");
          assertEquals(List.of(100L), f.scan(0, query));
          f.exec(0, "COMMIT");
          f.begin(0, "REPEATABLE READ");
          assertEquals(List.of(101L), f.scan(0, query));
          f.exec(0, "COMMIT");
          f.noVictim();
        }
      }
    }
  }

  @RepeatedTest(2)
  void serializableRangesProtectInsideAndPermitOutsideInsert(@TempDir Path root) throws Exception {
    for (String writer : List.of("REPEATABLE READ", "SERIALIZABLE")) {
      for (boolean secondary : List.of(false, true)) {
        try (var f = new SqlConcurrencyFixture(root.resolve(writer + secondary))) {
          String predicate = secondary ? "category>=10 AND category<20" : "id>=1 AND id<10";
          String query = "SELECT value FROM rows WHERE " + predicate;
          List<Long> initial = secondary ? List.of(100L) : List.of(100L, 200L, 300L);
          if (secondary) f.secondaryPlan(query);
          f.begin(0, "SERIALIZABLE");
          assertEquals(initial, f.scan(0, query));
          f.begin(1, writer);
          f.exec(1, "INSERT INTO rows VALUES (40,40,400)");
          f.exec(1, "COMMIT");
          f.begin(1, writer);
          f.block("TUPLE_KEY", "EXCLUSIVE", "SHARED", "ORDINARY", "ACTIVE_OWNER",
              "ACTIVE_OWNER", "NO_INCOMPATIBLE_ACTIVE_OWNER");
          String inside = "INSERT INTO rows VALUES (5,15,150)";
          var pending = f.queue(1, inside, 1);
          assertEquals(initial, f.scan(0, query));
          f.exec(0, "COMMIT");
          f.completed(pending);
          f.exec(1, "COMMIT");
          assertEquals(400, f.scalar(0, "SELECT value FROM rows WHERE id=40"));
          if (secondary) assertEquals(List.of(100L, 150L), f.scan(0, query));
          else assertEquals(List.of(100L, 200L, 300L, 150L), f.scan(0, query));
          f.noVictim();
        }
      }
    }
  }

  @RepeatedTest(2)
  void conversionAndFifoAcyclicControls(@TempDir Path root) throws Exception {
    try (var f = new SqlConcurrencyFixture(root.resolve("conversion"))) {
      for (int i = 0; i < 3; i++) f.begin(i, "SERIALIZABLE");
      f.scalar(0, "SELECT value FROM rows WHERE id=1");
      f.scalar(1, "SELECT value FROM rows WHERE id=1");
      var update = f.queue(0, "UPDATE rows SET value=101 WHERE id=1", 1);
      f.block("KEY", "EXCLUSIVE", "SHARED", "CONVERSION", "ACTIVE_OWNER",
          "ACTIVE_OWNER", "NO_INCOMPATIBLE_ACTIVE_OWNER");
      f.block("KEY", "SHARED", "EXCLUSIVE", "ORDINARY", "CONVERSION",
          "CONVERSION_PRIORITY", "CONVERSION_QUEUE_EMPTY");
      var reader = f.queue(2, "SELECT value FROM rows WHERE id=1", 2);
      f.exec(1, "COMMIT");
      f.completed(update);
      f.exec(0, "COMMIT");
      f.completed(reader);
      f.exec(2, "COMMIT");
      f.noVictim();
    }
    try (var f = new SqlConcurrencyFixture(root.resolve("fifo"))) {
      for (int i = 0; i < 3; i++) f.begin(i, "SERIALIZABLE");
      f.scalar(0, "SELECT value FROM rows WHERE id=1");
      f.exec(2, "UPDATE rows SET value=201 WHERE id=2");
      f.block("KEY", "EXCLUSIVE", "SHARED", "ORDINARY", "ACTIVE_OWNER",
          "ACTIVE_OWNER", "NO_INCOMPATIBLE_ACTIVE_OWNER");
      f.block("TUPLE_KEY", "EXCLUSIVE", "EXCLUSIVE", "ORDINARY", "ACTIVE_OWNER",
          "ACTIVE_OWNER", "NO_INCOMPATIBLE_ACTIVE_OWNER");
      var second = f.queue(1, "UPDATE rows SET value=101 WHERE id=1", 1);
      var first = f.queue(0, "UPDATE rows SET value=202 WHERE id=2", 2);
      f.exec(2, "COMMIT");
      f.completed(first);
      f.exec(0, "COMMIT");
      f.completed(second);
      f.exec(1, "COMMIT");
      f.noVictim();
    }
  }
}
