package io.riverdb.engine.schema;

/** Finds a physical index whose leading key parts support a local foreign key. */
public final class ForeignKeySupport {
  private ForeignKeySupport() { }

  public static KeyDescriptor find(TableDescriptor table, KeyDescriptor foreign) {
    KeyDescriptor best = supports(table.primaryKey(), foreign) ? table.primaryKey() : null;
    for (int index = 0; index < table.secondaryKeyCount(); index++) {
      KeyDescriptor candidate = table.secondaryKeyAt(index);
      if (supports(candidate, foreign)
          && (best == null || candidate.partCount() < best.partCount())) best = candidate;
    }
    return best;
  }

  public static KeyDescriptor find(
      KeyDescriptor primary, KeyDescriptor[] secondary, int secondaryCount,
      KeyDescriptor foreign) {
    KeyDescriptor best = supports(primary, foreign) ? primary : null;
    for (int index = 0; index < secondaryCount; index++) {
      KeyDescriptor candidate = secondary[index];
      if (supports(candidate, foreign)
          && (best == null || candidate.partCount() < best.partCount())) best = candidate;
    }
    return best;
  }

  public static boolean supports(KeyDescriptor candidate, KeyDescriptor foreign) {
    if (candidate == null || candidate.partCount() < foreign.partCount()) return false;
    for (int part = 0; part < foreign.partCount(); part++) {
      if (candidate.columnOrdinalAt(part) != foreign.columnOrdinalAt(part)
          || candidate.typeDescriptorAt(part) != foreign.typeDescriptorAt(part)) return false;
    }
    return true;
  }
}
