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

Column count and BIGINT type metadata are available when the query opens.
Column names are not yet carried by the engine API or wire contract, so the
temporary pre-V1 labels are `column1` through `column8`. Server-side prepared
plans, other parameter types, batching, generated keys, LOBs, callable
statements, non-loopback URLs, and authenticated JDBC properties remain
unsupported until their production consumers are implemented.

River uses status returns internally. JDBC-mandated `SQLException` objects are
created only at this external adapter boundary.
