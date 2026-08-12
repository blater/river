package io.riverdb.base.id;

/** Identity of a complete immutable checkpoint/snapshot manifest. */
public record CheckpointManifestId(
    DatabaseIncarnation incarnation,
    JournalPosition coveredPosition,
    long formatGeneration,
    long digestHigh,
    long digestLow) {
  public static final CheckpointManifestId NONE = new CheckpointManifestId(
      DatabaseIncarnation.NONE,
      JournalPosition.NONE,
      0,
      0,
      0);

  public CheckpointManifestId {
    boolean none = incarnation.equals(DatabaseIncarnation.NONE)
        && coveredPosition.equals(JournalPosition.NONE)
        && formatGeneration == 0
        && digestHigh == 0
        && digestLow == 0;
    boolean sameHistory = incarnation.equals(coveredPosition.incarnation());
    if (!none && (!incarnation.isValid()
        || !coveredPosition.isValid()
        || !sameHistory
        || formatGeneration <= 0
        || (digestHigh == 0 && digestLow == 0))) {
      throw new IllegalArgumentException(
          "manifest id requires one history, covered position, format generation, and digest");
    }
  }

  public static CheckpointManifestId of(
      DatabaseIncarnation incarnation,
      JournalPosition coveredPosition,
      long formatGeneration,
      long digestHigh,
      long digestLow) {
    return new CheckpointManifestId(
        incarnation,
        coveredPosition,
        formatGeneration,
        digestHigh,
        digestLow);
  }

  public boolean isValid() {
    return formatGeneration != 0;
  }
}
