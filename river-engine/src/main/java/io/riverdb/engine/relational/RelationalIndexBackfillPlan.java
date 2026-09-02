package io.riverdb.engine.relational;

/** Caller-owned durable identity admission for one streaming tuple-index backfill. */
public final class RelationalIndexBackfillPlan {
  private long tableId;
  private long schemaId;
  private long keyId;

  public void reset() {
    tableId = 0;
    schemaId = 0;
    keyId = 0;
  }

  void set(long table, long schema, long key) {
    tableId = table;
    schemaId = schema;
    keyId = key;
  }

  boolean matches(long table, long schema, long key) {
    return tableId == table && schemaId == schema && keyId == key;
  }
}
