package io.riverdb.engine.api;

/** Primitive permission mask attached to one authenticated engine session. */
public final class SessionPermissions {
  public static final int NONE = 0;
  public static final int READ = 1;
  public static final int WRITE = 1 << 1;
  public static final int SCHEMA = 1 << 2;
  public static final int ADMIN = 1 << 3;
  public static final int ALL = READ | WRITE | SCHEMA | ADMIN;

  private SessionPermissions() {}

  public static boolean valid(int permissions) {
    return (permissions & ~ALL) == 0;
  }
}
