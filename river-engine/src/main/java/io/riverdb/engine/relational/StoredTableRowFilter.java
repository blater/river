package io.riverdb.engine.relational;

import io.riverdb.engine.schema.TableDescriptor;
import java.nio.ByteBuffer;

/** Tests a bounded fixed row prefix before variable fields are inspected. */
public interface StoredTableRowFilter {
  boolean matches(TableDescriptor table, ByteBuffer source, int start);
}
