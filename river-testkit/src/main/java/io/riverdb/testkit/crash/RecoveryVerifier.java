package io.riverdb.testkit.crash;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DurableFile;

/** Verifies reopened durable state after one simulated process loss. */
@FunctionalInterface
public interface RecoveryVerifier {
  StatusCode verify(int cycle, DurableFile reopenedFile);
}
