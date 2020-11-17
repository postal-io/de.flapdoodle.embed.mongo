package de.flapdoodle.embed.mongo.distribution;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public enum LinuxDistro {
    DEBIAN_9_2,
    DEBIAN_10,
    UNKNOWN;

    private static Logger logger = LoggerFactory.getLogger(LinuxDistro.class);

    public static LinuxDistro detect() {
        Path path = Path.of("/etc/os-release");

        try {
            return detect(path);
        } catch (IOException e) {
            logger.warn("Could not read file: " + path, e);
            return LinuxDistro.UNKNOWN;
        }
    }

    static LinuxDistro detect(Path path) throws IOException {
        final boolean isDebian;
        final String versionIdStr;

        try (Reader reader = Files.newBufferedReader(path)) {
            Properties properties = new Properties();
            properties.load(reader);

            isDebian = "debian".equals(properties.getProperty("ID"));
            versionIdStr = StringUtils.unwrap(properties.getProperty("VERSION_ID"), '\"');
        }

        if (isDebian) {
            switch (versionIdStr) {
                case "9.2":
                    return DEBIAN_9_2;

                case "10":
                    return DEBIAN_10;
            }
        }

        return LinuxDistro.UNKNOWN;
    }
}
