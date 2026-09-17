package com.github.andreasarvidsson.eld;

import java.util.HashMap;
import java.util.Map;

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
        if (name.equals(RuntimeAbi.INT_ARRAY_CLASS.getName())) {
            return RuntimeAbi.INT_ARRAY_CLASS;
        }
        final byte[] bytes = definitions.remove(name);
        if (bytes == null) {
            throw new ClassNotFoundException(name);
        }
        return defineClass(name, bytes, 0, bytes.length);
    }
}
