package io.riverdb.buildpolicy;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact compiled invocation checks over Java 25 class files. */
public final class InvocationPolicy {
  private InvocationPolicy() {
  }

  /** One method invocation selected by binary owner, JVM name, and descriptor. */
  public record Invocation(String owner, String name, String descriptor) {
    public Invocation {
      if (owner.isBlank() || name.isBlank() || descriptor.isBlank()) {
        throw new IllegalArgumentException("invocation fields must be non-blank");
      }
    }

    String displayName() {
      return owner + "." + name + descriptor;
    }
  }

  public static List<String> violations(
      Path root,
      Collection<Path> classFiles,
      Map<String, Set<Invocation>> forbiddenInvocations
  ) {
    Set<String> violationSet = new LinkedHashSet<>();
    ClassFile parser = ClassFile.of();
    classFiles.stream().sorted().forEach(path -> {
      try {
        ClassModel model = parser.parse(Files.readAllBytes(path));
        String source = model.thisClass().asInternalName();
        Set<Invocation> forbidden = forbiddenFor(source, forbiddenInvocations);
        if (forbidden.isEmpty()) {
          return;
        }
        Set<Invocation> invoked = new LinkedHashSet<>();
        model.methods().forEach(method -> method.code().ifPresent(code ->
            code.forEach(element -> collectInvocation(element, forbidden, invoked))));
        forbidden.stream().filter(invoked::contains)
            .sorted((left, right) -> left.displayName().compareTo(right.displayName()))
            .forEach(invocation -> violationSet.add(
                root.relativize(path) + ": forbidden compiled invocation "
                    + source + " -> " + invocation.displayName()));
      } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException exception) {
        violationSet.add(root.relativize(path)
            + ": malformed class file: " + exception.getClass().getSimpleName());
      }
    });
    List<String> violations = new ArrayList<>(violationSet);
    Collections.sort(violations);
    return List.copyOf(violations);
  }

  private static Set<Invocation> forbiddenFor(
      String source,
      Map<String, Set<Invocation>> forbiddenInvocations
  ) {
    for (Map.Entry<String, Set<Invocation>> rule : forbiddenInvocations.entrySet()) {
      if (source.equals(rule.getKey()) || source.startsWith(rule.getKey() + "$")) {
        return rule.getValue();
      }
    }
    return Set.of();
  }

  private static void collectInvocation(
      CodeElement element,
      Set<Invocation> forbidden,
      Set<Invocation> invoked
  ) {
    if (!(element instanceof InvokeInstruction instruction)) {
      return;
    }
    Invocation invocation = new Invocation(
        instruction.owner().asInternalName(),
        instruction.name().stringValue(),
        instruction.type().stringValue());
    if (forbidden.contains(invocation)) {
      invoked.add(invocation);
    }
  }
}
