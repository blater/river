package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Recursive Boolean service used by one nested child scanner. */
interface SqlSubqueryCandidateEvaluator {
  StatusCode accept(int block);
  boolean accepted(int block);
}
