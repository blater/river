package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.runtime.RiverRuntimeConfig;
import io.riverdb.engine.runtime.materialized.SqlMaterializedScratchFileCodec;

/** Selects the greatest jointly affordable resident-run and merge-page shape. */
final class SqlSortAdmission {
  private int pages;
  private long runPayloadBytes;

  StatusCode select(
      int configuredPages,
      int pageBytes,
      int projections,
      boolean textRows,
      boolean generatedTextRows,
      long availableBytes) {
    pages = 0;
    runPayloadBytes = 0;
    int minimum = RiverRuntimeConfig.MINIMUM_SORT_RUN_PAGES;
    if (configuredPages < minimum
        || pageBytes <= SqlMaterializedScratchFileCodec.PAGE_HEADER_BYTES
        || projections <= 0 || availableBytes < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!fits(
        minimum, pageBytes, projections, textRows, generatedTextRows, availableBytes)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int low = minimum;
    int high = configuredPages;
    while (low < high) {
      int candidate = low + (int) (((long) high - low + 1) / 2);
      if (fits(
          candidate, pageBytes, projections,
          textRows, generatedTextRows, availableBytes)) {
        low = candidate;
      } else {
        high = candidate - 1;
      }
    }
    pages = low;
    runPayloadBytes = payloadBytes(pageBytes, pages);
    return StatusCode.OK;
  }

  int pages() { return pages; }
  long runPayloadBytes() { return runPayloadBytes; }

  static long cleanRequiredBytes(
      int pages,
      int pageBytes,
      int projections,
      boolean textRows,
      boolean generatedTextRows) {
    long payload = payloadBytes(pageBytes, pages);
    if (payload == Long.MAX_VALUE) return Long.MAX_VALUE;
    long resident = SqlSortRunStorage.cleanRequiredBytes(
        projections, textRows, generatedTextRows, payload);
    long spill = SqlSortSpillStorage.cleanRequiredBytes(
        projections, textRows, generatedTextRows, pages);
    return SqlSortRunCapacity.add(resident, spill);
  }

  private static boolean fits(
      int pages,
      int pageBytes,
      int projections,
      boolean textRows,
      boolean generatedTextRows,
      long availableBytes) {
    return cleanRequiredBytes(
        pages, pageBytes, projections, textRows, generatedTextRows) <= availableBytes;
  }

  private static long payloadBytes(int pageBytes, int pages) {
    long payload = (long) pageBytes - SqlMaterializedScratchFileCodec.PAGE_HEADER_BYTES;
    return pages <= 0 || payload <= 0 || payload > Long.MAX_VALUE / pages
        ? Long.MAX_VALUE : payload * pages;
  }
}
