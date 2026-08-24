package io.riverdb.buildpolicy;

import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewPrimitiveArrayInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.ThrowInstruction;
import java.lang.constant.DirectMethodHandleDesc;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** Audits the selected method instructions and consumes exact allowlist entries. */
final class HotPathBytecodeAuditor {
  private static final String STRING_CONCAT_FACTORY =
      "Ljava/lang/invoke/StringConcatFactory;";
  private static final String LAMBDA_METAFACTORY =
      "Ljava/lang/invoke/LambdaMetafactory;";
  private static final Set<String> WRAPPERS = Set.of(
      "java/lang/Boolean", "java/lang/Byte", "java/lang/Short", "java/lang/Character",
      "java/lang/Integer", "java/lang/Long", "java/lang/Float", "java/lang/Double");

  private HotPathBytecodeAuditor() {
  }

  static void audit(
      HotPathBytecodePolicy.ParsedClass owner,
      MethodModel method,
      CodeModel code,
      HotPathBytecodePolicy.ControlledHierarchy hierarchy,
      Set<HotPathBytecodePolicy.Allowance> allowed,
      java.util.List<String> violations) {
    Set<HotPathBytecodePolicy.Allowance> usedAllowances = new LinkedHashSet<>();
    int instructionIndex = 0;
    for (CodeElement element : code) {
      if (!(element instanceof java.lang.classfile.Instruction instruction)) continue;
      if (instruction instanceof NewObjectInstruction allocation) {
        String allocatedClass = allocation.className().asInternalName();
        String detail = "new " + allocatedClass.replace('/', '.');
        add(owner, method, instructionIndex, HotPathBytecodePolicy.Rule.OBJECT_ALLOCATION,
            detail, allowed, usedAllowances, violations);
        ThrowableState throwable = throwableState(allocatedClass, hierarchy);
        if (throwable != ThrowableState.NO) {
          String throwableDetail = throwable == ThrowableState.YES
              ? detail : "unresolved throwable ancestry for " + detail;
          add(owner, method, instructionIndex,
              HotPathBytecodePolicy.Rule.EXCEPTION_CONSTRUCTION, throwableDetail,
              allowed, usedAllowances, violations);
        }
      } else if (instruction instanceof NewPrimitiveArrayInstruction allocation) {
        add(owner, method, instructionIndex, HotPathBytecodePolicy.Rule.ARRAY_ALLOCATION,
            "new " + allocation.typeKind().name().toLowerCase(java.util.Locale.ROOT) + " array",
            allowed, usedAllowances, violations);
      } else if (instruction instanceof NewReferenceArrayInstruction allocation) {
        add(owner, method, instructionIndex, HotPathBytecodePolicy.Rule.ARRAY_ALLOCATION,
            "new " + allocation.componentType().asInternalName().replace('/', '.') + " array",
            allowed, usedAllowances, violations);
      } else if (instruction instanceof NewMultiArrayInstruction allocation) {
        add(owner, method, instructionIndex, HotPathBytecodePolicy.Rule.ARRAY_ALLOCATION,
            "new " + allocation.arrayType().asInternalName().replace('/', '.')
                + " dimensions=" + allocation.dimensions(),
            allowed, usedAllowances, violations);
      } else if (instruction instanceof ThrowInstruction) {
        add(owner, method, instructionIndex, HotPathBytecodePolicy.Rule.EXCEPTION_THROW,
            "athrow", allowed, usedAllowances, violations);
      } else if (instruction instanceof InvokeInstruction invocation) {
        auditInvocation(owner, method, instructionIndex, invocation, allowed,
            usedAllowances, violations);
      } else if (instruction instanceof InvokeDynamicInstruction invocation) {
        auditInvokeDynamic(owner, method, instructionIndex, invocation, allowed,
            usedAllowances, violations);
      }
      instructionIndex++;
    }
    Set<HotPathBytecodePolicy.Allowance> unused = new LinkedHashSet<>(allowed);
    unused.removeAll(usedAllowances);
    unused.stream().sorted((left, right) -> {
      int ruleOrder = left.rule().compareTo(right.rule());
      return ruleOrder == 0 ? left.detail().compareTo(right.detail()) : ruleOrder;
    }).forEach(allowance -> violations.add(
        owner.path() + ": stale hot-path allowlist " + methodDisplay(owner, method)
            + ": " + allowance.rule().diagnostic() + " (" + allowance.detail() + ")"));
  }

