package io.riverdb.base.id;

/** A stable heap-row address whose generations reject stale page and slot references. */
public record RowId(PageId pageId, int slot, int slotGeneration) {
  public static final RowId NONE = new RowId(PageId.NONE, -1, 0);

  public RowId {
    boolean none = pageId.equals(PageId.NONE) && slot == -1 && slotGeneration == 0;
    if (!none && (!pageId.isValid() || slot < 0 || slotGeneration <= 0)) {
      throw new IllegalArgumentException("row id requires page, slot, and slot generation");
    }
  }

  public static RowId of(PageId pageId, int slot, int slotGeneration) {
    return new RowId(pageId, slot, slotGeneration);
  }

  public boolean isValid() {
    return slot >= 0;
  }
}
