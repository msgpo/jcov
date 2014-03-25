/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.sun.tdk.jcov.instrument;

import com.sun.tdk.jcov.JREInstr;
import com.sun.tdk.jcov.runtime.PropertyFinder;
import java.io.IOException;
import java.io.InputStream;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * <p> Extention to ClassWriter where getCommonSuperClass() is overridden. </p>
 * <p> It's possible to set custom classfile extension by "clext" property
 * (through jcov.clext sys property, JCOV_CLEXT env variable and so on) - eg
 * ".clazz". </p> <p> Note that "class" extension has always more priority so
 * "Boo.class" will be loaded instead of "Boo.custom". </p>
 *
 * @author Dmitry Fazunenko
 */
public class OverriddenClassWriter extends ClassWriter {

    private final ClassLoader loader;
    private static String[] customExtentions = PropertyFinder.findValue("clext", "").split(":");

    public OverriddenClassWriter(final ClassReader classReader, final int flags, ClassLoader loader) {
        super(classReader, flags);
        this.loader = loader;
    }

    /**
     * Overridden implementation which doesn't use Class.forName()
     *
     * @param type1
     * @param type2
     * @return
     */
    @Override
    protected String getCommonSuperClass(final String type1, final String type2) {
        return getCommonSuperClassAlt(type1, type2, loader);
    }

    static String getCommonSuperClassAlt(final String type1, final String type2, ClassLoader loader) {
        if (isAssignableFrom(type2, type1, loader)) {
            return type2;
        }

        String type = type1;
        while (!isAssignableFrom(type, type2, loader)) {
            type = getSuperClass(type, loader);
        }
        return type;
    }
    /**
     * Map: class name --> super class name
     */
    private static final HashMap<String, String> class_superclass =
            new HashMap<String, String>();
    /**
     * Map: class name --> implemented interfaces
     */
    private static final HashMap<String, List<String>> class_interfaces =
            new HashMap<String, List<String>>();
    /**
     * The empty list object, to avoid multiple creation of empty lists
     */
    private static final List<String> EMPTY_LIST = new ArrayList<String>();

    /**
     * Cleans collected maps.
     */
    public static void clean() {
        class_superclass.clear();
        class_interfaces.clear();
    }

    /**
     * Analog of Class.isAssignableFrom(class2)
     *
     * @return true if type t1 is assignable from t2.
     */
    public static boolean isAssignableFrom(String t1, String t2, final ClassLoader loader) {
        if (t1 == null) {
            throw new RuntimeException("Can't read superclass bytecode. Please add it to the classpath. ");
        }

        if (t1.equals(t2)) {
            return true;
        }
        if (t2 == null || t2.equals("java/lang/Object")) {
            return false;
        }

        String superType = getSuperClass(t2, loader); // init interfaces for t2!
        List<String> t2Interfaces = class_interfaces.get(t2);
        if (t2Interfaces != null && !t2Interfaces.isEmpty()) {
            for (String in2 : t2Interfaces) {
                if (t1.equals(in2)) {
                    return true;
                }
            }
        }

        return isAssignableFrom(t1, superType, loader);
    }

    /**
     * Returns super class of a given class. For the sake of performance
     * returned values are hashed. Method also invokes detectInterfaces() to
     * calculate implemented interfaces.
     *
     * @param clName
     * @return
     */
    public static String getSuperClass(final String clName, final ClassLoader loader) {
        if (clName == null) {
            return null;
        }
        String loaded = class_superclass.get(clName);
        if (loaded != null) {
            return loaded;
        }
        try {
            ClassReader cr = openClassReader(clName, loader);

            String superName = cr.getSuperName();
            class_superclass.put(clName, superName);
            detectInterfaces(clName, cr, loader);
            return superName;
        } catch (IOException e) {
            System.err.println("Failed to read class: " + clName + ". Reason: " + e.getMessage());
        }
        return null;
    }

    /**
     * Detects interfaces all implemented by the given class. The method updates
     * class_interfaces map. For new loaded interfaces class_superclass map is
     * also updated to avoid multiple loading.
     *
     * @param clName
     * @param cr
     * @throws IOException
     */
    static void detectInterfaces(String clName, ClassReader cr, final ClassLoader loader)
            throws IOException {
        if (class_interfaces.get(clName) != null) {
            return;
        }
        if (cr == null) {
            cr = openClassReader(clName, loader);
            String superName = cr.getSuperName();
            class_superclass.put(clName, superName);
        }

        ArrayList list = new ArrayList();
        String[] interfaces = cr.getInterfaces();
        if (interfaces != null) {
            for (String itf : interfaces) {
                list.add(itf);
                detectInterfaces(itf, null, loader);
                list.addAll(class_interfaces.get(itf));
            }
        }
        if (list.isEmpty()) {
            class_interfaces.put(clName, EMPTY_LIST);
        } else {
            class_interfaces.put(clName, list);
        }
    }

    /**
     * Creates ClassReader instance for <b>clName</b> class existing in
     * <b>loader</b> ClassLoader
     *
     * @param clName class to read
     * @param loader loader of the clName class
     * @return ClassReader
     * @throws IOException when class can not be read (not found in loader or
     * can't be read by ClassReader)
     */
    public static ClassReader openClassReader(final String clName, final ClassLoader loader) throws IOException {

        if (loader instanceof JREInstr.StaticJREInstrClassLoader) {
            InputStream in = getInputStreamForName(clName, loader, false, ".class");

            if (in == null) {
                in = getInputStreamForName(clName, ClassLoader.getSystemClassLoader(), false, ".class");

                if (in == null) {
                    throw new IOException("Can't read class " + clName + " from classloader " + loader);
                }

                return new OffsetLabelingClassReader(in);
            }

            return new OffsetLabelingClassReader(in);
        }

        InputStream in = getInputStreamForName(clName, ClassLoader.getSystemClassLoader(), false, ".class");

        if (in == null) {

            in = getInputStreamForName(clName, ClassLoader.getSystemClassLoader(), false, ".clazz");

            if (in == null) {
                if (!ClassLoader.getSystemClassLoader().equals(loader)) {
                    in = getInputStreamForName(clName, loader, false, ".class");
                    if (in != null) {
                        return new OffsetLabelingClassReader(in);
                    } else {
                        throw new IOException("Can't read class " + clName + " from classloader " + loader);
                    }
                }

                throw new IOException("Can't read class " + clName + " from classloader " + loader);
            }

        }

        return new OffsetLabelingClassReader(in);
    }

    /**
     *
     * @param name
     * @param loader
     * @param priveleged if false - will try to use doPriveleged() mode
     * @return
     */
    private static InputStream getInputStreamForName(final String name, final ClassLoader loader, boolean priveleged, final String ext) {
        try {
            InputStream in = loader.getResourceAsStream(name + ext);
            if (in != null) {
                return in;
            }
        } catch (Throwable ignore) {
        }

        for (int i = 0; i < customExtentions.length; ++i) {
            try {
                InputStream in = loader.getResourceAsStream(name + "." + customExtentions[i]);
                if (in != null) {
                    return in;
                }
            } catch (Throwable ignore) {
            }
        }

        // trying to get class with priveleges
        if (!priveleged) {
            return AccessController.doPrivileged(new PrivilegedAction<InputStream>() {
                public InputStream run() {
                    return getInputStreamForName(name, loader, true, ext);
                }
            });
        }

        return null;
    }
}