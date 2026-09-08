package io.riverdb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.riverdb.base.error.StatusCode;
import io.riverdb.server.app.GeneratedClientFileTestFixture;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RiverDataSourceTest {
  @Test
  void opensAuthenticatedConnectionFromGeneratedClientFile(@TempDir Path root) throws Exception {
    GeneratedClientFileTestFixture fixture = GeneratedClientFileTestFixture.open(root);
    RiverDataSource source = new RiverDataSource();
    try {
      source.setClientFile(fixture.clientFile());
      assertEquals(fixture.clientFile(), source.getClientFile());
      try (Connection connection = source.getConnection();
          Statement statement = connection.createStatement()) {
        assertEquals(0, statement.executeUpdate(
            "CREATE TABLE datasource_probe (id INTEGER PRIMARY KEY)"));
        assertEquals(1, statement.executeUpdate("INSERT INTO datasource_probe VALUES (1)"));
      }
    } finally {
      source.close();
      StatusCode status = fixture.close();
      if (status != StatusCode.OK && status != StatusCode.CLOSED) {
        throw new AssertionError("close fixture: " + status);
      }
    }
  }

  @Test
  void rejectsUnconfiguredAndUnsafeClientFile(@TempDir Path root) throws Exception {
    RiverDataSource source = new RiverDataSource();
    try {
      assertThrows(SQLException.class, source::getConnection);
      assertThrows(SQLException.class, () -> source.setClientFile(Path.of("client.properties")));
      assertThrows(SQLException.class, () -> source.setClientFile(
          root.toAbsolutePath().resolve("instance/../client.properties")));
    } finally {
      source.close();
    }
  }
}
