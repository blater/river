# River

Use Java 25. Run `bin/river server --help` (`bin/river.bat server --help` on
Windows) for server commands and options. The server runs in the foreground:

```sh
bin/river server start --port=9191
```

First start creates persistent data and credentials under `~/.river/default`.
Use `--datadir=/absolute/path` for another instance. Startup prints the actual
port and the generated `security/client.properties` path. Port zero selects a
free port. Ctrl-C shuts down the server; starting it again reopens its data.

TLS verifies the server certificate and the generated token authenticates the
client. Keep the generated security files private. Use the client settings path
reported by the current server; no trust-store setup is needed.

The `lib` directory contains the JDBC driver and its dependencies. Add these
JARs to your application's runtime dependencies, then connect:

```java
try (var connection = java.sql.DriverManager.getConnection(
    "jdbc:river:client-file:/absolute/path/security/client.properties")) {
  try (var statement = connection.createStatement()) {
    statement.executeUpdate("CREATE TABLE example (id INTEGER PRIMARY KEY)");
    statement.executeUpdate("INSERT INTO example VALUES (1)");
  }
}
```

`server stop`, `server ps` and credential renewal are later deliveries. Their
help is available, but the commands are not implemented in this candidate.
PostgreSQL clients and remote addresses are not supported yet.
