package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.format.catalog.CatalogDefinitionManifest;
import io.riverdb.format.catalog.CatalogObjectHead;
import java.nio.ByteBuffer;

/** Private-build decoder that publishes a table descriptor only after complete validation. */
public final class CatalogTableAssemblyBuilder {
  private final CatalogTableAssemblyState state = new CatalogTableAssemblyState();

  public StatusCode begin(
      long expectedObjectHeadKey,
      CatalogObjectHead head,
      CatalogDefinitionManifest manifest) {
    return state.begin(expectedObjectHeadKey, head, manifest);
  }

  public StatusCode accept(
      ByteBuffer encodedRecord, int start, int recordBytes) {
    return state.accept(encodedRecord, start, recordBytes);
  }

  public StatusCode finish(TableDescriptor.Result result, StatusDetail detail) {
    return state.finish(result, detail);
  }

  public void reset() {
    state.reset();
  }
}
