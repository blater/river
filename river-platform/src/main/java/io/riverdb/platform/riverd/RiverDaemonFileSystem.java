package io.riverdb.platform.riverd;

import io.riverdb.base.error.StatusCode;
import java.nio.file.Path;

/** Portable riverd filesystem contract implemented by platform adapters. */
public interface RiverDaemonFileSystem {
  /** Opens an existing verified ancestor such as a user home directory. */
  StatusCode openAncestor(Path path, RiverDirectoryResult result);

  /** Opens an existing private instance/security root. */
  StatusCode openDirectory(Path path, RiverDirectoryResult result);

  StatusCode acquireExclusive(RiverFile file, RiverLockResult result);
}
