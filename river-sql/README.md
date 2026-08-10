# River SQL

The first executable subset is intentionally narrow and connected to the real
engine: `CREATE TABLE`, plus point `INSERT`, `SELECT`, `UPDATE`, and `DELETE`
for a named table with a non-negative 48-bit `KEY` and one `BIGINT` `VALUE`.
Each statement uses the durable catalog, transaction manager, authoritative
index, MVCC versions, and WAL recovery.

`SqlParser` writes into a reusable command and identifier buffer. It does not
build an allocating object tree. Joins, scans, expressions, explicit SQL
transaction control, general schemas/types, constraints, and secondary indexes
are not claimed by this subset.
