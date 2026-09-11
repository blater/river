package io.riverdb.platform.riverd.apfs;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** Native operations that inspect or mutate directory namespace state. */
@SuppressWarnings("restricted")
final class DarwinNamespaceBridge {
  static final int AT_SYMLINK_NOFOLLOW = 0x20;
  static final int AT_REMOVEDIR = 0x80;
  static final int RENAME_EXCL = 0x4;
  static final int ACL_TYPE_EXTENDED = 0x100;
  static final int ENOENT = 2;
  static final int EEXIST = 17;
  static final int EACCES = 13;
  static final int EINVAL = 22;
  static final int ENOTDIR = 20;
  static final int ENOTEMPTY = 66;
  static final int ENOSPC = 28;
  static final int ELOOP = 62;
  static final int S_IFMT = 0170000;
  static final int S_IFREG = 0100000;
  static final int S_IFDIR = 0040000;

  private DarwinNamespaceBridge() { }

  static int mkdirAt(int parent, String name, int mode) {
    try (Arena arena = Arena.ofConfined()) {
      DarwinNativeBindings.CallState state = DarwinNativeBindings.callState();
      int status = (int) DarwinNativeBindings.MKDIRAT.invokeExact(
          state.segment, parent, arena.allocateFrom(name), mode);
      if (status != 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("mkdirat", failure);
    }
  }

  static MemorySegment openDirectoryStream(int fd) {
    try {
      DarwinNativeBindings.CallState state = DarwinNativeBindings.callState();
      MemorySegment directory = (MemorySegment) DarwinNativeBindings.FDOPENDIR.invokeExact(
          state.segment, fd);
      if (directory.equals(MemorySegment.NULL)) state.captureErrno();
      return directory;
    } catch (Throwable failure) {
      throw bridgeFailure("fdopendir", failure);
    }
  }

  static int readDirectory(
      MemorySegment directory, MemorySegment entryBuffer, MemorySegment resultPointer) {
    try {
      DarwinNativeBindings.CallState state = DarwinNativeBindings.callState();
      int status = (int) DarwinNativeBindings.READDIR_R.invokeExact(
          state.segment, directory, entryBuffer, resultPointer);
      if (status != 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("readdir_r", failure);
    }
  }

  static int closeDirectoryStream(MemorySegment directory) {
    try {
      DarwinNativeBindings.CallState state = DarwinNativeBindings.callState();
      int status = (int) DarwinNativeBindings.CLOSEDIR.invokeExact(state.segment, directory);
      if (status != 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("closedir", failure);
    }
  }

  static NativeStat stat(int fd) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment bytes = arena.allocate(144, 8);
      DarwinNativeBindings.CallState state = DarwinNativeBindings.callState();
      int status = (int) DarwinNativeBindings.FSTAT.invokeExact(state.segment, fd, bytes);
      if (status != 0) state.captureErrno();
      return status == 0 ? readStat(bytes) : null;
    } catch (Throwable failure) {
      throw bridgeFailure("fstat", failure);
    }
  }

  static NativeStat statAt(int parent, String name) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment bytes = arena.allocate(144, 8);
      DarwinNativeBindings.CallState state = DarwinNativeBindings.callState();
      int status = (int) DarwinNativeBindings.FSTATAT.invokeExact(
          state.segment, parent, arena.allocateFrom(name), bytes, AT_SYMLINK_NOFOLLOW);
      if (status != 0) state.captureErrno();
      return status == 0 ? readStat(bytes) : null;
    } catch (Throwable failure) {
      throw bridgeFailure("fstatat", failure);
    }
  }

  static int effectiveUid() {
    try {
      return (int) DarwinNativeBindings.GETEUID.invokeExact();
    } catch (Throwable failure) {
      throw bridgeFailure("geteuid", failure);
    }
  }

  /** Returns 0 for no ACL or deny-only ACL, 1 when an allow entry is present, -1 if unreadable. */
  static int aclAllows(int fd) {
    try (Arena arena = Arena.ofConfined()) {
      DarwinNativeBindings.CallState state = DarwinNativeBindings.callState();
      MemorySegment acl = (MemorySegment) DarwinNativeBindings.ACL_GET.invokeExact(
          state.segment, fd, ACL_TYPE_EXTENDED);
      if (acl.equals(MemorySegment.NULL)) {
        state.captureErrno();
        return state.errno == ENOENT ? 0 : -1;
      }
      MemorySegment entryPointer = arena.allocate(ValueLayout.ADDRESS);
      MemorySegment tagPointer = arena.allocate(ValueLayout.JAVA_INT);
      int entryId = 0;
      while (true) {
        int result = (int) DarwinNativeBindings.ACL_GET_ENTRY.invokeExact(
            state.segment, acl, entryId, entryPointer);
        if (result != 0) {
          state.captureErrno();
          int error = state.errno;
          freeAcl(acl);
          return error == EINVAL ? 0 : -1;
        }
        MemorySegment entry = entryPointer.get(ValueLayout.ADDRESS, 0);
        if (entry.equals(MemorySegment.NULL)) {
          freeAcl(acl);
          return -1;
        }
        int tagStatus = (int) DarwinNativeBindings.ACL_GET_TAG.invokeExact(
            state.segment, entry, tagPointer);
        if (tagStatus != 0) {
          state.captureErrno();
          freeAcl(acl);
          return -1;
        }
        if (tagPointer.get(ValueLayout.JAVA_INT, 0) == 1) {
          freeAcl(acl);
          return 1;
        }
        entryId = -1;
      }
    } catch (Throwable failure) {
      throw bridgeFailure("acl_get_fd_np/acl_get_entry", failure);
    }
  }

  static int forceDirectory(int fd) {
    try {
      DarwinNativeBindings.CallState state = DarwinNativeBindings.callState();
      int status = (int) DarwinNativeBindings.FSYNC.invokeExact(state.segment, fd);
      if (status != 0) state.captureErrno();
      if (status != 0) return status;
      // fsync establishes namespace ordering; F_FULLFSYNC asks the device to flush it.
      status = (int) DarwinNativeBindings.FULLFSYNC.invokeExact(
          state.segment, fd, DarwinNativeBindings.F_FULLFSYNC);
      if (status != 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("fsync", failure);
    }
  }

  static int renameExclusive(int parent, String stage, String target) {
    return renameExclusive(parent, stage, parent, target);
  }

  static int renameExclusive(int sourceParent, String stage, int targetParent, String target) {
    try (Arena arena = Arena.ofConfined()) {
      DarwinNativeBindings.CallState state = DarwinNativeBindings.callState();
      int status = (int) DarwinNativeBindings.RENAME.invokeExact(state.segment, sourceParent,
          arena.allocateFrom(stage), targetParent, arena.allocateFrom(target), RENAME_EXCL);
      if (status != 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("renameatx_np", failure);
    }
  }

  static int renameReplace(int parent, String stage, String target) {
    try (Arena arena = Arena.ofConfined()) {
      DarwinNativeBindings.CallState state = DarwinNativeBindings.callState();
      int status = (int) DarwinNativeBindings.RENAME.invokeExact(state.segment, parent,
          arena.allocateFrom(stage), parent, arena.allocateFrom(target), 0);
      if (status != 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("renameatx_np", failure);
    }
  }

  static int remove(int parent, String name) {
    try (Arena arena = Arena.ofConfined()) {
      DarwinNativeBindings.CallState state = DarwinNativeBindings.callState();
      int status = (int) DarwinNativeBindings.UNLINK.invokeExact(
          state.segment, parent, arena.allocateFrom(name), 0);
      if (status != 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("unlinkat", failure);
    }
  }

  static int removeDirectory(int parent, String name) {
    try (Arena arena = Arena.ofConfined()) {
      DarwinNativeBindings.CallState state = DarwinNativeBindings.callState();
      int status = (int) DarwinNativeBindings.UNLINK.invokeExact(
          state.segment, parent, arena.allocateFrom(name), AT_REMOVEDIR);
      if (status != 0) state.captureErrno();
      return status;
    } catch (Throwable failure) {
      throw bridgeFailure("unlinkat", failure);
    }
  }

  private static NativeStat readStat(MemorySegment bytes) {
    long device = Integer.toUnsignedLong(bytes.get(ValueLayout.JAVA_INT, 0));
    int mode = Short.toUnsignedInt(bytes.get(ValueLayout.JAVA_SHORT, 4));
    int links = Short.toUnsignedInt(bytes.get(ValueLayout.JAVA_SHORT, 6));
    long inode = bytes.get(ValueLayout.JAVA_LONG, 8);
    int uid = bytes.get(ValueLayout.JAVA_INT, 16);
    long size = bytes.get(ValueLayout.JAVA_LONG, 96);
    return new NativeStat(
        new io.riverdb.platform.riverd.FileIdentity(device, 0, inode), mode, links, uid, size);
  }

  private static void freeAcl(MemorySegment acl) throws Throwable {
    int ignored = (int) DarwinNativeBindings.ACL_FREE.invokeExact(
        DarwinNativeBindings.callState().segment, acl);
  }

  static final class NativeStat {
    final io.riverdb.platform.riverd.FileIdentity identity;
    final int mode;
    final int links;
    final int uid;
    final long size;

    NativeStat(
        io.riverdb.platform.riverd.FileIdentity identity,
        int mode,
        int links,
        int uid,
        long size) {
      this.identity = identity;
      this.mode = mode;
      this.links = links;
      this.uid = uid;
      this.size = size;
    }

    boolean regularFile() {
      return (mode & S_IFMT) == S_IFREG;
    }

    boolean directory() {
      return (mode & S_IFMT) == S_IFDIR;
    }
  }

  private static AssertionError bridgeFailure(String operation, Throwable failure) {
    return new AssertionError("Darwin FFM ABI invocation failed: " + operation, failure);
  }
}
