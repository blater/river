# River SQL CLI

`RiverSqlMain PORT` reads semicolon-terminated SQL from standard input and
executes it against the plain loopback River server. Query output is tab
separated, followed by a `ROWS` count; command output reports affected rows and
commit sequence. Scripts stop on the first error and statements are bounded to
64 KiB.

The current SQL grammar has no string literals, quoted identifiers, or comments,
so semicolon splitting is exact for the supported language. TLS/token CLI
configuration and interactive editing remain deferred; authenticated Java
applications can use `RiverDataSource` today.
