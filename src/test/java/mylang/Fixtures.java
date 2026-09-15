package mylang;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import java.util.regex.Pattern;

public class Fixtures {
    private final static String FIXTURE_EXTENSION = ".fixture";
    private final static String SUBSET_ARGUMENT = "testSubset";
    private final static String SUBSET_FILE = "testSubsetGrep.properties";

    static public List<@NonNull Fixture> getFixtures() throws IOException {
        final Path directory = Path.of(
                System.getProperty("basedir", "."),
                "src", "test", "resources", "fixtures");

        final List<@NonNull Fixture> fixtures = new ArrayList<>();

        try (final var paths = Files.walk(directory)) {
            paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(FIXTURE_EXTENSION))
                    .sorted()
                    .forEach(path -> {
                        final String filename = path.getFileName().toString();
                        final String name = Objects
                                .requireNonNull(filename.substring(0, filename.length() - FIXTURE_EXTENSION.length()));
                        fixtures.add(new Fixture(path, name));
                    });
        }

        final @Nullable Pattern subsetRegex = getSubsetRegex();

        if (subsetRegex == null) {
            return fixtures;
        }

        return Objects.requireNonNull(
                fixtures.stream()
                        .filter(fixture -> subsetRegex.matcher(fixture.name()).find())
                        .toList());

    }

    static private @Nullable Pattern getSubsetRegex() throws IOException {
        if (!Boolean.getBoolean(SUBSET_ARGUMENT)) {
            return null;
        }

        try (final InputStream input = Fixtures.class.getResourceAsStream("/" + SUBSET_FILE)) {
            if (input == null) {
                throw new IOException(String.format("Subset file '%s' not found", SUBSET_FILE));
            }

            final String subsetFileContent = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            final String[] subsetPatterns = subsetFileContent.lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .toArray(String[]::new);

            if (subsetPatterns.length == 0) {
                return null;
            }

            return Pattern.compile(String.join("|", subsetPatterns));
        }
    }
}
