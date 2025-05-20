package org.example.maven.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Mojo(name = "analyzefeaturefiles", defaultPhase = LifecyclePhase.VERIFY)
public class FeatureAnalyzerMojo extends AbstractMojo {

    // Configuration parameters
    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "src/test/resources")
    private String featuresDirectory;

    @Parameter(defaultValue = "100")
    private int maxScenariosPerFeature;

    // Emoji constants
    private static final String ERROR_EMOJI = "🚫 ";
    private static final String DUPLICATE_EMOJI = "🔁 ";
    private static final String FEATURE_EMOJI = "📜 ";
    private static final String SCENARIO_EMOJI = "📝 ";

    @Override
    public void execute() throws MojoExecutionException {
        try {
            ValidationResult result = new FeatureValidator()
                    .validateFeatures(featuresDirectory, maxScenariosPerFeature);

            if (result.hasErrors()) {
                result.getErrors().forEach(getLog()::error);
                throw new MojoExecutionException(ERROR_EMOJI + "Feature validation failed");
            }
        } catch (IOException e) {
            throw new MojoExecutionException(ERROR_EMOJI + "File error: " + e.getMessage(), e);
        }
    }

    // Core validation logic extracted to separate class
    private class FeatureValidator {
        ValidationResult validateFeatures(String featuresDir, int maxScenarios) throws IOException {
            ValidationResult result = new ValidationResult();
            List<Path> featureFiles = findFeatureFiles(featuresDir);

            if (featureFiles.isEmpty()) {
                getLog().warn("No feature files found");
                return result;
            }

            featureFiles.forEach(file -> processFeatureFile(file, maxScenarios, result));
            return result;
        }

        private List<Path> findFeatureFiles(String featuresDir) throws IOException {
            Path startPath = Paths.get(project.getBasedir().getAbsolutePath(), featuresDir);
            if (!Files.exists(startPath)) return Collections.emptyList();

            try (Stream<Path> paths = Files.walk(startPath)) {
                return paths.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".feature"))
                        .collect(Collectors.toList());
            }
        }

        private void processFeatureFile(Path file, int maxScenarios, ValidationResult result) {
            try {
                FeatureFileStats stats = new FeatureFileParser().parse(file);
                validateFeature(file, stats, maxScenarios, result);
            } catch (IOException e) {
                result.addError("Failed to parse: " + file, e);
            }
        }

        private void validateFeature(Path file, FeatureFileStats stats, int maxScenarios, ValidationResult result) {
            result.trackFeature(stats.featureDescription, file);
            stats.scenarioNames.forEach(name -> result.trackScenario(name, file));

            if (stats.scenarioCount > maxScenarios) {
                result.addError(ERROR_EMOJI + String.format(
                        "Scenario limit exceeded in %s (%d > %d)",
                        file.getFileName(), stats.scenarioCount, maxScenarios));
            }
        }
    }

    // Statistics tracking
    private static class FeatureFileStats {
        int scenarioCount;
        String featureDescription;
        final List<String> scenarioNames = new ArrayList<>();
    }

    // File parsing logic
    private static class FeatureFileParser {
        FeatureFileStats parse(Path file) throws IOException {
            FeatureFileStats stats = new FeatureFileStats();
            ParseState state = new ParseState();

            Files.lines(file)
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .forEach(line -> processLine(line, stats, state));

            return stats;
        }

        private void processLine(String line, FeatureFileStats stats, ParseState state) {
            if (line.startsWith("#") || line.startsWith("@")) return;

            if (line.startsWith("Feature:")) {
                stats.featureDescription = line.substring("Feature:".length()).trim();
            }
            else if (line.startsWith("Scenario")) {
                handleScenario(line, stats, state);
            }
            else if (state.inExamples && line.startsWith("|") && line.endsWith("|")) {
                handleExampleLine(state, stats);
            }
            else {
                state.inExamples = line.startsWith("Examples:");
            }
        }

        private void handleScenario(String line, FeatureFileStats stats, ParseState state) {
            String prefix = line.contains("Outline") ? "Scenario Outline:" : "Scenario:";
            stats.scenarioNames.add(line.substring(prefix.length()).trim());
            stats.scenarioCount++;
            state.reset();
        }

        private void handleExampleLine(ParseState state, FeatureFileStats stats) {
            if (!state.examplesHeader) stats.scenarioCount++;
            state.examplesHeader = false;
        }
    }

    // Parse state tracker
    private static class ParseState {
        boolean inExamples = false;
        boolean examplesHeader = true;

        void reset() {
            inExamples = false;
            examplesHeader = true;
        }
    }

    // Result container
    private static class ValidationResult {
        private final Map<String, List<String>> features = new HashMap<>();
        private final Map<String, List<String>> scenarios = new HashMap<>();
        private final List<String> errors = new ArrayList<>();

        void trackFeature(String description, Path file) {
            if (description != null) {
                features.computeIfAbsent(description, k -> new ArrayList<>())
                        .add(file.getFileName().toString());
            }
        }

        void trackScenario(String name, Path file) {
            scenarios.computeIfAbsent(name, k -> new ArrayList<>())
                    .add(file.getFileName().toString());
        }

        void addError(String message) {
            errors.add(message);
        }

        void addError(String message, Exception e) {
            errors.add(message + ": " + e.getMessage());
        }

        boolean hasErrors() {
            checkDuplicates();
            return !errors.isEmpty();
        }

        List<String> getErrors() {
            return errors;
        }

        private void checkDuplicates() {
            features.forEach((desc, files) -> {
                if (files.size() > 1) {
                    errors.add(ERROR_EMOJI + DUPLICATE_EMOJI + FEATURE_EMOJI +
                            String.format("'%s' (duplicated %d times)", desc, files.size()));
                }
            });

            scenarios.forEach((name, files) -> {
                if (files.size() > 1) {
                    errors.add(ERROR_EMOJI + DUPLICATE_EMOJI + SCENARIO_EMOJI +
                            String.format("'%s' (duplicated %d times)", name, files.size()));
                }
            });
        }
    }
}