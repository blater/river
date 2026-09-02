package io.riverdb.engine.schema.cache;

import io.riverdb.engine.schema.TableDescriptor;

/** One preallocated cache slot. */
final class SchemaCacheEntry {
  TableDescriptor descriptor;
  long tableId;
  long schemaId;
  long rowLayoutId;
  long catalogGeneration;
  long sequence;
  long reservedCharge;
  int pinCount;
  boolean occupied;
  boolean reserved;

  void clear() {
    descriptor = null;
    tableId = 0;
    schemaId = 0;
    rowLayoutId = 0;
    catalogGeneration = 0;
    sequence = 0;
    reservedCharge = 0;
    pinCount = 0;
    occupied = false;
    reserved = false;
  }
}
