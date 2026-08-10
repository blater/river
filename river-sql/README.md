# River SQL

The first executable subset is intentionally narrow and connected to the real
engine: `CREATE TABLE`, plus point `INSERT`, `SELECT`, `UPDATE`, and `DELETE`
for a named table with a non-negative 48-bit `KEY` and one `BIGINT` `VALUE`.
Each statement uses the durable catalog, transaction manager, authoritative
index, MVCC versions, and WAL recovery.

`BEGIN`, `COMMIT`, and `ROLLBACK` group point statements into one atomic write
set, currently bounded at 64 mutations. DDL remains outside explicit
transactions in this first slice.

`SqlParser` writes into a reusable command and identifier buffer. It does not
build an allocating object tree. Joins, scans, expressions, explicit SQL
savepoints, scans, general schemas/types, constraints, and secondary indexes are
not claimed by this subset.
