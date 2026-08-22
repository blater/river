package io.riverdb.format.catalog;

import io.riverdb.base.error.StatusCode;

/** Caller-owned atomic-generation validator for one decoded catalog header and its segments. */
public final class CatalogAssemblyValidator {
  private int headerKind;
  private int tableId;
  private long generation;
  private long firstSegmentKey;
  private int segmentCount;
  private int expectedBytes;
  private int acceptedBytes;
  private int acceptedMask;
  private boolean hasSchemaSegment;
  private boolean active;

  public StatusCode begin(CatalogHeader header) {
    reset();
    if (header == null
        || header.tableId() <= 0
        || header.generation() <= 0
        || header.segmentCount() <= 0
        || header.segmentCount() > CatalogHeaderCodec.MAXIMUM_SEGMENTS
        || header.payloadBytes() < header.segmentCount()
        || !CatalogContinuationKey.validRange(
            header.firstSegmentKey(), header.segmentCount())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    headerKind = header.kind();
    tableId = header.tableId();
    generation = header.generation();
    firstSegmentKey = header.firstSegmentKey();
    segmentCount = header.segmentCount();
    expectedBytes = header.payloadBytes();
    active = true;
    return StatusCode.OK;
  }

  public StatusCode accept(int space, long key, CatalogSegment segment) {
    if (!active) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (segment == null) {
      reset();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int ordinal = segment.ordinal();
    int bit = ordinal < 0 || ordinal >= Integer.SIZE ? 0 : 1 << ordinal;
    if (space != CatalogContinuationKey.SPACE
        || segment.tableId() != tableId
        || segment.generation() != generation
        || segment.segmentCount() != segmentCount
        || ordinal < 0
        || ordinal >= segmentCount
        || CatalogContinuationKey.at(firstSegmentKey, ordinal, segmentCount) != key
        || !validKind(headerKind, segment.kind())
        || segment.payloadBytes() <= 0
        || acceptedBytes > expectedBytes - segment.payloadBytes()
        || (acceptedMask & bit) != 0) {
      reset();
      return StatusCode.CORRUPTION;
    }
    acceptedMask |= bit;
    acceptedBytes += segment.payloadBytes();
    hasSchemaSegment |= segment.kind() == CatalogSegmentCodec.KIND_SCHEMA;
    return StatusCode.OK;
  }

  public boolean complete() {
    int expectedMask = segmentCount == Integer.SIZE ? -1 : (1 << segmentCount) - 1;
    return active
        && acceptedMask == expectedMask
        && acceptedBytes == expectedBytes
        && (headerKind != CatalogHeaderCodec.KIND_TABLE || hasSchemaSegment);
  }

  public void reset() {
    headerKind = 0;
    tableId = 0;
    generation = 0;
    firstSegmentKey = 0;
    segmentCount = 0;
    expectedBytes = 0;
    acceptedBytes = 0;
    acceptedMask = 0;
    hasSchemaSegment = false;
    active = false;
  }

  private static boolean validKind(int headerKind, int segmentKind) {
    return headerKind == CatalogHeaderCodec.KIND_TABLE
        ? segmentKind == CatalogSegmentCodec.KIND_SCHEMA
            || segmentKind == CatalogSegmentCodec.KIND_CONSTRAINT
        : headerKind == CatalogHeaderCodec.KIND_STATISTICS
            && segmentKind == CatalogSegmentCodec.KIND_STATISTICS;
  }
}
