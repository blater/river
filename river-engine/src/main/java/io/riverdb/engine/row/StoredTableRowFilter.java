package io.riverdb.engine.row;

import io.riverdb.engine.schema.TableDescriptor;
import java.nio.ByteBuffer;

/** Tests a validated borrowed row before its values are published. */
public interface StoredTableRowFilter {
  boolean matches(TableDescriptor table, ByteBuffer source, int start);
}
