package io.riverdb.engine.table;

import io.riverdb.wal.local.LocalWalReadResult;

/** Classifies explicitly supported indexed-table WAL formats. */
final class IndexedWalRecordKinds {
  static final int INVALID = -1;
  static final int OTHER = 0;
  static final int INDEXED = 1;
  static final int RELATIONAL = 2;

  private IndexedWalRecordKinds() { }

  static int classify(LocalWalReadResult record) {
    int format = record.header().formatId();
    int version = record.header().formatVersion();
    if (format == IndexedTableStore.WAL_FORMAT_ID) {
      return version == IndexedTableStore.WAL_FORMAT_VERSION ? INDEXED : INVALID;
    }
    if (format == IndexedRelationalWalCodec.WAL_FORMAT_ID) {
      return version == IndexedRelationalWalCodec.WAL_FORMAT_VERSION ? RELATIONAL : INVALID;
    }
    return OTHER;
  }
}
