package io.riverdb.sql;

import java.util.Arrays;

/** Unpublished complete replacement for every ordinal-indexed command column array. */
final class SqlCommandColumnGrowth {
  private final SqlIdentifier[] names;
  private final SqlIdentifier[] tables;
  private final SqlIdentifier[] aliases;
  SqlIdentifier[] referenceTables;
  SqlIdentifier[] referenceColumns;
  private final long[] updates;
  private final long[] updateHighs;
  long[] defaults;
  long[] defaultHighs;
  byte[] defaultKinds;
  int[] types;
  long[] checkValues;
  long[] checkHighs;
  int[] checkTypes;
  SqlComparison[] checks;
  private final boolean[] nullUpdates;
  private final boolean[] defaultUpdates;
  private final int[] updateTypes;
  private final int[] updateOperators;
  private final boolean[] nullProjections;
  boolean[] notNull;
  boolean[] hasDefault;
  boolean[] unique;
  boolean[] references;

  SqlCommandColumnGrowth(SqlCommand source, int capacity) {
    names = identifiers(source.columnNames, capacity);
    tables = identifiers(source.columnTableNames, capacity);
    aliases = identifiers(source.columnAliases, capacity);
    source.columnConstraints.prepareGrowth(this, capacity);
    updates = Arrays.copyOf(source.updateValues, capacity);
    updateHighs = Arrays.copyOf(source.updateHighs, capacity);
    nullUpdates = Arrays.copyOf(source.nullUpdates, capacity);
    defaultUpdates = Arrays.copyOf(source.defaultUpdates, capacity);
    updateTypes = Arrays.copyOf(source.updateTypeDescriptors, capacity);
    updateOperators = Arrays.copyOf(source.updateOperators, capacity);
    nullProjections = Arrays.copyOf(source.nullProjections, capacity);
  }

  void publish(SqlCommand target) {
    target.columnNames = names;
    target.columnTableNames = tables;
    target.columnAliases = aliases;
    target.columnConstraints.publishGrowth(this);
    target.updateValues = updates;
    target.updateHighs = updateHighs;
    target.nullUpdates = nullUpdates;
    target.defaultUpdates = defaultUpdates;
    target.updateTypeDescriptors = updateTypes;
    target.updateOperators = updateOperators;
    target.nullProjections = nullProjections;
  }

  private static SqlIdentifier[] identifiers(SqlIdentifier[] source, int capacity) {
    SqlIdentifier[] grown = Arrays.copyOf(source, capacity);
    for (int index = source.length; index < capacity; index++) grown[index] = new SqlIdentifier();
    return grown;
  }
}
