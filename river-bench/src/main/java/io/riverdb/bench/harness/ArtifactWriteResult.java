package io.riverdb.bench.harness;

import java.nio.file.Path;

/**
 * Non-throwing semantic result; operating-system I/O failures remain IOException.
 *
 * @param runDirectory intended {@code run-id/artifacts/} path; it exists as a complete tree only
 *     when status is {@link ArtifactWriteStatus#WRITTEN}
 */
public record ArtifactWriteResult(
    ArtifactWriteStatus status,
    Path runDirectory,
    SchemaValidation validation) {
}
