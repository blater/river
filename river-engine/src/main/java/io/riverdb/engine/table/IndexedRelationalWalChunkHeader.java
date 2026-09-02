package io.riverdb.engine.table;

import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;

/** Reusable decoded header for one grouped relational WAL chunk. */
final class IndexedRelationalWalChunkHeader {
  long transactionId;
  long operationId;
  long totalStreamBytes;
  long priorDigest;
  long resultingDigest;
  long wholeDigest;
  long payloadBytes;
  int ordinal;
  int chunkCount;
  int firstItem;
  int chunkItems;
  int totalItems;
  int descriptors;
  int suboperations;
  int mutations;
  int logicalRowFloors;
  int streamBytes;

  boolean readAndValidate(
      ByteBuffer payload, int start, long walTransactionId, int decisionCode) {
    transactionId = FormatBytes.getLong(payload, start + 16);
    operationId = FormatBytes.getLong(payload, start + 24);
    ordinal = FormatBytes.getInt(payload, start + 32);
    chunkCount = FormatBytes.getInt(payload, start + 36);
    firstItem = FormatBytes.getInt(payload, start + 40);
    chunkItems = FormatBytes.getInt(payload, start + 44);
    totalItems = FormatBytes.getInt(payload, start + 48);
    descriptors = FormatBytes.getInt(payload, start + 52);
    suboperations = FormatBytes.getInt(payload, start + 56);
    mutations = FormatBytes.getInt(payload, start + 60);
    totalStreamBytes = FormatBytes.getLong(payload, start + 64);
    streamBytes = FormatBytes.getInt(payload, start + 72);
    logicalRowFloors = FormatBytes.getInt(payload, start + 76);
    priorDigest = FormatBytes.getLong(payload, start + 80);
    resultingDigest = FormatBytes.getLong(payload, start + 88);
    wholeDigest = FormatBytes.getLong(payload, start + 96);
    payloadBytes = FormatBytes.getLong(payload, start + 104);
    return IndexedRelationalWalValidation.validHeader(
        payload, start, walTransactionId, transactionId, operationId,
        ordinal, chunkCount, firstItem, chunkItems, totalItems,
        descriptors, logicalRowFloors, suboperations, mutations, totalStreamBytes,
        streamBytes, payloadBytes, decisionCode);
  }
}
