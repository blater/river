package io.riverdb.engine.api;

/** Stable phase identities attached to canonical session authorization events. */
public final class SessionAuthorizationPhase {
  public static final int EXECUTE = 1;
  public static final int QUERY = 2;
  public static final int PREPARE = 3;
  public static final int EXECUTE_PREPARED = 4;
  public static final int QUERY_PREPARED = 5;
  public static final int PROGRAM_STEP = 6;

  private SessionAuthorizationPhase() { }

  public static boolean valid(int phase) {
    return phase >= EXECUTE && phase <= PROGRAM_STEP;
  }
}
