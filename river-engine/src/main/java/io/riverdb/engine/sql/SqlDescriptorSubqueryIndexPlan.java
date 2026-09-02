package io.riverdb.engine.sql;

import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;

/** Retains one shared-selector decision and its child bound suppliers. */
final class SqlDescriptorSubqueryIndexPlan {
  private final SqlDescriptorIndexChoice choice = new SqlDescriptorIndexChoice();
  private final SqlDescriptorSubqueryIndexCandidates candidates =
      new SqlDescriptorSubqueryIndexCandidates();
  private final SqlDescriptorSubqueryIndexBinding[] equal =
      new SqlDescriptorSubqueryIndexBinding[KeyDescriptor.MAXIMUM_PARTS];
  private final SqlDescriptorSubqueryIndexBinding lower =
      new SqlDescriptorSubqueryIndexBinding();
  private final SqlDescriptorSubqueryIndexBinding upper =
      new SqlDescriptorSubqueryIndexBinding();
  private SqlComparison lowerComparison;
  private SqlComparison upperComparison;

  SqlDescriptorSubqueryIndexPlan() {
    for (int part = 0; part < equal.length; part++) {
      equal[part] = new SqlDescriptorSubqueryIndexBinding();
    }
  }

  void prepare(
      SqlCommand command, TableDescriptor table,
      SqlDescriptorCorrelatedBindings bindings) {
    reset();
    candidates.prepare(command.wherePredicates(), bindings);
    SqlDescriptorIndexSelection.choose(table, candidates, 0, null, null, choice);
    for (int part = 0; part < choice.equalityParts; part++) equal[part].set(
        bindings, candidates.find(choice.key.columnOrdinalAt(part), SqlComparison.EQUAL));
    if (choice.lowerLeaf >= 0) {
      lower.set(bindings, choice.lowerLeaf);
      lowerComparison = candidates.comparison(choice.lowerLeaf);
    }
    if (choice.upperLeaf >= 0) {
      upper.set(bindings, choice.upperLeaf);
      upperComparison = candidates.comparison(choice.upperLeaf);
    }
  }

  boolean active() { return choice.key != null; }
  KeyDescriptor key() { return choice.key; }
  int equalParts() { return choice.equalityParts; }
  SqlDescriptorSubqueryIndexBinding equal(int part) { return equal[part]; }
  SqlDescriptorSubqueryIndexBinding lower() { return lower; }
  SqlDescriptorSubqueryIndexBinding upper() { return upper; }
  SqlComparison lowerComparison() { return lowerComparison; }
  SqlComparison upperComparison() { return upperComparison; }

  private void reset() {
    for (SqlDescriptorSubqueryIndexBinding binding : equal) binding.reset();
    lower.reset();
    upper.reset();
    choice.reset();
    lowerComparison = null;
    upperComparison = null;
  }
}
