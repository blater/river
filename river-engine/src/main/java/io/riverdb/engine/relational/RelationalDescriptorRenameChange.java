package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.TableDescriptor;

/** Facade for ordinal-preserving catalog-v2 metadata rename successors. */
public final class RelationalDescriptorRenameChange {
  private final RelationalDescriptorColumnRenameChange columns =
      new RelationalDescriptorColumnRenameChange();
  private final RelationalDescriptorIndexRenameChange indexes =
      new RelationalDescriptorIndexRenameChange();

  public StatusCode column(
      TableDescriptor current,
      CharSequence currentName,
      CharSequence renamedName,
      TableDescriptor.Result result,
      StatusDetail detail) {
    return columns.build(current, currentName, renamedName, result, detail);
  }

  public StatusCode index(
      TableDescriptor current,
      CharSequence currentName,
      CharSequence renamedName,
      TableDescriptor.Result result,
      StatusDetail detail) {
    return indexes.build(current, currentName, renamedName, result, detail);
  }
}
