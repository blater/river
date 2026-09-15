package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Budget-sized FIFO ownership for published cohorts awaiting their exact WAL force target. */
final class IndexedDurabilityCohortRing {
  private final IndexedGroupCommitRequest[] requests;
  private final long[] memberCommitSequences;
  private final long[] memberCommittedRows;
  private final long[] cohortTokens;
  private final long[] requiredWalEnds;
  private final int[] cohortMemberStarts;
  private final int[] cohortMemberCounts;
  private final int[] cohortFrameHeads;
  private int memberHead;
  private int memberTail;
  private int memberCount;
  private int cohortHead;
  private int cohortTail;
  private int cohortCount;
  private long nextToken = 1;

  IndexedDurabilityCohortRing(int capacity) {
    requests = new IndexedGroupCommitRequest[capacity];
    memberCommitSequences = new long[capacity];
    memberCommittedRows = new long[capacity];
    cohortTokens = new long[capacity];
    requiredWalEnds = new long[capacity];
    cohortMemberStarts = new int[capacity];
    cohortMemberCounts = new int[capacity];
    cohortFrameHeads = new int[capacity];
    java.util.Arrays.fill(cohortFrameHeads, -1);
  }

  boolean empty() { return cohortCount == 0; }
  int memberCount() { return memberCount; }
  int remainingMembers() { return requests.length - memberCount; }
  boolean canRetain(int members) {
    return nextToken != 0 && members > 0 && members <= remainingMembers()
        && cohortCount < cohortTokens.length;
  }

  StatusCode admissionStatus(int members) {
    return canRetain(members) ? StatusCode.OK : StatusCode.RESOURCE_EXHAUSTED;
  }

  long nextToken() {
    return nextToken;
  }

  StatusCode add(
      IndexedGroupCommitRequest[] sourceRequests,
      long[] sourceCommitSequences,
      long[] sourceCommittedRows,
      int count,
      long token,
      long requiredWalEnd,
      int frameHead) {
    if (sourceRequests == null || sourceCommitSequences == null || sourceCommittedRows == null
        || count <= 0 || count > sourceRequests.length
        || count > sourceCommitSequences.length || count > sourceCommittedRows.length
        || token <= 0 || token != nextToken || requiredWalEnd <= 0 || frameHead < -1
        || !canRetain(count)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int start = memberTail;
    for (int index = 0; index < count; index++) {
      IndexedGroupCommitRequest request = sourceRequests[index];
      if (request == null || sourceCommitSequences[index] <= 0 || sourceCommittedRows[index] < 0) {
        return StatusCode.INVARIANT_BROKEN;
      }
    }
    for (int index = 0; index < count; index++) {
      requests[memberTail] = sourceRequests[index];
      memberCommitSequences[memberTail] = sourceCommitSequences[index];
      memberCommittedRows[memberTail] = sourceCommittedRows[index];
      memberTail = increment(memberTail);
    }
    memberCount += count;
    cohortTokens[cohortTail] = token;
    requiredWalEnds[cohortTail] = requiredWalEnd;
    cohortMemberStarts[cohortTail] = start;
    cohortMemberCounts[cohortTail] = count;
    cohortFrameHeads[cohortTail] = frameHead;
    cohortTail = increment(cohortTail);
    cohortCount++;
    nextToken = token == Long.MAX_VALUE ? 0 : token + 1;
    return StatusCode.OK;
  }

  long requiredWalEnd() { return empty() ? 0 : requiredWalEnds[cohortHead]; }
  long token() { return empty() ? 0 : cohortTokens[cohortHead]; }
  int frameHead() { return empty() ? -1 : cohortFrameHeads[cohortHead]; }
  int headMemberCount() { return empty() ? 0 : cohortMemberCounts[cohortHead]; }
  long firstCommitSequence() {
    return empty() ? 0 : memberCommitSequences[cohortMemberStarts[cohortHead]];
  }

  StatusCode loadHead(IndexedGroupCommitBatch batch) {
    if (batch == null || empty()) return StatusCode.CONFLICT;
    int count = cohortMemberCounts[cohortHead];
    int slot = cohortMemberStarts[cohortHead];
    for (int index = 0; index < count; index++) {
      IndexedGroupCommitRequest request = requests[slot];
      if (request == null) return StatusCode.INVARIANT_BROKEN;
      batch.addPublished(
          index, request, memberCommitSequences[slot], memberCommittedRows[slot]);
      slot = increment(slot);
    }
    return StatusCode.OK;
  }

  StatusCode removeHead() {
    if (empty()) return StatusCode.CONFLICT;
    int count = cohortMemberCounts[cohortHead];
    if (cohortMemberStarts[cohortHead] != memberHead || count <= 0 || count > memberCount) {
      return StatusCode.INVARIANT_BROKEN;
    }
    for (int index = 0; index < count; index++) {
      requests[memberHead] = null;
      memberCommitSequences[memberHead] = 0;
      memberCommittedRows[memberHead] = 0;
      memberHead = increment(memberHead);
    }
    memberCount -= count;
    cohortTokens[cohortHead] = 0;
    requiredWalEnds[cohortHead] = 0;
    cohortMemberStarts[cohortHead] = 0;
    cohortMemberCounts[cohortHead] = 0;
    cohortFrameHeads[cohortHead] = -1;
    cohortHead = increment(cohortHead);
    cohortCount--;
    return StatusCode.OK;
  }

  private int increment(int slot) {
    return slot + 1 == requests.length ? 0 : slot + 1;
  }
}
