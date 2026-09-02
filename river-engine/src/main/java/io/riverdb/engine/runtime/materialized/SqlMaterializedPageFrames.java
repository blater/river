package io.riverdb.engine.runtime.materialized;

import java.nio.ByteBuffer;

/** Bounded structure-of-arrays metadata for every configured cache frame. */
final class SqlMaterializedPageFrames {
  static final byte FREE = 0;
  static final byte READY = 1;
  static final byte LOADING = 2;
  static final byte WRITING = 3;
  static final byte RETIRED = 4;

  final ByteBuffer[] buffers;
  final SqlMaterializedPageIo[] io;
  final long[] fileIdentities;
  final long[] pageNumbers;
  final long[] fileOwners;
  final long[] reservationOwners;
  final long[] generations;
  final int[] pins;
  final int[] candidates;
  final boolean[] dirty;
  final boolean[] failed;
  final boolean[] referenced;
  final boolean[] selected;
  final byte[] state;

  SqlMaterializedPageFrames(int count) {
    buffers = new ByteBuffer[count];
    io = new SqlMaterializedPageIo[count];
    fileIdentities = new long[count];
    pageNumbers = new long[count];
    fileOwners = new long[count];
    reservationOwners = new long[count];
    generations = new long[count];
    pins = new int[count];
    candidates = new int[count];
    dirty = new boolean[count];
    failed = new boolean[count];
    referenced = new boolean[count];
    selected = new boolean[count];
    state = new byte[count];
  }

  int count() { return state.length; }
}
