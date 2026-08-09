package io.riverdb.bench.harness;

import java.nio.file.Path;

/** Non-throwing semantic result; operating-system I/O failures remain IOException. */
public record ArtifactWriteResult(
    ArtifactWriteStatus status,
    Path runDirectory,
    SchemaValidation validation) {
}
