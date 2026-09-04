package io.riverdb.storage.btree;

/** Structural bounds shared by River B+trees and implied by the durable page-id domain. */
public final class BTreeStructuralLimits {
  /** The exclusive allocation successor must remain a positive signed durable page id. */
  public static final int MAXIMUM_PAGE_ID = Integer.MAX_VALUE - 1;

  /** Maximum levels in a unique, balanced tree whose internal pages have at least two children. */
  public static final int MAXIMUM_LEVELS = maximumLevelsForPageCount(MAXIMUM_PAGE_ID);
  public static final int MAXIMUM_INTERNAL_LEVELS = MAXIMUM_LEVELS - 1;

  private BTreeStructuralLimits() {
  }

  public static boolean canVisitLevel(int level) {
    return level >= 0 && level < MAXIMUM_LEVELS;
  }

  public static boolean canDescendFrom(int level) {
    return level >= 0 && level < MAXIMUM_INTERNAL_LEVELS;
  }

  public static boolean validPageId(int pageId) {
    return pageId > 0 && pageId <= MAXIMUM_PAGE_ID;
  }

  public static int maximumLevelsForPageCount(int maximumPages) {
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
