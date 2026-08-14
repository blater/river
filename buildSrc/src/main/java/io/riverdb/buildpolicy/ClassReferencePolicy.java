package io.riverdb.buildpolicy;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.DynamicConstantPoolEntry;
import java.lang.classfile.constantpool.MethodTypeEntry;
import java.lang.classfile.constantpool.NameAndTypeEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exact executable, declared-type, and generic-signature reference checks over Java 25 class
 * files. Annotation metadata is outside this focused dependency-graph policy.
 */
public final class ClassReferencePolicy {
  private ClassReferencePolicy() {
  }

  public static List<String> violations(
      Path root,
      Collection<Path> classFiles,
      Map<String, Set<String>> forbiddenTargets
  ) {
    Set<String> violationSet = new LinkedHashSet<>();
    ClassFile parser = ClassFile.of();
    classFiles.stream().sorted().forEach(path -> {
      try {
        ClassModel model = parser.parse(Files.readAllBytes(path));
        String source = model.thisClass().asInternalName();
        Set<String> forbidden = forbiddenFor(source, forbiddenTargets);
        if (forbidden.isEmpty()) {
          return;
        }
        Set<String> referenced = new LinkedHashSet<>();
        for (PoolEntry entry : model.constantPool()) {
          if (entry instanceof ClassEntry type) {
            collectClassEntry(type, forbidden, referenced);
          } else if (entry instanceof NameAndTypeEntry member) {
            collectDescriptor(member.type().stringValue(), forbidden, referenced);
          } else if (entry instanceof MethodTypeEntry methodType) {
            collectDescriptor(methodType.descriptor().stringValue(), forbidden, referenced);
          } else if (entry instanceof DynamicConstantPoolEntry dynamic) {
            collectDescriptor(dynamic.type().stringValue(), forbidden, referenced);
          }
        }
        model.fields().forEach(field -> {
          collectDescriptor(field.fieldType().stringValue(), forbidden, referenced);
          collectSignature(field.findAttribute(Attributes.signature()), forbidden, referenced);
        });
        model.methods().forEach(method -> {
          collectDescriptor(method.methodType().stringValue(), forbidden, referenced);
          collectSignature(method.findAttribute(Attributes.signature()), forbidden, referenced);
          method.code().ifPresent(code -> code.forEach(element ->
              collectInstruction(element, forbidden, referenced)));
        });
        collectSignature(model.findAttribute(Attributes.signature()), forbidden, referenced);
        forbidden.stream().filter(referenced::contains).sorted().forEach(target ->
            violationSet.add(root.relativize(path).toString()
                + ": forbidden compiled reference " + source + " -> " + target));
      } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException exception) {
        violationSet.add(root.relativize(path).toString()
            + ": malformed class file: " + exception.getClass().getSimpleName());
      }
    });
    List<String> violations = new ArrayList<>(violationSet);
    Collections.sort(violations);
    return List.copyOf(violations);
  }

  private static Set<String> forbiddenFor(
      String source,
      Map<String, Set<String>> forbiddenTargets
  ) {
    for (Map.Entry<String, Set<String>> rule : forbiddenTargets.entrySet()) {
      if (source.equals(rule.getKey()) || source.startsWith(rule.getKey() + "$")) {
        return rule.getValue();
      }
    }
    return Set.of();
  }

  private static void collectDescriptor(
      String descriptor,
      Set<String> forbidden,
      Set<String> referenced
  ) {
    forbidden.stream().filter(target -> containsType(descriptor, target))
        .forEach(referenced::add);
  }

  private static void collectClassEntry(
      ClassEntry type,
      Set<String> forbidden,
      Set<String> referenced
  ) {
    String name = type.asInternalName();
    if (name.startsWith("[")) {
      collectDescriptor(name, forbidden, referenced);
    } else {
      referenced.add(name);
    }
  }

  private static void collectSignature(
      java.util.Optional<SignatureAttribute> signature,
      Set<String> forbidden,
      Set<String> referenced
  ) {
    signature.ifPresent(attribute -> collectDescriptor(
        attribute.signature().stringValue(), forbidden, referenced));
  }

  private static boolean containsType(String descriptor, String target) {
    String prefix = "L" + target;
    return descriptor.contains(prefix + ";")
        || descriptor.contains(prefix + "<")
        || descriptor.contains(prefix + ".");
  }

  private static void collectInstruction(
      CodeElement element,
      Set<String> forbidden,
      Set<String> referenced
  ) {
    if (element instanceof FieldInstruction field) {
      referenced.add(field.owner().asInternalName());
      collectDescriptor(field.type().stringValue(), forbidden, referenced);
    } else if (element instanceof InvokeInstruction invoke) {
      referenced.add(invoke.owner().asInternalName());
      collectDescriptor(invoke.type().stringValue(), forbidden, referenced);
    } else if (element instanceof NewObjectInstruction allocation) {
      referenced.add(allocation.className().asInternalName());
    }
  }
}
