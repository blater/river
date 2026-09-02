package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleKeyCodec;
import java.nio.ByteBuffer;

/** Allocation-free whole B-tree over provider-owned physical tuple-key pages. */
public final class TupleBTree {
  private TupleBTreePageProvider provider;
  private long schemaId;
  private TupleShape shape;

  public TupleBTree(TupleBTreePageProvider pageProvider, long keySchemaId, TupleShape keyShape) {
    configure(pageProvider, keySchemaId, keyShape);
  }

  /** Rebinds this stateless tree facade after validating the complete next identity. */
  public StatusCode configure(
      TupleBTreePageProvider pageProvider, long keySchemaId, TupleShape keyShape) {
    if (pageProvider == null || keySchemaId <= 0 || keyShape == null
        || keyShape.partCount() <= 0 || keyShape.partCount() > SqlShapeLimits.MAX_KEY_PARTS) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (keyShape.maximumEncodedBytes() > TupleKeyCodec.MAX_INDEX_USER_KEY_BYTES
        || keyShape.maximumPhysicalEncodedBytes() <= 0
        || keyShape.maximumPhysicalEncodedBytes() > TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    schemaId = keySchemaId;
    shape = keyShape;
    provider = pageProvider;
    return StatusCode.OK;
  }

  public StatusCode initialize(TupleBTreeTreeWorkspace workspace) {
    return TupleBTreeInitialization.initialize(this, workspace);
  }

  public StatusCode lookupExact(
      ByteBuffer key, int keyOffset, int keyLength,
      TupleBTreeTreeWorkspace workspace, TupleBTreeLookupResult result) {
    return TupleBTreeLookup.lookup(this, key, keyOffset, keyLength, workspace, result);
  }

  public StatusCode insert(
      ByteBuffer key, int keyOffset, int keyLength, TupleBTreeTreeWorkspace workspace) {
    return TupleBTreeMutation.insert(this, key, keyOffset, keyLength, workspace);
  }

  public StatusCode preflightInsert(
      ByteBuffer key, int keyOffset, int keyLength,
      TupleBTreeTreeWorkspace workspace, TupleBTreeInsertPreflightResult result) {
    return TupleBTreeInsertPreflight.plan(
        this, key, keyOffset, keyLength, workspace, result);
  }

  public StatusCode delete(
      ByteBuffer key, int keyOffset, int keyLength, TupleBTreeTreeWorkspace workspace) {
    return TupleBTreeMutation.delete(this, key, keyOffset, keyLength, workspace);
  }

  public StatusCode validate(
      TupleBTreeTreeWorkspace workspace, TupleBTreeValidationResult result) {
    return TupleBTreeGraphValidation.validate(this, workspace, result);
  }

  boolean isValid(TupleBTreeTreeWorkspace workspace) {
    return provider != null && workspace != null && workspace.isValid();
  }

  TupleBTreePageProvider provider() { return provider; }
  long schemaId() { return schemaId; }
  TupleShape shape() { return shape; }
}
