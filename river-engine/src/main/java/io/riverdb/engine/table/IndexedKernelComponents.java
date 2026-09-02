package io.riverdb.engine.table;

import io.riverdb.storage.heap.HeapInsertResult;

/** Constructs and retains the concrete services used by one indexed-table kernel. */
final class IndexedKernelComponents {
  final IndexedKernelRowAccess rows;
  final IndexedTableValidator validator;
  final IndexedMutationValidator mutationValidator;
  final IndexedTableVacuum vacuum;
  final IndexedEntryCounter entryCounter;
  final IndexedTableIndexTree indexTree;
  final IndexedTableMutationStager mutationStager;
  final IndexedKernelVisibility visibility;
  final IndexedKernelCapacity capacity;

  IndexedKernelComponents(
      IndexedTableKernel kernel,
      IndexedPageSet pages,
      IndexedVersionState versions,
      HeapInsertResult heapInsert) {
    rows = new IndexedKernelRowAccess(pages, versions);
    validator = new IndexedTableValidator(pages, versions);
    mutationValidator = new IndexedMutationValidator(pages);
    vacuum = new IndexedTableVacuum(kernel, pages, versions, heapInsert);
    entryCounter = new IndexedEntryCounter(pages);
    indexTree = new IndexedTableIndexTree(pages);
    mutationStager = new IndexedTableMutationStager(kernel, pages);
    visibility = new IndexedKernelVisibility(pages, versions, rows, indexTree);
    capacity = new IndexedKernelCapacity(pages);
  }
}
