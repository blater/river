package io.riverdb.engine.sql;

/** In-place heap ordering over one reusable sort workspace. */
final class SqlSortHeap {
  void sort(SqlSortWorkspace workspace, int rows) {
    for (int root = rows / 2 - 1; root >= 0; root--) sift(workspace, root, rows);
    for (int end = rows - 1; end > 0; end--) {
      workspace.swapRows(0, end);
      sift(workspace, 0, end);
    }
  }

  private void sift(SqlSortWorkspace workspace, int root, int length) {
    int current = root;
    while (current * 2 + 1 < length) {
      int child = current * 2 + 1;
      if (child + 1 < length && workspace.compareRows(child, child + 1) < 0) child++;
      if (workspace.compareRows(current, child) >= 0) return;
      workspace.swapRows(current, child);
      current = child;
    }
  }
}
