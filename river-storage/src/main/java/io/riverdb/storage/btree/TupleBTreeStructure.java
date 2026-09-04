package io.riverdb.storage.btree;

/** Structural bounds implied by tuple-tree fanout and the durable page-id domain. */
public final class TupleBTreeStructure {
  /**
   * Page ids and their exclusive allocation successor share signed-int durable fields. The
   * successor must remain positive after a fresh allocation, so the highest allocatable page id is
   * one below the highest positive signed int.
   */
  public static final int MAXIMUM_PAGE_ID = Integer.MAX_VALUE - 1;

  /**
   * A valid internal page has at least two children, graph visits are unique, and all leaves have
   * equal depth. A tree with {@code levels} levels therefore needs at least
   * {@code 2^levels - 1} pages. Thirty levels need 1,073,741,823 pages; thirty-one would need
   * 2,147,483,647 pages, outside the allocatable page-id domain.
   */
  public static final int MAXIMUM_LEVELS = maximumLevelsForPageCount(MAXIMUM_PAGE_ID);
  public static final int MAXIMUM_INTERNAL_LEVELS = MAXIMUM_LEVELS - 1;

  private TupleBTreeStructure() {
  }

  static boolean canVisitLevel(int level) {
    return level >= 0 && level < MAXIMUM_LEVELS;
  }

  static boolean canDescendFrom(int level) {
    return level >= 0 && level < MAXIMUM_INTERNAL_LEVELS;
  }

  static boolean validPageId(int pageId) {
    return pageId > 0 && pageId <= MAXIMUM_PAGE_ID;
  }

  static int maximumLevelsForPageCount(int maximumPages) {
    if (maximumPages <= 0) return 0;
    long totalPages = 1;
    long pagesAtLevel = 1;
    int levels = 1;
    while (pagesAtLevel <= ((long) maximumPages - totalPages) / 2) {
      pagesAtLevel *= 2;
      totalPages += pagesAtLevel;
      levels++;
    }
    return levels;
  }
}
