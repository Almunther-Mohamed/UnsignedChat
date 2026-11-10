package me.almunther.unsignedchat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Small reflection helpers. Everything here is tolerant: a missing class or member yields null, never a crash. */
public final class Reflect {

    private Reflect() {
    }

    public static Class<?> firstClass(ClassLoader loader, String... names) {
        for (String name : names) {
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException | LinkageError ignored) {
                // try next candidate
            }
        }
        return null;
    }

    /** Finds a field in the class or any superclass and makes it accessible. */
    public static Field field(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException | RuntimeException ignored) {
                // keep looking
            }
        }
        return null;
    }

    /** Finds a method (public or not) in the class or any superclass and makes it accessible. */
    public static Method method(Class<?> type, String name, Class<?>... params) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException | RuntimeException ignored) {
                // keep looking
            }
        }
        return null;
    }
}
