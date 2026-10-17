package com.inkcontrol.features.lock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class Reflect {
    static Object get(Object object, String name) {
        if (object == null) return null;
        for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(object); }
            catch (ReflectiveOperationException ignored) { }
        }
        return null;
    }
    static void set(Object object, String name, Object value) {
        for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); f.set(object, value); return; }
            catch (ReflectiveOperationException ignored) { }
        }
    }
    static Object call(Object object, String name, Class<?>[] types, Object... args) throws Exception {
        for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) {
            try { Method m = c.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(object, args); }
            catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(name);
    }
}
