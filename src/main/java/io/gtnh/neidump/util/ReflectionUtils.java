package io.gtnh.neidump.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class ReflectionUtils {
    private ReflectionUtils() {
    }

    public static Object readField(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    public static Object readStaticField(String className, String fieldName) {
        try {
            Class<?> clazz = Class.forName(className);
            // getDeclaredField may not find inherited fields; try declared first
            Field field = null;
            try {
                field = clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                Class<?> sup = clazz.getSuperclass();
                while (sup != null) {
                    try {
                        field = sup.getDeclaredField(fieldName);
                        break;
                    } catch (NoSuchFieldException ignored) {
                        sup = sup.getSuperclass();
                    }
                }
            }
            if (field == null) return null;
            field.setAccessible(true);
            return field.get(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Invokes a no-arg method by name.  Searches the class   hierarchy AND
     * all implemented interfaces (necessary for IRecipeHandler methods that
     * are not redeclared by concrete handler classes).
     */
    public static Object invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        Method m = findMethodInHierarchy(target.getClass(), methodName);
        if (m == null) return null;
        try {
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Invokes a method taking a single {@code int} argument.  Same interface‑aware
     * search as {@link #invokeNoArg}.
     */
    public static Object invokeIntArg(Object target, String methodName, int arg) {
        if (target == null) return null;
        Method m = findMethodInHierarchy(target.getClass(), methodName, int.class);
        if (m == null) return null;
        try {
            m.setAccessible(true);
            return m.invoke(target, arg);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Invokes a method taking a {@code String} and {@code Object[]} (varargs).
     * Matches the {@code getRecipeHandler(String, Object...)} signature.
     */
    public static Object invokeStringObjectArray(Object target, String methodName,
                                                  String strArg, Object[] arrArg) {
        if (target == null) return null;
        Method m = findMethodInHierarchy(target.getClass(), methodName,
                String.class, Object[].class);
        if (m == null) return null;
        try {
            m.setAccessible(true);
            return m.invoke(target, strArg, arrArg);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Invokes a method taking a single {@code String}.
     */
    public static Object invokeString(Object target, String methodName, String arg) {
        if (target == null) return null;
        Method m = findMethodInHierarchy(target.getClass(), methodName, String.class);
        if (m == null) return null;
        try {
            m.setAccessible(true);
            return m.invoke(target, arg);
        } catch (Exception ignored) {
            return null;
        }
    }

    // ---- internal ----------------------------------------------------------

    private static Method findMethodInHierarchy(Class<?> clazz, String name, Class<?>... paramTypes) {
        for (Class<?> iface : collectAllInterfaces(clazz)) {
            try {
                return iface.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException ignored) { }
        }
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static List<Class<?>> collectAllInterfaces(Class<?> clazz) {
        List<Class<?>> all = new ArrayList<Class<?>>();
        while (clazz != null) {
            for (Class<?> iface : clazz.getInterfaces()) {
                collectInterfacesRecursive(iface, all);
            }
            clazz = clazz.getSuperclass();
        }
        return all;
    }

    private static void collectInterfacesRecursive(Class<?> iface, List<Class<?>> out) {
        if (out.contains(iface)) return;
        out.add(iface);
        for (Class<?> superIface : iface.getInterfaces()) {
            collectInterfacesRecursive(superIface, out);
        }
    }

    /**
     * Invokes a static method on a class loaded by name.
     */
    public static Object invokeStaticMethod(String className, String methodName,
                                             Class<?>[] paramTypes, Object[] args) {
        try {
            Class<?> clazz = Class.forName(className);
            java.lang.reflect.Method m = clazz.getDeclaredMethod(methodName, paramTypes);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (Exception ignored) {
            return null;
        }
    }
}
