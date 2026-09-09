# River database how-to

River is a pre-V1 database with an installed foreground `river server` workflow, an
embedded Java API, JDBC, and a script-oriented SQL client. The installed
server creates or reopens one authenticated local instance and publishes the
client configuration needed by JDBC and the SQL client.

## Start an installed database

JDK 25 is required. Start the server in the foreground with a persistent data
directory:

```sh
river server start --datadir=/absolute/path/to/database --port=9191
```

The first start creates the database and credentials. Later starts reopen the
same identity and database. Startup reports the generated
`security/client.properties` path; pass that path to the SQL client or JDBC.
The server accepts only loopback TLS 1.3 connections and the generated token
authenticates the client.

Embedded applications may still own database creation and reopening for
diagnostic or engine-level work. The application must choose `create` only for
an empty data directory and `openExisting` for a committed database, and must
surface either status before opening any client path:

```java
import io.riverdb.engine.EmbeddedLockDiagnosticsConfig;
import io.riverdb.engine.EmbeddedRiver;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.runtime.DatabaseResourcePlanRequest;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import java.nio.file.Files;
import java.nio.file.Path;

DatabaseResourcePlanRequest resources = new DatabaseResourcePlanRequest()
    .memory(256_000_000L, 0L, 0L, 0L, 64_000_000L)
    .lockProviderBytes(8_000_000L)
    .versionWorkspaceBytes(8_000_000L)
    .indexedPageCache(32_000_000L, 8_000_000L)
    .capacity(64, Integer.MAX_VALUE, 800L, 64_000_000L)
    .maximumDelivery(Integer.MAX_VALUE, 800L, 64_000_000L);
Path data = Path.of("var/river/main");
Files.createDirectories(data);
DatabaseIncarnation id = DatabaseIncarnation.of(ID_HIGH, ID_LOW);
WalGeneration walGeneration = WalGeneration.of(1);
DatabaseOpenResult opened = new DatabaseOpenResult();

StatusCode status = firstStart
    ? EmbeddedRiver.create(
        resources, data, id, walGeneration, 64,
        EmbeddedLockDiagnosticsConfig.disabled(), opened)
    : EmbeddedRiver.openExisting(
        resources, data, id, walGeneration, 64,
        EmbeddedLockDiagnosticsConfig.disabled(), opened);
if (!status.isOk()) {
  // Report the status and do not start a listener or client.
  return;
}
RiverDatabase database = opened.database();
```

Both calls explicitly disable embedded lock diagnostics.

The directory must exist before either call. The `ID_HIGH`/`ID_LOW` pair is the
durable database identity: generate it once, store it in application
configuration, and pass the same identity and WAL generation to
`openExisting`. Do not call `create` over an existing database. The installed
`river server` owner is responsible for listener, TLS, credential, and shutdown
lifetime; embedded callers should close the database after their sessions.

## Run DDL and DML

Build the native executable with GraalVM JDK 25:

```sh
GRAALVM_HOME=/path/to/graalvm-jdk-25 ./gradlew --no-daemon :river-server-app:nativeCompile
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

Run it against the generated client configuration:

```sh
bin/river \
  /absolute/path/to/database/security/client.properties < setup.sql
```

DDL and standalone DML statements are transactional and auto-commit. Use
`BEGIN`, `COMMIT`, `ROLLBACK`, and named savepoints when several statements
must be atomic. The client prints tab-separated rows, stops at the first error,
and limits each statement to 64 KB. `CHECKPOINT` is an administrative command
and returns `CONFLICT` inside an explicit transaction.

## Stop safely

The first installed milestone runs in the foreground. Send Ctrl-C to request
the normal shutdown path; it closes the authenticated listener before the
database and preserves committed data for restart. The standalone `stop`
command is a later operational delivery. Embedded callers should stop accepting
work through their own listener owner, then close the database:

```java
StatusCode databaseStatus = database.close();
```

Check the status. A disconnected session's open transaction is rolled back.
Closing the database
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
- The SQL client reads only the generated `client.properties` path. It uses
  TLS 1.3 and token authentication; do not expose the loopback endpoint through
  a proxy or port forward as a remote service.
- River is pre-V1: internal APIs and on-disk formats may change directly.
  Before upgrading, read the release notes and take a tested offline backup;
  do not assume an unreleased format has an automatic migration path.

See [the SQL CLI notes](river-cli/README.md),
[the JDBC feature boundary](river-jdbc/README.md), and the
[SQL conformance profile](docs/compatibility/sql-conformance-profile.md) for the
supported surface, target semantics, and known limits.
