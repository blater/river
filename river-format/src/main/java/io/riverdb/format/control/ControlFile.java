package io.riverdb.format.control;

import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;

/** Trusted logical contents of the v1 database control file. */
public record ControlFile(
    DatabaseIncarnation databaseIncarnation,
    WalGeneration walGeneration) {
}
