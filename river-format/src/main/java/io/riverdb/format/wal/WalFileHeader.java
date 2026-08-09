package io.riverdb.format.wal;

import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;

/** Trusted identity decoded from a local WAL file header. */
public record WalFileHeader(
    DatabaseIncarnation databaseIncarnation,
    WalGeneration walGeneration) {
}
