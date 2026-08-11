# River JDBC slice

The pre-V1 driver accepts `jdbc:river://localhost:PORT` and uses the production
River client, protocol, server, engine, WAL, and storage path. It currently
supports one statement per connection, auto-commit or explicit repeatable-read
and serializable transactions, update counts, and streaming forward-only,
read-only BIGINT result sets. Each `next()` consumes one protocol fetch credit;
the driver does not buffer the result set. Primitive `next()`/`getLong()` use
reusable row carriers and do not create per-row JDBC adapter objects.

`PreparedStatement` currently accepts bounded positional BIGINT parameters.
Values are rendered directly into a statement-owned character buffer as
decimal numerals, so user values cannot inject SQL text; the existing String
engine boundary still requires one rendered SQL String per execution. Parameter
count and rendered length are fixed at statement creation.

Statement and prepared-statement batches hold at most 64 SQL snapshots. They
execute in order through the same transaction state and report the exact
successful prefix in `BatchUpdateException` if an entry fails.

`RiverDataSource` supports both plain loopback connections and the production
TLS 1.3 token-authenticated client path. It owns a private token copy, snapshots
that copy per connection, and erases both snapshots and retained credentials.
Username/password connection overloads remain unsupported; River tokens are
high-entropy credentials, not human passwords.

Projection names, column count, and BIGINT type metadata are available when the
query opens. Server-side prepared plans, other parameter types, generated keys,
LOBs, callable statements, non-loopback URLs, and authenticated JDBC properties
remain unsupported until their production consumers are implemented. Secure
JDBC configuration is currently provided by `RiverDataSource`.

River uses status returns internally. JDBC-mandated `SQLException` objects are
created only at this external adapter boundary.
