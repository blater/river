package io.riverdb.platform.riverd;

import io.riverdb.platform.file.DurableFile;

/** A regular-file capability retained from a verified descriptor. */
public interface RiverFile extends DurableFile {
  FileIdentity identity();
}
