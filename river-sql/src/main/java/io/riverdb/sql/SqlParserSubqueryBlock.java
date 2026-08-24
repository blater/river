package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Validates and initializes the parser state for a bounded subquery block. */
final class SqlParserSubqueryBlock {
  private SqlParserSubqueryBlock() { }

  static StatusCode parse(
      SqlParser parser,
      CharSequence sql,
      int[] offsets,
      int[] kinds,
      int[] edges,
      int count,
      SqlCommand result) {
    if (offsets == null || kinds == null || edges == null || result == null
        || count < 0 || count > SqlBooleanPredicateProgram.MAXIMUM_LEAVES
        || count > offsets.length || count > kinds.length || count > edges.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    parser.beginSubqueries(offsets, kinds, edges, count);
    return parser.parseTextBlock(sql, result);
  }
}
