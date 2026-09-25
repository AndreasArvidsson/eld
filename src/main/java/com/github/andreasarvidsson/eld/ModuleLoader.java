package com.github.andreasarvidsson.eld;

import java.util.HashMap;
import java.util.Map;
import com.github.andreasarvidsson.eld.runtime.RuntimeAbi;

final class ModuleLoader extends ClassLoader {
    private final Map<String, byte[]> definitions = new HashMap<>();

    public ModuleLoader() {
        super(ClassLoader.getPlatformClassLoader());
    }

    public void add(final Map<String, byte[]> classes) {
        definitions.putAll(classes);
    }

    @Override
    protected Class<?> findClass(final String name)
        throws ClassNotFoundException {
        for (final Class<?> runtimeClass : RuntimeAbi.runtimeClasses()) {
            if (name.equals(runtimeClass.getName())) {
                return runtimeClass;
            }
        }
        final byte[] bytes = definitions.remove(name);
        if (bytes == null) {
            throw new ClassNotFoundException(name);
        }
        return defineClass(name, bytes, 0, bytes.length);
    }
}
