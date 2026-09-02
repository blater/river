package io.riverdb.sql;

import java.util.Arrays;

/** Unpublished complete replacement for every ordinal-indexed command column array. */
final class SqlCommandColumnGrowth {
  private final SqlIdentifier[] names;
  private final SqlIdentifier[] tables;
  private final SqlIdentifier[] aliases;
  private final SqlIdentifier[] referenceTables;
  private final SqlIdentifier[] referenceColumns;
  private final long[] updates;
  private final long[] updateHighs;
  private final long[] defaults;
  private final long[] defaultHighs;
  private final byte[] defaultKinds;
  private final int[] types;
  private final long[] checkValues;
  private final long[] checkHighs;
  private final int[] checkTypes;
  private final SqlComparison[] checks;
  private final boolean[] nullUpdates;
  private final boolean[] defaultUpdates;
  private final int[] updateTypes;
  private final int[] updateOperators;
  private final boolean[] nullProjections;
  private final boolean[] notNull;
  private final boolean[] hasDefault;
  private final boolean[] unique;
  private final boolean[] references;

  SqlCommandColumnGrowth(SqlCommand source, int capacity) {
    names = identifiers(source.columnNames, capacity);
    tables = identifiers(source.columnTableNames, capacity);
    aliases = identifiers(source.columnAliases, capacity);
    referenceTables = identifiers(source.columnReferenceTableNames, capacity);
    referenceColumns = identifiers(source.columnReferenceColumnNames, capacity);
    updates = Arrays.copyOf(source.updateValues, capacity);
    updateHighs = Arrays.copyOf(source.updateHighs, capacity);
    defaults = Arrays.copyOf(source.columnDefaultValues, capacity);
    defaultHighs = Arrays.copyOf(source.columnDefaultHighs, capacity);
    defaultKinds = Arrays.copyOf(source.columnDefaultKinds, capacity);
    types = Arrays.copyOf(source.columnTypeDescriptors, capacity);
    checkValues = Arrays.copyOf(source.columnCheckValues, capacity);
    checkHighs = Arrays.copyOf(source.columnCheckHighs, capacity);
    checkTypes = Arrays.copyOf(source.columnCheckTypeDescriptors, capacity);
    checks = Arrays.copyOf(source.columnCheckComparisons, capacity);
    nullUpdates = Arrays.copyOf(source.nullUpdates, capacity);
    defaultUpdates = Arrays.copyOf(source.defaultUpdates, capacity);
    updateTypes = Arrays.copyOf(source.updateTypeDescriptors, capacity);
    updateOperators = Arrays.copyOf(source.updateOperators, capacity);
    nullProjections = Arrays.copyOf(source.nullProjections, capacity);
    notNull = Arrays.copyOf(source.columnNotNull, capacity);
    hasDefault = Arrays.copyOf(source.columnDefaults, capacity);
    unique = Arrays.copyOf(source.columnUnique, capacity);
    references = Arrays.copyOf(source.columnReferences, capacity);
  }

  void publish(SqlCommand target) {
    target.columnNames = names;
    target.columnTableNames = tables;
    target.columnAliases = aliases;
    target.columnReferenceTableNames = referenceTables;
    target.columnReferenceColumnNames = referenceColumns;
    target.updateValues = updates;
    target.updateHighs = updateHighs;
    target.columnDefaultValues = defaults;
    target.columnDefaultHighs = defaultHighs;
    target.columnDefaultKinds = defaultKinds;
    target.columnTypeDescriptors = types;
    target.columnCheckValues = checkValues;
    target.columnCheckHighs = checkHighs;
    target.columnCheckTypeDescriptors = checkTypes;
    target.columnCheckComparisons = checks;
    target.nullUpdates = nullUpdates;
    target.defaultUpdates = defaultUpdates;
    target.updateTypeDescriptors = updateTypes;
    target.updateOperators = updateOperators;
    target.nullProjections = nullProjections;
    target.columnNotNull = notNull;
    target.columnDefaults = hasDefault;
    target.columnUnique = unique;
    target.columnReferences = references;
  }

  private static SqlIdentifier[] identifiers(SqlIdentifier[] source, int capacity) {
    SqlIdentifier[] grown = Arrays.copyOf(source, capacity);
    for (int index = source.length; index < capacity; index++) grown[index] = new SqlIdentifier();
    return grown;
  }
}
