package io.riverdb.base.id;

/** A page address with an allocation generation, never a byte offset. */
public record PageId(TablespaceId tablespaceId, long pageNumber, int generation) {
  public static final PageId NONE = new PageId(TablespaceId.NONE, -1, 0);

  public PageId {
    boolean none = tablespaceId.equals(TablespaceId.NONE) && pageNumber == -1 && generation == 0;
    if (!none && (!tablespaceId.isValid() || pageNumber < 0 || generation <= 0)) {
      throw new IllegalArgumentException("page id requires tablespace, page number, and generation");
    }
  }

  public static PageId of(TablespaceId tablespaceId, long pageNumber, int generation) {
    return new PageId(tablespaceId, pageNumber, generation);
  }

  public boolean isValid() {
    return pageNumber >= 0;
  }
}