  private static void auditInvocation(
      HotPathBytecodePolicy.ParsedClass owner,
      MethodModel method,
      int index,
      InvokeInstruction invocation,
      Set<HotPathBytecodePolicy.Allowance> allowed,
      Set<HotPathBytecodePolicy.Allowance> used,
      java.util.List<String> violations) {
    MethodReference reference = new MethodReference(
        invocation.owner().asInternalName(), invocation.name().stringValue(),
        invocation.type().stringValue());
    if (isBoxing(reference)) add(owner, method, index, HotPathBytecodePolicy.Rule.BOXING,
        reference.displayName(), allowed, used, violations);
    if (isStream(reference)) add(owner, method, index, HotPathBytecodePolicy.Rule.STREAM_API,
        reference.displayName(), allowed, used, violations);
    if (isFormatting(reference)) add(owner, method, index,
        HotPathBytecodePolicy.Rule.STRING_FORMAT, reference.displayName(), allowed, used,
        violations);
  }

  private static void auditInvokeDynamic(
      HotPathBytecodePolicy.ParsedClass owner,
      MethodModel method,
      int index,
      InvokeDynamicInstruction invocation,
      Set<HotPathBytecodePolicy.Allowance> allowed,
      Set<HotPathBytecodePolicy.Allowance> used,
      java.util.List<String> violations) {
    DirectMethodHandleDesc bootstrap = invocation.bootstrapMethod();
    String bootstrapOwner = bootstrap.owner().descriptorString();
    HotPathBytecodePolicy.Rule rule = bootstrapOwner.equals(STRING_CONCAT_FACTORY)
        ? HotPathBytecodePolicy.Rule.STRING_CONCAT
        : HotPathBytecodePolicy.Rule.OTHER_INVOKEDYNAMIC;
    String kind = bootstrapOwner.equals(LAMBDA_METAFACTORY) ? "lambda metafactory " : "bootstrap ";
    String detail = kind + bootstrap.owner().displayName() + "#"
        + bootstrap.methodName() + bootstrap.lookupDescriptor();
    add(owner, method, index, rule, detail, allowed, used, violations);
  }

  private static void add(
      HotPathBytecodePolicy.ParsedClass owner,
      MethodModel method,
      int index,
      HotPathBytecodePolicy.Rule rule,
      String detail,
      Set<HotPathBytecodePolicy.Allowance> allowed,
      Set<HotPathBytecodePolicy.Allowance> used,
      java.util.List<String> violations) {
    HotPathBytecodePolicy.Allowance allowance =
        new HotPathBytecodePolicy.Allowance(rule, detail);
    if (allowed.contains(allowance) && used.add(allowance)) return;
    violations.add(owner.path() + ": " + methodDisplay(owner, method) + " instruction " + index
        + ": " + rule.diagnostic() + " (" + detail + ")");
  }

  private static String methodDisplay(HotPathBytecodePolicy.ParsedClass owner, MethodModel method) {
    return owner.model().thisClass().asInternalName().replace('/', '.') + "#"
        + method.methodName().stringValue() + method.methodType().stringValue();
  }

  private static boolean isBoxing(MethodReference reference) {
    return WRAPPERS.contains(reference.owner())
        && (reference.name().equals("valueOf") || reference.name().equals("<init>"));
  }

  private static boolean isStream(MethodReference reference) {
    return reference.owner().startsWith("java/util/stream/")
        || returnDescriptor(reference.descriptor()).startsWith("Ljava/util/stream/");
  }

  private static boolean isFormatting(MethodReference reference) {
    if (reference.owner().equals("java/util/Formatter")) return true;
    return (reference.owner().equals("java/lang/String")
        || reference.owner().equals("java/io/PrintStream")
        || reference.owner().equals("java/io/PrintWriter"))
        && (reference.name().equals("format") || reference.name().equals("formatted")
            || reference.name().equals("printf"));
  }

  private static String returnDescriptor(String descriptor) {
    int separator = descriptor.lastIndexOf(')');
    return separator < 0 ? "" : descriptor.substring(separator + 1);
  }

  private static ThrowableState throwableState(
      String className, HotPathBytecodePolicy.ControlledHierarchy hierarchy) {
    Set<String> visited = new HashSet<>();
    String current = className;
    while (current != null && visited.add(current)) {
      if (current.equals("java/lang/Throwable")) return ThrowableState.YES;
      java.util.Optional<String> superclass = hierarchy.superclass(current);
      if (superclass == null) return ThrowableState.UNKNOWN;
      current = superclass.orElse(null);
    }
    return current == null ? ThrowableState.NO : ThrowableState.UNKNOWN;
  }

  private record MethodReference(String owner, String name, String descriptor) {
    String displayName() {
      return owner.replace('/', '.') + "#" + name + descriptor;
    }
  }

  private enum ThrowableState { YES, NO, UNKNOWN }
}
