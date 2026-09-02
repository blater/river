package io.riverdb.engine.runtime;

import java.io.IOException;
import java.nio.file.Path;

/** Filesystem boundary used to prove that an admitted spill directory is writable. */
interface RuntimeSpillProbe {
  Path create(Path directory) throws IOException;
  void delete(Path probe) throws IOException;
}
