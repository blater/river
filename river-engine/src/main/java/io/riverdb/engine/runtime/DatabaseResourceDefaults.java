package io.riverdb.engine.runtime;

/** Shared ungoverned capacities; governed databases replace them with an explicit plan. */
public final class DatabaseResourceDefaults {
  /** Addressable ceiling shared by transaction workspaces and store publication scratch. */
  public static final int MAXIMUM_TRANSACTION_WRITE_ENTRIES = 1_048_576;
  public static final int TRANSACTION_WRITE_ENTRIES = 384;

  private DatabaseResourceDefaults() {
  }
}
