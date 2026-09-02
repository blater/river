package io.riverdb.engine.relational;

import java.nio.ByteBuffer;

/** Writes the invariant column identity fields shared by catalog schema sources. */
final class CatalogTableColumnFields {
  private CatalogTableColumnFields() { }

  static void write(
      ByteBuffer target, CharSequence name, int descriptor, int flags) {
    target.putInt(name.length());
    for (int index = 0; index < name.length(); index++) {
      target.put((byte) name.charAt(index));
    }
    target.putInt(descriptor);
    target.put((byte) flags);
  }
}
