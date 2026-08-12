package com.taiwei.aiagent.util;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Extracts Python PSI context without putting Python plugin classes on the compile
 * classpath. All Python-specific lookups and calls are reflective and best-effort.
 */
public final class ReflectivePythonPsi {

    private static final String PY_FILE = "com.jetbrains.python.psi.PyFile";
    private static final String PY_CLASS = "com.jetbrains.python.psi.PyClass";
    private static final String PY_FUNCTION = "com.jetbrains.python.psi.PyFunction";
    private static final int MAX_DOCSTRING_CHARS = 400;

    private ReflectivePythonPsi() {}

    public static boolean isPythonFile(PsiFile file) {
        if (file == null || !PythonPluginAvailability.isPythonPluginAvailable()) {
            return false;
        }
        try {
            String languageId = file.getLanguage().getID();
            String languageClass = file.getLanguage().getClass().getName();
            if (containsPython(languageId) || containsPython(languageClass)) {
                return true;
            }
        } catch (Throwable ignored) {
            // Fall through to reflective and file-name checks.
        }

        Class<?> pyFileClass = loadPythonClass(PY_FILE, file);
        if (pyFileClass != null && pyFileClass.isInstance(file)) {
            return true;
        }
        try {
            String fileName = file.getName().toLowerCase(Locale.ROOT);
            return fileName.endsWith(".py") || fileName.endsWith(".pyi");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Returns import and enclosing class/function context for a cursor element. */
    public static String buildContext(PsiElement element) {
        if (element == null || !PythonPluginAvailability.isPythonPluginAvailable()) {
            return "";
        }
        try {
            PsiFile file = element.getContainingFile();
            if (!isPythonFile(file)) {
                return "";
            }

            StringBuilder context = new StringBuilder();
            for (String importStatement : extractImports(file)) {
                context.append(importStatement).append('\n');
            }
            if (context.length() > 0) {
                context.append('\n');
            }
            appendEnclosingScopes(context, element);
            return context.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void appendEnclosingScopes(StringBuilder context, PsiElement element) {
        // A cursor can sit on platform-owned whitespace. Use the PyFile implementation's
        // class loader so Python interfaces remain visible in that case as well.
        PsiFile containingFile = element.getContainingFile();
        Class<?> pyClass = loadPythonClass(PY_CLASS, containingFile);
        Class<?> pyFunction = loadPythonClass(PY_FUNCTION, containingFile);
        if (pyClass == null && pyFunction == null) {
            return;
        }

        List<PsiElement> scopes = new ArrayList<>();
        PsiElement current = element;
        while (current != null && !(current instanceof PsiFile)) {
            if ((pyClass != null && pyClass.isInstance(current))
                    || (pyFunction != null && pyFunction.isInstance(current))) {
                scopes.add(current);
            }
            current = current.getParent();
        }
        Collections.reverse(scopes);

        for (PsiElement scope : scopes) {
            if (pyClass != null && pyClass.isInstance(scope)) {
                appendClassContext(context, scope);
            } else if (pyFunction != null && pyFunction.isInstance(scope)) {
                appendFunctionContext(context, scope);
            }
        }
        if (!scopes.isEmpty()) {
            context.append('\n');
        }
    }

    private static void appendClassContext(StringBuilder context, Object pyClass) {
        String name = stringValue(invokeNoArgs(pyClass, "getName"));
        if (name == null || name.isBlank()) {
            return;
        }
        context.append("# enclosing class: ").append(name);
        String bases = textOf(invokeNoArgs(pyClass, "getSuperClassExpressionList"));
        if (bases != null && !bases.isBlank()) {
            context.append(bases.startsWith("(") ? bases : "(" + bases + ")");
        }
        context.append('\n');
        appendDocString(context, pyClass, "class");
    }

    private static void appendFunctionContext(StringBuilder context, Object pyFunction) {
        String name = stringValue(invokeNoArgs(pyFunction, "getName"));
        if (name == null || name.isBlank()) {
            return;
        }
        String parameters = textOf(invokeNoArgs(pyFunction, "getParameterList"));
        String annotation = textOf(invokeNoArgs(pyFunction, "getAnnotation"));
        Object asyncValue = invokeNoArgs(pyFunction, "isAsync");

        context.append("# enclosing function: ");
        if (Boolean.TRUE.equals(asyncValue)) {
            context.append("async ");
        }
        context.append(name).append(parameters != null ? parameters : "()");
        if (annotation != null && !annotation.isBlank()) {
            context.append(annotation.stripLeading().startsWith("->") ? " " : " -> ")
                    .append(annotation);
        }
        context.append('\n');
        appendDocString(context, pyFunction, "function");
    }

    private static void appendDocString(StringBuilder context, Object owner, String scopeKind) {
        String docString = stringValue(invokeNoArgs(owner, "getDocString"));
        if (docString == null || docString.isBlank()) {
            return;
        }
        String compact = docString.strip().replaceAll("\\s+", " ");
        if (compact.length() > MAX_DOCSTRING_CHARS) {
            compact = compact.substring(0, MAX_DOCSTRING_CHARS) + "...";
        }
        context.append("# ").append(scopeKind).append(" docstring: ").append(compact).append('\n');
    }

    private static List<String> extractImports(PsiFile file) {
        Set<String> imports = new LinkedHashSet<>();
        addTexts(invokeNoArgs(file, "getImportBlock"), imports);
        if (imports.isEmpty()) {
            addTopLevelImports(file.getText(), imports);
        }
        return new ArrayList<>(imports);
    }

    private static void addTexts(Object value, Set<String> output) {
        if (value == null) return;
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) addText(item, output);
        } else if (value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) addText(Array.get(value, i), output);
        } else {
            addText(value, output);
        }
    }

    private static void addText(Object value, Set<String> output) {
        String text = textOf(value);
        if (text != null && !text.isBlank()) output.add(text.strip());
    }

    private static void addTopLevelImports(String fileText, Set<String> output) {
        StringBuilder statement = null;
        int parenthesisDepth = 0;
        for (String line : fileText.split("\\R", -1)) {
            String trimmed = line.stripLeading();
            boolean topLevel = trimmed.length() == line.length();
            if (statement == null) {
                if (!topLevel || !(trimmed.startsWith("import ") || trimmed.startsWith("from "))) continue;
                statement = new StringBuilder(trimmed);
                parenthesisDepth = parenthesisDelta(trimmed);
            } else {
                statement.append('\n').append(line);
                parenthesisDepth += parenthesisDelta(line);
            }
            if (parenthesisDepth <= 0 && !line.stripTrailing().endsWith("\\")) {
                output.add(statement.toString().strip());
                statement = null;
                parenthesisDepth = 0;
            }
        }
        if (statement != null) output.add(statement.toString().strip());
    }

    private static int parenthesisDelta(String text) {
        int delta = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '[' || c == '{') delta++;
            if (c == ')' || c == ']' || c == '}') delta--;
        }
        return delta;
    }

    private static Class<?> loadPythonClass(String className, Object context) {
        if (!PythonPluginAvailability.isPythonPluginAvailable()) return null;
        try {
            ClassLoader loader = context != null ? context.getClass().getClassLoader() : null;
            return loader != null
                    ? Class.forName(className, false, loader)
                    : Class.forName(className);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeNoArgs(Object target, String methodName) {
        if (target == null || !PythonPluginAvailability.isPythonPluginAvailable()) return null;
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Throwable ignored) {
            // Includes missing APIs, access failures, and InvocationTargetException.
            return null;
        }
    }

    private static String textOf(Object value) {
        return stringValue(invokeNoArgs(value, "getText"));
    }

    private static String stringValue(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static boolean containsPython(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains("python");
    }
}
