package io.riverdb.engine.sql;

import io.riverdb.sql.SqlComparison;

/** Provides a mandatory predicate candidate encoded as a nonnegative handle. */
interface SqlDescriptorIndexCandidateSource {
  int find(int column, SqlComparison comparison);
}
