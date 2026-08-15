package io.riverdb.engine.sql;

import io.riverdb.engine.api.ParameterSet;
import io.riverdb.sql.SqlParameterSource;

/** Session-owned adapter borrowing one engine-api parameter carrier during parse. */
final class SqlParameterSetSource implements SqlParameterSource {
  private ParameterSet parameters;

  void set(ParameterSet source) {
    parameters = source;
  }

  void reset() {
    parameters = null;
  }

  @Override
  public int count() {
    return parameters == null ? 0 : parameters.count();
  }

  @Override
  public boolean isNull(int index) {
    return parameters != null && parameters.isNull(index);
  }

  @Override
  public int typeDescriptorAt(int index) {
    return parameters == null ? 0 : parameters.typeDescriptorAt(index);
  }

  @Override
  public long valueAt(int index) {
    return parameters == null ? 0 : parameters.valueAt(index);
  }

  @Override
  public int copyTextAt(int index, char[] target, int offset) {
    return parameters == null ? -1 : parameters.copyTextAt(index, target, offset);
  }
}
