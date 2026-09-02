package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** One retained literal or outer-row supplier for a subquery index bound. */
final class SqlDescriptorSubqueryIndexBinding {
  private SqlDescriptorCorrelatedBindings bindings;
  private int leaf = -1;
  private boolean left;

  void set(SqlDescriptorCorrelatedBindings source, int encoded) {
    bindings = source;
    leaf = encoded >>> 1;
    left = (encoded & 1) != 0;
  }

  StatusCode assign(
      SqlDescriptorPrimaryValues target, int column, int descriptor,
      SqlDescriptorValueSource outer) {
    byte kind = bindings.kind(leaf, left);
    if (kind == SqlDescriptorCorrelatedBindings.OUTER) {
      int source = bindings.column(leaf, left);
      if (outer == null || outer.isNull(source)) return StatusCode.CONFLICT;
      return target.assign(column, bindings.descriptor(leaf, left), descriptor,
          outer.highValue(source), outer.value(source));
    }
    return kind == SqlDescriptorCorrelatedBindings.LITERAL
        ? target.assign(column, bindings.descriptor(leaf, left), descriptor,
            bindings.high(leaf, left), bindings.value(leaf, left))
        : StatusCode.CONFLICT;
  }

  boolean nullValue(SqlDescriptorValueSource outer) {
    byte kind = bindings.kind(leaf, left);
    return kind == SqlDescriptorCorrelatedBindings.NULL
        || kind == SqlDescriptorCorrelatedBindings.OUTER
            && (outer == null || outer.isNull(bindings.column(leaf, left)));
  }

  void reset() { bindings = null; leaf = -1; left = false; }
}
