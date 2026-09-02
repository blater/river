package io.riverdb.engine.table;

import java.util.concurrent.locks.LockSupport;

/** Preserves caller interruption while reactively awaiting commit-writer termination. */
final class IndexedGroupCommitStopWait {
  private IndexedGroupCommitStopWait() {}

  static void await(IndexedGroupCommitCoordinator coordinator) {
    boolean interrupted = false;
    while (!coordinator.stopped()) {
      LockSupport.park();
      if (Thread.interrupted()) interrupted = true;
    }
    if (interrupted) Thread.currentThread().interrupt();
  }
}
