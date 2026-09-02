package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Synchronous publication boundary for one completely decoded relational WAL group. */
interface IndexedRelationalWalReplay {
  /** The decoded mutations are borrowed only for this call and reset immediately afterward. */
  StatusCode apply(
      IndexedRelationalMutationBuffer mutations,
      long recordStart,
      long recordEnd,
      long commitSequence,
      long oldestVisibleCommitSequence,
      boolean recovery);
}
