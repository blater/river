# River database how-to

River is currently a pre-V1 embedded database. It provides a Java lifecycle
API and a script-oriented SQL client, but it does not yet provide a standalone
`river-server start|stop` command or a service manager integration.

## Start a database

JDK 25 is required. The hosting application creates the database once, then
opens that same database on later starts:

```java
Path data = Path.of("var/river/main");
Files.createDirectories(data);
DatabaseIncarnation id = DatabaseIncarnation.of(ID_HIGH, ID_LOW);
WalGeneration walGeneration = WalGeneration.of(1);
DatabaseOpenResult opened = new DatabaseOpenResult();

StatusCode status = firstStart
    ? EmbeddedRiver.create(data, id, walGeneration, 64, opened)
    : EmbeddedRiver.openExisting(data, id, walGeneration, 64, opened);
if (!status.isOk()) {
  // Report the status and do not start the listener.
  return;
}

RiverDatabase database = opened.database();
LoopbackServerOpenResult listening = new LoopbackServerOpenResult();
status = LoopbackRiverServer.start(database, 9191, listening);
if (!status.isOk()) {
  database.close();
  // Report the status and stop the process.
  return;
}
LoopbackRiverServer server = listening.server();
```

The application must retain `server` and `database` for shutdown. The
directory must exist before either call. The `ID_HIGH`/`ID_LOW` pair is the
durable database identity: generate it once, store it in application
configuration, and pass the same identity and WAL generation to
`openExisting`. Do not call `create` over an existing database.

The server accepts only loopback connections. `start` is the plain local
development path; authenticated local applications should use
`startAuthenticated` and `RiverDataSource`.

## Run DDL and DML

Build the SQL client distribution:

```sh
./gradlew :river-cli:installDist
```

Put semicolon-terminated SQL in `setup.sql`, for example:

```sql
CREATE TABLE accounts
  (id BIGINT PRIMARY KEY, balance BIGINT, region BIGINT);
CREATE INDEX accounts_region ON accounts(region);

BEGIN SERIALIZABLE;
INSERT INTO accounts VALUES (1, 100, 7), (2, 200, 7), (3, 300, 8);
UPDATE accounts SET balance=250 WHERE id=2;
DELETE FROM accounts WHERE id=3;
COMMIT;

SELECT id, balance FROM accounts ORDER BY id;
CHECKPOINT;
```

Run it against the listening port:

```sh
river-cli/build/install/river-cli/bin/river-cli 9191 < setup.sql
```

DDL and standalone DML statements are transactional and auto-commit. Use
`BEGIN`, `COMMIT`, `ROLLBACK`, and named savepoints when several statements
must be atomic. The client prints tab-separated rows, stops at the first error,
and limits each statement to 64 KB. `CHECKPOINT` is an administrative command
and returns `CONFLICT` inside an explicit transaction.

## Stop safely

Stop accepting work first, then close the database:

```java
StatusCode serverStatus = server.close();
StatusCode databaseStatus = database.close();
```

Check both statuses. Closing the server closes its active connections; a
disconnected session's open transaction is rolled back. Closing the database
returns `CONFLICT` while an embedded API session remains open, so applications
must close queries and sessions before the database. Register this sequence in
the host process's normal shutdown hook or service stop handler.

River recovers durable work from WAL after a crash, but a graceful stop is the
normal operating procedure. Do not terminate the process while copying or
inspecting its files.

## Administration essentials

- Treat the data directory as owned by one River process. Do not edit, rename,
  or selectively copy its control, WAL, checkpoint, or page files.
- Back up only a closed database with `OfflineDatabaseBackup`; its source and
  destination must be different existing directories, and the destination
  must be empty. Restore likewise validates a complete backup and refuses to
  overwrite a non-empty destination.
- Run `OfflineDatabaseInspector` only while the database is closed. A clean
  inspection or backup is evidence about the physical files, not a substitute
  for restoring a backup and checking application data.
- Size `maximumActiveTransactions`, server connection limits, storage, and WAL
  space deliberately. Capacity pressure and conflicts are returned as status
  codes; operators should surface them rather than retry every failure.
- The plain SQL client has no TLS/token options. Do not expose the plain
  loopback listener through a proxy or port forward as a remote service.
- River is pre-V1: internal APIs and on-disk formats may change directly.
  Before upgrading, read the release notes and take a tested offline backup;
  do not assume an unreleased format has an automatic migration path.

See [the SQL CLI notes](../river-cli/README.md),
[the JDBC feature boundary](../river-jdbc/README.md), and the
[SQL conformance profile](compatibility/sql-conformance-profile.md) for the
supported surface, target semantics, and known limits.
