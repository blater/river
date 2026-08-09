package io.riverdb.base.id;

/**
 * Semantic generation of one database incarnation's replica-local WAL address space.
 *
 * <p>A generation is meaningful only together with its {@link DatabaseIncarnation}. This Java
 * value shape is not a durable or wire encoding; ADR 0004 and K02 own that representation.
 */
public record WalGeneration(long value) {
  public static final WalGeneration NONE = new WalGeneration(0);

  public WalGeneration {
    IdBounds.nonNegative("WAL generation", value);
  }

  public static WalGeneration of(long value) {
    return new WalGeneration(IdBounds.positive("WAL generation", value));
  }

  public boolean isValid() {
    return value != 0;
  }
}
