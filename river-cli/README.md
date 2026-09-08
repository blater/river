# River SQL CLI

The installed `river-cli` reads semicolon-terminated SQL from standard input
through the generated client configuration:

```sh
river-cli /absolute/path/to/database/security/client.properties < setup.sql
```

The client configuration identifies the loopback endpoint, pins the server
certificate, and points to the generated token file. TLS 1.3 verifies the
server; the instance token authenticates the client. Secrets are never command
arguments. Query output is tab separated, followed by a `ROWS` count; command
output reports affected rows and commit sequence. Scripts stop on the first
error and statements are bounded to 64 KB. Plain or unauthenticated transport
is not available.

Statement framing is quote-aware, including semicolons and doubled quotes inside
VARCHAR literals. Result formatting is descriptor-driven: Boolean, scaled
decimal, DATE, TIME, local TIMESTAMP, zoned TIMESTAMP, VARCHAR, and NULL values
use their canonical SQL forms instead of exposing River's primitive encodings.
The bounded catalog commands `SHOW TABLES`, `SHOW INDEXES FROM table`, and
`SHOW COLUMNS FROM table` use the same streaming path. `SHOW TABLES` enumerates
physical tables and views; `SHOW INDEXES` and `SHOW COLUMNS` accept physical
tables only. `SHOW COLUMNS` reports each declared column name and canonical
type, its declared nullability, and its 1-based ordinal. System views and
broader diagnostic commands remain U04 work.
Interactive editing and comments remain deferred.
