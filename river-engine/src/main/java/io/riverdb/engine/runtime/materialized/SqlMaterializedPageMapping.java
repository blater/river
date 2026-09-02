package io.riverdb.engine.runtime.materialized;

import io.riverdb.base.error.StatusCode;

/** Checked conversion between an unpaged logical stream and physical scratch pages. */
public final class SqlMaterializedPageMapping {
  public static final int FILE_HEADER_BYTES = 64;
  public static final int PAGE_HEADER_BYTES = 32;

  private SqlMaterializedPageMapping() {}

  public static StatusCode map(
      long logicalOffset, int pageBytes, SqlMaterializedPageLocation target) {
    if (logicalOffset < 0 || pageBytes <= PAGE_HEADER_BYTES || target == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int payloadBytes = pageBytes - PAGE_HEADER_BYTES;
    long pageNumber = logicalOffset / payloadBytes;
    int payloadOffset = (int) (logicalOffset % payloadBytes);
    long maximumPage = (Long.MAX_VALUE - FILE_HEADER_BYTES - pageBytes) / pageBytes;
    if (pageNumber > maximumPage) return StatusCode.RESOURCE_EXHAUSTED;
    long filePosition = FILE_HEADER_BYTES + pageNumber * pageBytes;
    target.set(
        pageNumber, filePosition, PAGE_HEADER_BYTES + payloadOffset,
        payloadBytes - payloadOffset);
    return StatusCode.OK;
  }

  public static StatusCode physicalPosition(
      long pageNumber, int pageBytes, SqlMaterializedPageLocation target) {
    if (pageNumber < 0 || pageBytes <= PAGE_HEADER_BYTES || target == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long maximumPage = (Long.MAX_VALUE - FILE_HEADER_BYTES - pageBytes) / pageBytes;
    if (pageNumber > maximumPage) return StatusCode.RESOURCE_EXHAUSTED;
    target.set(pageNumber, FILE_HEADER_BYTES + pageNumber * pageBytes, 0, pageBytes);
    return StatusCode.OK;
  }
}
