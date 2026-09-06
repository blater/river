package io.riverdb.engine.table;

import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** Temporary tic-f539 timing probe; one reusable event owned by the writer. */
@Name("river.CommitOpportunity")
@StackTrace(false)
final class IndexedCommitOpportunityEvent extends Event {
  long submitted;
  long enqueued;
  long selected;
  long processStarted;
  long published;
  long forceStarted;
  long forceFinished;
  long completed;
  int groupSize;
  boolean successful;
}
