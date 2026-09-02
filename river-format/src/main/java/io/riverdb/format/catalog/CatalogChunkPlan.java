package io.riverdb.format.catalog;

/** Caller-owned result of bounded catalog chunk planning. */
public final class CatalogChunkPlan {
  private int columnChunks;
  private int keyChunks;
  private int constraintChunks;
  private int expressionChunks;
  private int columnBytes;
  private int keyBytes;
  private int constraintBytes;
  private int expressionBytes;
  private int totalChunks;
  private int payloadBytes;

  void set(
      int columns, int keys, int constraints, int expressions,
      int columnsPayload, int keysPayload, int constraintsPayload, int expressionsPayload) {
    columnChunks = columns;
    keyChunks = keys;
    constraintChunks = constraints;
    expressionChunks = expressions;
    columnBytes = columnsPayload;
    keyBytes = keysPayload;
    constraintBytes = constraintsPayload;
    expressionBytes = expressionsPayload;
    totalChunks = columns + keys + constraints + expressions;
    payloadBytes = columnsPayload + keysPayload + constraintsPayload + expressionsPayload;
  }

  public void reset() { set(0, 0, 0, 0, 0, 0, 0, 0); }
  public int columnChunks() { return columnChunks; }
  public int keyChunks() { return keyChunks; }
  public int constraintChunks() { return constraintChunks; }
  public int expressionChunks() { return expressionChunks; }
  public int columnBytes() { return columnBytes; }
  public int keyBytes() { return keyBytes; }
  public int constraintBytes() { return constraintBytes; }
  public int expressionBytes() { return expressionBytes; }
  public int otherChunks() { return keyChunks + constraintChunks + expressionChunks; }
  public int totalChunks() { return totalChunks; }
  public int payloadBytes() { return payloadBytes; }
}
