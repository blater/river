package io.riverdb.bench.harness;

import java.io.IOException;
import java.nio.file.Path;

/** Package-private deterministic interposition point for publication race tests. */
@FunctionalInterface
interface ArtifactPublishProbe {
  ArtifactPublishProbe NONE = runDirectory -> { };

  void beforeClaim(Path runDirectory) throws IOException;
}
