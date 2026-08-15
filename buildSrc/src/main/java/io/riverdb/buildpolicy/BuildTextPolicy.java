package io.riverdb.buildpolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Checks tabs and indentation in the build's selected text files. */
final class BuildTextPolicy {
  private BuildTextPolicy() {
  }

  static void check(
      Path root,
      Collection<Path> files,
      Set<String> checkedExtensions,
      Set<String> indentedExtensions,
      List<String> violations
  ) {
    List<Path> ordered = new ArrayList<>(files);
    ordered.sort(Path::compareTo);
    for (Path file : ordered) {
      String extension = extension(file);
      if (checkedExtensions.contains(extension)) {
        checkFile(root, file, extension, indentedExtensions, violations);
      }
    }
  }

  private static void checkFile(
      Path root,
      Path file,
      String extension,
      Set<String> indentedExtensions,
      List<String> violations
  ) {
    String text;
    try {
      text = Files.readString(file);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read policy input " + file, exception);
    }
    String[] lines = text.split("\\R", -1);
    for (int index = 0; index < lines.length; index++) {
      checkLine(root, file, extension, indentedExtensions, lines[index], index + 1, violations);
    }
  }

  private static void checkLine(
      Path root,
      Path file,
      String extension,
      Set<String> indentedExtensions,
      String line,
      int lineNumber,
      List<String> violations
  ) {
    String location = relative(root, file) + ":" + lineNumber;
    if (line.indexOf('\t') >= 0) {
      violations.add(location + ": tab character");
    }
    if (!indentedExtensions.contains(extension) || line.isBlank()) {
      return;
    }
    int leadingSpaces = leadingSpaces(line);
    String trimmed = line.substring(leadingSpaces);
    if (!trimmed.startsWith("*") && leadingSpaces % 2 != 0) {
      violations.add(location + ": indentation is not a multiple of two");
    }
  }

  private static int leadingSpaces(String line) {
    int spaces = 0;
    while (spaces < line.length() && line.charAt(spaces) == ' ') {
      spaces++;
    }
    return spaces;
  }

  private static String relative(Path root, Path path) {
    Path absoluteRoot = root.toAbsolutePath().normalize();
    Path absolutePath = path.toAbsolutePath().normalize();
    return absolutePath.startsWith(absoluteRoot)
        ? absoluteRoot.relativize(absolutePath).toString()
        : path.toString();
  }

  private static String extension(Path path) {
    String name = path.getFileName().toString();
    int separator = name.lastIndexOf('.');
    return separator < 0 ? "" : name.substring(separator + 1).toLowerCase(java.util.Locale.ROOT);
  }
}
