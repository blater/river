package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.FileIdentity;
import java.nio.file.Path;

/** Inspects launch targets through capabilities and admits only non-colliding identities. */
final class RiverDaemonPathInspection {
  private RiverDaemonPathInspection() { }

  /**
   * Revalidates existing prospective objects through the platform descriptor boundary. Call this
   * immediately before creating missing parents and again before publishing runtime records.
   */
  static StatusCode verify(RiverDaemonFileSystem filesystem,
      RiverDaemonPathSelection.Result paths) {
    if (filesystem == null || paths == null || !RiverDaemonPathSelection.valid(
        paths.datadir, paths.runtimeRoot, paths.ready)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    try {
      RiverDaemonPathProbe.Target data = RiverDaemonPathProbe.inspectDirectory(
          filesystem, paths.datadir);
      if (!data.status.isOk()) return data.status;
      RiverDaemonPathProbe.Target[] fixedChildren = new RiverDaemonPathProbe.Target[2];
      String[] fixedNames = {
          RiverDaemonIdentity.DATABASE_NAME,
          RiverDaemonIdentity.SECURITY_NAME};
      for (int index = 0; index < fixedNames.length; index++) {
        fixedChildren[index] = RiverDaemonPathProbe.inspectDirectory(
            filesystem, paths.datadir.resolve(fixedNames[index]));
        if (!fixedChildren[index].status.isOk()) return fixedChildren[index].status;
      }
      RiverDaemonPathProbe.Target runtimeRoot = RiverDaemonPathProbe.inspectDirectory(
          filesystem, paths.runtimeRoot);
      if (!runtimeRoot.status.isOk()) return runtimeRoot.status;
      RiverDaemonPathProbe.Target ready = paths.ready == null
          ? RiverDaemonPathProbe.Target.missing()
          : RiverDaemonPathProbe.inspectFile(filesystem, paths.ready);
      if (!ready.status.isOk()) return ready.status;
      return admit(data, fixedChildren, runtimeRoot, ready);
    } catch (SecurityException | IllegalArgumentException failure) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
  }

  private static StatusCode admit(
      RiverDaemonPathProbe.Target data, RiverDaemonPathProbe.Target[] fixedChildren,
      RiverDaemonPathProbe.Target runtimeRoot, RiverDaemonPathProbe.Target ready) {
    if (collides(data, runtimeRoot) || collides(data, ready) || collides(runtimeRoot, ready)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < fixedChildren.length; index++) {
      if (collidesWithFixed(fixedChildren[index], data, runtimeRoot, ready)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      for (int other = index + 1; other < fixedChildren.length; other++) {
        if (same(fixedChildren[index].identity, fixedChildren[other].identity)) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
      }
    }
    return StatusCode.OK;
  }

  private static boolean collidesWithFixed(
      RiverDaemonPathProbe.Target fixed, RiverDaemonPathProbe.Target data,
      RiverDaemonPathProbe.Target runtimeRoot, RiverDaemonPathProbe.Target ready) {
    FileIdentity identity = fixed.identity;
    return same(identity, data.identity) || same(identity, runtimeRoot.identity)
        || same(identity, ready.identity) || same(identity, data.parentIdentity)
        || same(identity, runtimeRoot.parentIdentity) || same(identity, ready.parentIdentity);
  }

  private static boolean collides(
      RiverDaemonPathProbe.Target first, RiverDaemonPathProbe.Target second) {
    return same(first.identity, second.identity)
        || same(first.parentIdentity, second.identity)
        || same(second.parentIdentity, first.identity)
        || (same(first.parentIdentity, second.parentIdentity)
            && first.name != null && first.name.equals(second.name));
  }

  private static boolean same(FileIdentity first, FileIdentity second) {
    return first != null && second != null && first.equals(second);
  }

}
