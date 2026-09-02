package io.riverdb.format.catalog;

import io.riverdb.base.error.StatusCode;
import java.util.zip.CRC32C;

/** Caller-owned streaming validator for one manifest and its contiguous child range. */
public final class CatalogAssemblyValidator {
  private final CatalogAssemblyState state = new CatalogAssemblyState();

  public StatusCode begin(CatalogDefinitionManifest manifest, CRC32C checksum) {
    return state.begin(manifest, checksum);
  }

  public StatusCode accept(CatalogDefinitionRecord child) {
    return state.accept(child);
  }

  public boolean complete() {
    return state.complete();
  }

  public void reset() {
    state.reset();
  }
}
