package de.flapdoodle.embed.mongo.distribution;

import org.junit.Test;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class LinuxDistroTest {

    @Test
    public void debian9_2() throws Exception {
        assertEquals(LinuxDistro.DEBIAN_9_2, LinuxDistro.detect(getPath("os-release-debian9_2.txt")));
    }

    @Test
    public void debian10() throws Exception {
        assertEquals(LinuxDistro.DEBIAN_10, LinuxDistro.detect(getPath("os-release-debian10.txt")));
    }

    private Path getPath(String resourceStr) throws URISyntaxException {
        URL url = getClass().getClassLoader().getResource(resourceStr);
        return Paths.get(url.toURI());
    }
}