# River embedded engine

`EmbeddedDatabase` owns the first usable local database lifecycle. It strictly
creates or opens an existing database directory, assembles the NIO files, local
WAL, indexed page store, transaction manager, and quiescent vacuum, and closes
them in durability order. A session executes the currently supported indexed
point insert, update, delete, and lookup transactions.

This is an implementation-facing embedded API, not the frozen relational API.
It deliberately exposes the working indexed session until catalog, SQL, and
result-stream semantics exist. Missing database files are not invented by
`openExisting`, and `create` never replaces an existing database.
