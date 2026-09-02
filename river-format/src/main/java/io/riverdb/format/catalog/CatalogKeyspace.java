package io.riverdb.format.catalog;

/** Physical catalog spaces; object and definition keys are their positive durable IDs. */
public final class CatalogKeyspace {
  public static final long FIRST_RELATIONAL_SPACE = 1L << 32;
  /** Globally unique key IDs map one-to-one onto dedicated tuple-index spaces. */
  public static final long FIRST_INDEX_SPACE = 1L << 62;
  /** Reserved high finite spaces cannot collide with relational table data/index namespaces. */
  public static final long INDEX_ROOT_SPACE = Long.MAX_VALUE - 4;
  public static final long SYSTEM_SPACE = Long.MAX_VALUE - 3;
  public static final long BUILD_INTENT_SPACE = Long.MAX_VALUE - 2;
  public static final long OBJECT_HEAD_SPACE = Long.MAX_VALUE - 1;
  public static final long DEFINITION_SPACE = Long.MAX_VALUE;
  /**
   * Shared relational object ceiling. Legacy table handles remain signed ints;
   * this still leaves every catalog-v2 object space far below the tuple-index region.
   */
  public static final long MAXIMUM_RELATIONAL_OBJECT_ID = Integer.MAX_VALUE;
  public static final long OBJECT_ID_EXHAUSTED = MAXIMUM_RELATIONAL_OBJECT_ID + 1;
  public static final long MAXIMUM_KEY_ID = INDEX_ROOT_SPACE - FIRST_INDEX_SPACE;
  public static final long KEY_ID_EXHAUSTED = MAXIMUM_KEY_ID + 1;
  public static final long ALLOCATION_WATERMARK_KEY = 1;
  public static final long VACUUM_PROGRESS_KEY = 2;

  private CatalogKeyspace() {
  }

  public static boolean validObjectHead(long objectId) {
    return objectId > 0 && objectId <= MAXIMUM_RELATIONAL_OBJECT_ID;
  }

  public static boolean validKeyId(long keyId) {
    return keyId > 0 && keyId <= MAXIMUM_KEY_ID;
  }

  public static boolean validDefinitionRange(long firstRecordId, int count) {
    return CatalogDefinitionManifestCodec.validRange(firstRecordId, count);
  }

  /** Single base-row space owned by one relational catalog object. */
  public static long relationalBaseRowSpace(long objectId) {
    return FIRST_RELATIONAL_SPACE + objectId;
  }

  /** Dedicated root/page space for one globally unique durable key identity. */
  public static long relationalIndexSpace(long keyId) {
    return FIRST_INDEX_SPACE + keyId - 1;
  }
}
