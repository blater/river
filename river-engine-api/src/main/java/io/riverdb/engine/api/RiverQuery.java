package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/**
 * Single-owner streaming query capability. An OK unavailable row is end of stream.
 */
public interface RiverQuery {
  StatusCode next(RowResult result);

  StatusCode close(CommandResult result);

  boolean isActive();

  int columnCount();

  CharSequence columnName(int index);

  default boolean columnIsVarchar(int index) {
    return SqlTypeDescriptor.typeId(columnTypeDescriptor(index))
        == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }

  int columnTypeDescriptor(int index);

  boolean columnIsNullable(int index);

  long rowsReturned();
}
