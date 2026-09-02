package io.riverdb.format.catalog;

final class CatalogBuildIntentLayout {
  static final long MAGIC = 0x5249564341544249L;
  static final int CHECKSUM_OFFSET = 152;
  static final int COMPLEMENT_OFFSET = 156;

  private CatalogBuildIntentLayout() { }
}
