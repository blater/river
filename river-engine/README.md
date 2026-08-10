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

`RelationalDatabase` adds the first durable catalog and multiple logical table
names without adding a second storage engine. Catalog records occupy negative
physical keys; table-qualified user keys occupy a bounded positive range. This
lets one existing write set commit mutations to different logical tables in a
single WAL record. The current row contract remains an opaque bounded byte row
with a non-negative 48-bit primary key; SQL types, secondary-index namespaces,
and broader key encodings are the next relational layers.

`SqlSession` executes the initial `KEY`/`VALUE` point-statement SQL subset using
implicit transactions. It is a real end-to-end path through the durable catalog
and storage kernel, while explicit SQL transactions, scans, general schemas,
and secondary indexes remain subsequent vertical slices.
