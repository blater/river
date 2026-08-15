package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Composes one validated stored view with its outer query and metadata. */
final class SqlViewCompiler {
  private final SqlQuery query;
  private final SqlDerivedQueryCompiler derived;

  SqlViewCompiler(SqlQuery ownedQuery, SqlDerivedQueryCompiler derivedCompiler) {
    query = ownedQuery;
    derived = derivedCompiler;
  }

  StatusCode compile(
      SqlCommand outer,
      SqlCommand view,
      SqlCommand destination,
      int outerDepth,
      int viewDepth,
      boolean explain,
      boolean analyze) {
    query.reset();
    StatusCode status = validate(
        outer, view, destination, outerDepth, viewDepth, explain, analyze);
    if (!status.isOk()) return status;
    SqlCommand outerBlock = query.nextBlock();
    SqlCommand viewBlock = query.nextBlock();
    if (outerBlock == null || viewBlock == null) {
      destination.reset();
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    outerBlock.copyQueryFrom(outer);
    viewBlock.copyQueryFrom(view);
    query.setSourceMetadata(outerDepth + viewDepth, explain, analyze);
    destination.reset();
    if (outerBlock.isSelectAll()) {
      status = outerBlock.expandSelectAllFrom(viewBlock);
    }
    return status.isOk() ? derived.compile(destination, true) : status;
  }

  private static StatusCode validate(
      SqlCommand outer,
      SqlCommand view,
      SqlCommand destination,
      int outerDepth,
      int viewDepth,
      boolean explain,
      boolean analyze) {
    if (destination == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (outer == null || view == null || destination == view) {
      destination.reset();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (outerDepth < 1
        || viewDepth < 1
        || analyze && !explain
        || outerDepth > SqlQuery.MAXIMUM_QUERY_BLOCKS - viewDepth) {
      destination.reset();
      return StatusCode.QUERY_TOO_COMPLEX;
    }
    return StatusCode.OK;
  }
}
