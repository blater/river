package io.riverdb.engine.table;

import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** Temporary tic-f539 quiescent capture boundary, on the same nanoTime clock. */
@Name("river.CommitCapture")
@StackTrace(false)
final class IndexedCommitCaptureEvent extends Event {
  long stamp;
  boolean opening;

  static void record(boolean opening) {
    var event = new IndexedCommitCaptureEvent();
    event.stamp = System.nanoTime();
    event.opening = opening;
    event.commit();
  }
}
