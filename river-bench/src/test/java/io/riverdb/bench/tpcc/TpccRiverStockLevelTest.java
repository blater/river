package io.riverdb.bench.tpcc;

import static io.riverdb.bench.tpcc.TpccTestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.EmbeddedRiver;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.jdbc.RiverConnectionMetrics;
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.LoopbackServerOpenResult;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TpccRiverStockLevelTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(8_201, 8_203);

  @Test
  void executesCompleteStockLevelInOneRequest(@TempDir Path root) throws Exception {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK,
        EmbeddedRiver.create(
            databaseRequest(8), root, DATABASE, WalGeneration.of(1), 8, opened));
    RiverDatabase database = opened.database();
    LoopbackServerOpenResult serverResult = new LoopbackServerOpenResult();
    assertEquals(StatusCode.OK, LoopbackRiverServer.start(database, 0, serverResult));
    LoopbackRiverServer server = serverResult.server();
    try (Connection connection = DriverManager.getConnection(
        "jdbc:river://localhost:" + server.port())) {
      createStockLevelData(connection);
      RiverConnectionMetrics metrics = connection.unwrap(RiverConnectionMetrics.class);
      try (TpccRiverStockLevel transaction = new TpccRiverStockLevel(connection)) {
        TpccInputs.StockLevel input = new TpccInputs.StockLevel();
        input.warehouse = 1;
        input.district = 1;
        input.threshold = 10;
        long requests = metrics.completedRequests();

        assertTrue(transaction.execute(input));

        assertEquals(requests + 1, metrics.completedRequests());
        assertEquals(1, transaction.lowStockCount());
      }
    } finally {
      assertEquals(StatusCode.OK, server.close());
      assertEquals(StatusCode.OK, database.close());
    }
  }

  private static void createStockLevelData(Connection connection) throws Exception {
    try (Statement statement = connection.createStatement()) {
      assertEquals(0, statement.executeUpdate(
          "CREATE TABLE district (d_w_id SMALLINT NOT NULL,d_id SMALLINT NOT NULL,"
              + "d_next_o_id INTEGER NOT NULL,PRIMARY KEY(d_w_id,d_id))"));
      assertEquals(0, statement.executeUpdate(
          "CREATE TABLE stock (s_w_id SMALLINT NOT NULL,s_i_id INTEGER NOT NULL,"
              + "s_quantity SMALLINT NOT NULL,PRIMARY KEY(s_w_id,s_i_id))"));
      assertEquals(0, statement.executeUpdate(
          "CREATE TABLE order_line (ol_w_id SMALLINT NOT NULL,ol_d_id SMALLINT NOT NULL,"
              + "ol_o_id INTEGER NOT NULL,ol_number SMALLINT NOT NULL,ol_i_id INTEGER NOT NULL,"
              + "ol_supply_w_id SMALLINT NOT NULL,PRIMARY KEY(ol_w_id,ol_d_id,ol_o_id,ol_number))"));
      assertEquals(1, statement.executeUpdate("INSERT INTO district VALUES (1,1,25)"));
      assertEquals(3, statement.executeUpdate(
          "INSERT INTO stock VALUES (1,1,5),(1,2,30),(2,1,30)"));
      assertEquals(4, statement.executeUpdate(
          "INSERT INTO order_line VALUES "
              + "(1,1,5,1,1,2),(1,1,6,1,1,1),(1,1,7,1,2,1),(1,1,25,1,1,1)"));
    }
  }
}
