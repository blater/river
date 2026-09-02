package io.riverdb.engine;

import io.riverdb.base.error.StatusCode;

/** Intrusive ownership hook for database-deferred session cleanup. */
interface TerminalSessionCleanupTarget {
  boolean transferToTerminalCleanup(Object owner);
  StatusCode retryTerminalClose();
  TerminalSessionCleanupTarget terminalCleanupNext();
  void terminalCleanupNext(TerminalSessionCleanupTarget next);
}
