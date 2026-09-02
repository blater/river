package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaAdmission;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.format.catalog.CatalogBuildIntent;
import io.riverdb.format.catalog.CatalogBuildIntentCodec;

/** Caller-owned catalog build awaiting its creating transaction's terminal outcome. */
public final class CatalogPreparedTable {
  private final SchemaAdmission admission = new SchemaAdmission();
  private TableDescriptor descriptor;
  private long objectId;
  private long schemaId;
  private long manifestRecordId;
  private long firstChildRecordId;
  private long predecessorSchemaId;
  private long predecessorGeneration;
  private long predecessorManifestRecordId;
  private long catalogBytes;
  private long firstKeyId;
  private int childCount;
  private int keyCount;
  private int physicalIndexCount;
  private int payloadBytes;
  private int buildKind;
  private boolean durableBuildKnown;
  private boolean cacheResolved;
  private boolean publicationStaged;

  public boolean isActive() { return descriptor != null; }

  public long objectId() { return isActive() ? objectId : 0; }

  /** Whether this private generation replaces an already-published object. */
  public boolean replacesPublished() {
    return isActive() && predecessorSchemaId > 0;
  }

  /** Borrows the unpublished descriptor for same-transaction statement execution. */
  public StatusCode borrow(SchemaPin pin) {
    return isActive() ? admission.borrow(pin) : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  /** Whether this active private build lent the exact unpublished descriptor pin. */
  public boolean authorizes(SchemaPin pin) {
    return isActive() && pin != null && pin.isActive() && !pin.isPublished()
        && pin.descriptor() == descriptor;
  }

  SchemaAdmission admission() { return admission; }

  TableDescriptor descriptor() { return descriptor; }

  void activate(
      TableDescriptor value, CatalogReservation reservation,
      int admittedPayloadBytes, long admittedCatalogBytes) {
    descriptor = value;
    objectId = value.tableId();
    schemaId = reservation.schemaId();
    manifestRecordId = reservation.manifestRecordId();
    firstChildRecordId = reservation.firstChildRecordId();
    predecessorSchemaId = reservation.predecessorSchemaId();
    predecessorGeneration = reservation.predecessorGeneration();
    predecessorManifestRecordId = reservation.predecessorManifestRecordId();
    childCount = reservation.childCount();
    payloadBytes = admittedPayloadBytes;
    catalogBytes = admittedCatalogBytes;
    firstKeyId = reservation.firstKeyId();
    buildKind = reservation.kind();
    keyCount = reservation.keyCount();
    physicalIndexCount = reservation.physicalIndexCount();
    durableBuildKnown = false;
    cacheResolved = false;
    publicationStaged = false;
  }

  void markDurableBuildKnown() { durableBuildKnown = true; }

  void forgetIntentCommitOutcome() { durableBuildKnown = false; }

  boolean durableBuildKnown() { return durableBuildKnown; }

  boolean cacheResolved() { return cacheResolved; }

  void markCacheResolved() { cacheResolved = true; }

  boolean publicationStaged() { return publicationStaged; }

  void markPublicationStaged() { publicationStaged = true; }

  void restoreReservation(CatalogReservation result) {
    if (buildKind == CatalogBuildIntentCodec.KIND_SUCCESSOR) {
      result.setSuccessor(
          objectId, schemaId, descriptor.rowLayoutId(), descriptor.catalogGeneration(),
          manifestRecordId, firstChildRecordId, childCount, firstKeyId, keyCount,
          predecessorSchemaId, predecessorGeneration, predecessorManifestRecordId);
    } else {
      result.setInitial(
          objectId, schemaId, descriptor.rowLayoutId(), descriptor.catalogGeneration(),
          manifestRecordId, firstChildRecordId, childCount, firstKeyId, keyCount);
    }
    result.setPhysicalIndexCount(physicalIndexCount);
    result.setNextPhysicalIndex(physicalIndexCount);
  }

  boolean matchesUnknownIntent(CatalogBuildIntent intent) {
    return isActive() && intent != null
        && intent.state() == CatalogBuildIntentCodec.STATE_BUILDING
        && intent.kind() == buildKind
        && intent.objectId() == objectId
        && intent.schemaId() == schemaId
        && intent.rowLayoutId() == descriptor.rowLayoutId()
        && intent.catalogGeneration() == descriptor.catalogGeneration()
        && intent.manifestRecordId() == manifestRecordId
        && intent.firstChildRecordId() == firstChildRecordId
        && intent.childCount() == childCount
        && intent.nextChild() == 0
        && intent.cleanupCursor() == 0
        && intent.payloadBytes() == payloadBytes
        && intent.catalogBytes() == catalogBytes
        && intent.firstKeyId() == firstKeyId
        && intent.keyCount() == keyCount
        && intent.physicalIndexCount() == physicalIndexCount
        && intent.nextPhysicalIndex() == 0
        && intent.indexCleanupCursor() == 0
        && intent.indexCleanupHorizon() == 0
        && intent.predecessorSchemaId() == predecessorSchemaId
        && intent.predecessorGeneration() == predecessorGeneration
        && intent.predecessorManifestRecordId() == predecessorManifestRecordId;
  }

  void clear() {
    descriptor = null;
    objectId = 0;
    schemaId = 0;
    manifestRecordId = 0;
    firstChildRecordId = 0;
    predecessorSchemaId = 0;
    predecessorGeneration = 0;
    predecessorManifestRecordId = 0;
    catalogBytes = 0;
    firstKeyId = 0;
    childCount = 0;
    keyCount = 0;
    physicalIndexCount = 0;
    payloadBytes = 0;
    buildKind = 0;
    durableBuildKnown = false;
    cacheResolved = false;
    publicationStaged = false;
  }
}
