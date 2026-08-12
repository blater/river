package io.riverdb.testkit.crash;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DurableFile;

/** One pre-crash workload step. The file handle is invalidated immediately afterwards. */
@FunctionalInterface
public interface CrashWorkload {
  StatusCode run(int cycle, DurableFile file);
}
