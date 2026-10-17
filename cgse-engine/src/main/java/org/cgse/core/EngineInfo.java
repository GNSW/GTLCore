// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Stable bootstrap API; independent of the solver and adapter classes. */
public final class EngineInfo {
    private static final Properties METADATA = readMetadata();

    private EngineInfo() {}

    public static String version() {
        return METADATA.getProperty("version", "unknown");
    }

    public static String compatibility() {
        return METADATA.getProperty("compatibility", "unknown");
    }

    public static String revision() {
        return METADATA.getProperty("revision", "unknown");
    }

    public static void requireCompatibility(String expected) {
        if (!expected.equals(compatibility())) {
            throw new IllegalStateException("CGSE " + version() + " has API " + compatibility()
                    + "; this integration requires " + expected + ". Update the integration or remove the incompatible CGSE mod.");
        }
    }

    private static Properties readMetadata() {
        Properties properties = new Properties();
        try (InputStream input = EngineInfo.class.getResourceAsStream("/META-INF/cgse/engine.properties")) {
            if (input != null) properties.load(input);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        return properties;
    }
}
