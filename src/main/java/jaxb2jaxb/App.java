package jaxb2jaxb;

import javax.xml.bind.annotation.XmlElement;
import java.beans.IntrospectionException;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static java.util.Locale.ENGLISH;

/**
 * Генерируем код для копирования jaxb-контейнеров в другие jaxb-контейнеры.
 *
 * @author alexander
 */
public class App {
    private static final String LF = System.lineSeparator();
    private static final Set<String> RT_PACKAGES = new HashSet<>(Arrays.asList(
            "java.lang", "java.util", "java.math", "java.time", "javax.xml"));

    private Set<Class<?>> imports;
    private Set<String> importsSimple;
    private Deque<List<Class<?>>> waitingPairs;
    private Set<List<Class<?>>> allPairs;

    public static void main(String[] args) {
        try {
            new App().run(args);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void run(String[] args) throws Exception {
        if (args.length < 2 || args[0].equals("-h") || args[0].equals("--help")) {
            help();
            System.exit(0);
        }

        Path file = Paths.get("JaxbTransformer.java");
        Files.write(file, generate(args[0], args[1]).toString().getBytes("UTF-8"));
        System.exit(0);
    }

    private void help() {
        out("jaxb2jaxb 1.0.0");
        out("Add your classes to classpath, then use parameters: SOURCE_CLASS DESTINATION_CLASS");
    }

    private CharSequence generate(String src, String dst) throws ClassNotFoundException, IntrospectionException, NoSuchMethodException {
        Class<?> srcRootClass = Class.forName(src);
        Class<?> dstRootClass = Class.forName(dst);

        imports = new HashSet<>();
        importsSimple = new TreeSet<>();
        waitingPairs = new ArrayDeque<>();
        allPairs = new HashSet<>();
        waitingPairs.addLast(Arrays.asList(srcRootClass, dstRootClass));

        StringBuilder sbClass = new StringBuilder();
        sbClass.append("/** Generated on ").append(new Date()).append(" */").append(LF);
        sbClass.append("public class JaxbTransformer {").append(LF);

        while (!waitingPairs.isEmpty()) {
            List<Class<?>> classPair = waitingPairs.removeLast();
            Class srcClass = classPair.get(0);
            Class dstClass = classPair.get(1);

            out(srcClass.getName() + " -> " + dstClass.getName());

            sbClass.append("  public ")
                    .append(importClass(dstClass))
                    .append(" transform(")
                    .append(importClass(srcClass))
                    .append(" a) {")
                    .append(LF);

            sbClass.append("    if (a == null)").append(LF);
            sbClass.append("      return null;").append(LF);
            sbClass.append(LF);

            sbClass.append("    ")
                    .append(importClass(dstClass))
                    .append(" r = new ")
                    .append(importClass(dstClass))
                    .append("();").append(LF);

            // Идем по полям.
            for (Field srcField : srcClass.getDeclaredFields()) {
                Field dstField;

                try {
                    dstField = dstClass.getDeclaredField(srcField.getName());
                } catch (NoSuchFieldException e) {
                    sbClass.append("    // r.").append(srcField.getName()).append(" not found").append(LF);
                    err("Field not found. Class: " + srcClass.getName() + ", field: " + srcField.getName());
                    continue;
                }

                out("    ." + dstField.getName());

                if (dstField.getType().equals(List.class) && srcField.getType().equals(List.class)) {
                    ParameterizedType parameterizedSrcType = (ParameterizedType) srcField.getGenericType();
                    Class srcListType = (Class) parameterizedSrcType.getActualTypeArguments()[0];

                    ParameterizedType parameterizedDstType = (ParameterizedType) dstField.getGenericType();
                    Class dstListType = (Class) parameterizedDstType.getActualTypeArguments()[0];

                    if (isRtClass(dstListType) || srcListType.equals(dstListType)) {
                        sbClass.append("    r.").append(getName(dstClass, dstField))
                                .append("().addAll(")
                                .append("a.").append(getName(srcClass, srcField))
                                .append("());").append(LF);
                    } else {
                        sbClass.append("    ").append("for (").append(importClass(srcListType)).append(" i : ")
                                .append("a.").append(getName(srcClass, srcField))
                                .append("())").append(LF);

                        sbClass.append("      r.").append(getName(dstClass, dstField))
                                .append("().add(transform(i));").append(LF)
                                .append(LF);

                        needClassPair(srcListType, dstListType);
                    }

                } else if (isRtClass(dstField.getType())) {
                    sbClass.append("    r.").append(setName(dstClass, dstField)).append("(")
                            .append("a.").append(getName(srcClass, srcField))
                            .append("());").append(LF);

                } else if (dstField.getType().isEnum()) {
                    sbClass.append("    if (a.").append(getName(srcClass, srcField)).append("() != null)").append(LF);

                    sbClass.append("      r.").append(setName(dstClass, dstField)).append("(")
                            .append(importClass(dstField.getType()))
                            .append(".valueOf(a.").append(getName(srcClass, srcField))
                            .append("().name()));").append(LF)
                            .append(LF);

                } else {
                    sbClass.append("    ")
                            .append("r.").append(setName(dstClass, dstField)).append("(")
                            .append("transform(a.").append(getName(srcClass, srcField))
                            .append("()));").append(LF);

                    needClassPair(srcField.getType(), dstField.getType());
                }
            }

            sbClass.append("    return r;").append(LF);

            sbClass.append("  }").append(LF);
            sbClass.append(LF);
        }

        sbClass.append("}").append(LF);

        StringBuilder sbMain = new StringBuilder();
        sbMain.append("package com.example;").append(LF);
        sbMain.append(LF);

        imports.stream()
                .map(Class::getName)
                .sorted()
                .forEach(s -> sbMain.append("import ").append(s).append(";").append(LF));

        sbMain.append(LF);

        return sbMain.append(sbClass);
    }

    private boolean isRtClass(Class<?> c) {
        return RT_PACKAGES.stream()
                .anyMatch(s -> c.getName().startsWith(s + "."));
    }

    private void needClassPair(Class<?> src, Class<?> dst) {
        List<Class<?>> pair = Arrays.asList(src, dst);

        if (!allPairs.contains(pair)) {
            waitingPairs.add(pair);
            allPairs.add(pair);
        }
    }

    private String importClass(Class<?> c) {
        if (imports.contains(c))
            return c.getSimpleName();

        if (importsSimple.contains(c.getSimpleName()))
            return c.getName();  // Уже занято короткое имя.

        imports.add(c);
        importsSimple.add(c.getSimpleName());
        return c.getSimpleName();
    }

    private String setName(Class<?> c, Field field) throws IntrospectionException, NoSuchMethodException {
        String name = field.getName();
        String methodName = "set" + name.substring(0, 1).toUpperCase(ENGLISH) + name.substring(1);
        try {
            return c.getMethod(methodName, field.getType()).getName();
        } catch (NoSuchMethodException e) {
            XmlElement xmlElement = field.getDeclaredAnnotation(XmlElement.class);
            name = xmlElement.name();
            methodName = "set" + name.substring(0, 1).toUpperCase(ENGLISH) + name.substring(1);
            return c.getMethod(methodName, field.getType()).getName();
        }
    }

    private String getName(Class<?> c, Field field) throws IntrospectionException, NoSuchMethodException {
        String name = field.getName();
        String prefix = field.getType().getName().equals("boolean") ? "is" : "get";
        String methodName = prefix + name.substring(0, 1).toUpperCase(ENGLISH) + name.substring(1);
        try {
            return c.getMethod(methodName).getName();
        } catch (NoSuchMethodException e) {
            XmlElement xmlElement = field.getDeclaredAnnotation(XmlElement.class);
            name = xmlElement.name();
            methodName = prefix + name.substring(0, 1).toUpperCase(ENGLISH) + name.substring(1);
            return c.getMethod(methodName).getName();
        }
    }

    private void out(String s) {
        System.out.println("-- " + s);
    }

    private void err(String s) {
        System.err.println("!! ERROR: " + s);
    }

}
