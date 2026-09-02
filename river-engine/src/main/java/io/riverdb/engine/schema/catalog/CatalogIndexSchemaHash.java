package io.riverdb.engine.schema.catalog;

import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;

/** Stable identity for the primary and secondary tuple-index definitions. */
final class CatalogIndexSchemaHash {
  private static final long BASIS = 0xcbf29ce484222325L;
  private static final long PRIME = 0x100000001b3L;

  private CatalogIndexSchemaHash() {
  }

  static long value(TableDescriptor table) {
    if (table == null) return 0;
    int count = indexCount(table);
    if (count == 0) return 0;
    long hash = mix(BASIS, count);
    if (table.primaryKey() != null) hash = mix(hash, table.primaryKey());
    for (int index = 0; index < table.secondaryKeyCount(); index++) {
      hash = mix(hash, table.secondaryKeyAt(index));
    }
    return hash == 0 ? 1 : hash;
  }

  static int indexCount(TableDescriptor table) {
    return (table.primaryKey() == null ? 0 : 1) + table.secondaryKeyCount();
  }

  private static long mix(long hash, KeyDescriptor key) {
    hash = mix(mix(mix(hash, key.keyId()), key.kind()), key.isUnique() ? 1 : 0);
    hash = mix(hash, key.partCount());
    for (int part = 0; part < key.partCount(); part++) {
      hash = mix(mix(hash, key.columnOrdinalAt(part)), key.typeDescriptorAt(part));
    }
    hash = mix(hash, key.hasName() ? key.name().length() : 0);
    if (key.hasName()) {
      for (int index = 0; index < key.name().length(); index++) {
        hash = mix(hash, key.name().charAt(index));
      }
    }
    return hash;
  }

  private static long mix(long hash, long value) {
    for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
      hash = (hash ^ (value >>> shift & 0xff)) * PRIME;
    }
    return hash;
  }
}
