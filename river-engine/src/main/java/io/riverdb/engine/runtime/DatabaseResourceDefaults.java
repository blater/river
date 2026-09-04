package io.riverdb.engine.runtime;

/** Structural addressability used when no explicit database resource plan is supplied. */
public final class DatabaseResourceDefaults {
  /** Transaction workspaces use int ordinals and allocate their storage lazily in chunks. */
  public static final int ADDRESSABLE_TRANSACTION_WRITE_ENTRIES = Integer.MAX_VALUE;

  private DatabaseResourceDefaults() {
  }
}
