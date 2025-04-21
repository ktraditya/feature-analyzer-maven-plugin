package org.example.maven.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Mojo(name = "analyzefeaturefiles", defaultPhase = LifecyclePhase.VERIFY)
public class FeatureAnalyzerMojo extends AbstractMojo {

    // Emoji Constants
    private static final String FEATURE_EMOJI = "\uD83D\uDCDC "; // 📜
    private static final String SCENARIO_EMOJI = "\uD83D\uDCDD "; // 📝
    private static final String ERROR_EMOJI = "\uD83D\uDEAB "; // 🚫
    private static final String FILES_EMOJI = "\uD83D\uDCC2 "; // 📂
    private static final String DUPLICATE_EMOJI = "\uD83D\uDD01 "; // 🔁
    private static final String SUCCESS_EMOJI = "\uD83C\uDF89 "; // 🎉
    private static final String CHECK_EMOJI = "\u2705 "; // ✅
    private static final String WARNING_EMOJI = "\u26A0\uFE0F "; // ⚠️

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "src/test/resources", property = "featuresDirectory")
    private String featuresDirectory;

    @Parameter(defaultValue = "100", property = "maxScenariosPerFeature")
    private int maxScenariosPerFeature;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        printBanner("STARTING FEATURE ANALYSIS", "\uD83D\uDD0E "); // 🔎

        try {
            List<Path> featureFiles = findFeatureFiles();
            if (featureFiles.isEmpty()) {
                getLog().warn(WARNING_EMOJI + "No feature files found in " + featuresDirectory);
                return;
            }

            Map<String, List<String>> featureDescriptions = new HashMap<>();
            Map<String, List<String>> scenarioNames = new HashMap<>();
            boolean validationPassed = true;

            for (Path featureFile : featureFiles) {
                FeatureFileStats stats = analyzeFeatureFile(featureFile, featureDescriptions, scenarioNames);
                logFeatureStats(featureFile, stats);

                if (stats.scenarioCount > maxScenariosPerFeature) {
                    validationPassed = false;
                    getLog().error(ERROR_EMOJI + String.format(
                            "Scenario limit exceeded! %d > %d (max)",
                            stats.scenarioCount, maxScenariosPerFeature));
                }
            }

            if (!checkForDuplicates(featureDescriptions, scenarioNames)) {
                validationPassed = false;
            }

            if (validationPassed) {
                printSuccessBanner();
            } else {
                throw new MojoExecutionException("Feature validation failed");
            }

        } catch (IOException e) {
            getLog().error(ERROR_EMOJI + "File reading error: " + e.getMessage());
            throw new MojoExecutionException("Error reading feature files", e);
        }
    }

    private static class FeatureFileStats {
        int scenarioCount;
        List<String> scenarioNames = new ArrayList<>();
    }

    private FeatureFileStats analyzeFeatureFile(Path featureFile,
                                                Map<String, List<String>> featureDescriptions,
                                                Map<String, List<String>> scenarioNames)
            throws IOException {
        FeatureFileStats stats = new FeatureFileStats();
        List<String> lines = Files.readAllLines(featureFile);
        boolean inExamples = false;
        int examplesStartLine = -1;
        String currentFeature = null;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();

            if (line.startsWith("Feature:")) {
                currentFeature = line.substring("Feature:".length()).trim();
                trackDescription(featureDescriptions, currentFeature, featureFile.getFileName().toString());
            }
            else if (line.startsWith("Scenario:") && currentFeature != null) {
                stats.scenarioCount++;
                String scenarioName = line.substring("Scenario:".length()).trim();
                trackScenario(scenarioNames, scenarioName, featureFile.getFileName().toString());
                stats.scenarioNames.add(scenarioName);
                inExamples = false;
            }
            else if (line.startsWith("Scenario Outline:") && currentFeature != null) {
                String scenarioName = line.substring("Scenario Outline:".length()).trim();
                trackScenario(scenarioNames, scenarioName, featureFile.getFileName().toString());
                stats.scenarioNames.add(scenarioName);
                inExamples = false;
                examplesStartLine = -1;
            }
            else if (line.startsWith("Examples:") && currentFeature != null) {
                inExamples = true;
                examplesStartLine = i;
            }
            else if (inExamples && line.startsWith("|") && line.endsWith("|")) {
                if (i == examplesStartLine + 1) continue; // Skip header
                stats.scenarioCount++;
            }
            else if (!line.isEmpty() && !line.startsWith("#") && !line.startsWith("@")) {
                inExamples = false;
            }
        }

        return stats;
    }

    private void logFeatureStats(Path featureFile, FeatureFileStats stats) {
        getLog().info(CHECK_EMOJI + String.format("%s - %d scenarios",
                featureFile.getFileName(), stats.scenarioCount));

        if (stats.scenarioCount > maxScenariosPerFeature * 0.8) {
            getLog().warn(WARNING_EMOJI + String.format(
                    "Warning: Feature file is approaching limit (%d/%d scenarios)",
                    stats.scenarioCount, maxScenariosPerFeature));
        }
    }

    private List<Path> findFeatureFiles() throws IOException {
        Path startPath = Paths.get(project.getBasedir().getAbsolutePath(), featuresDirectory);
        if (!Files.exists(startPath)) {
            return new ArrayList<>();
        }
        try (Stream<Path> paths = Files.walk(startPath)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".feature"))
                    .collect(Collectors.toList());
        }
    }

    private boolean checkForDuplicates(Map<String, List<String>> featureDescriptions,
                                       Map<String, List<String>> scenarioNames) {
        boolean hasErrors = false;

        // Check feature duplicates
        for (Map.Entry<String, List<String>> entry : featureDescriptions.entrySet()) {
            if (entry.getValue().size() > 1) {
                hasErrors = true;
                getLog().error(ERROR_EMOJI + DUPLICATE_EMOJI + FEATURE_EMOJI +
                        String.format("'%s' in files: %s",
                                entry.getKey(), FILES_EMOJI + String.join(", ", entry.getValue())));
            }
        }

        // Check scenario duplicates
        for (Map.Entry<String, List<String>> entry : scenarioNames.entrySet()) {
            if (entry.getValue().size() > 1) {
                hasErrors = true;
                getLog().error(ERROR_EMOJI + DUPLICATE_EMOJI + SCENARIO_EMOJI +
                        String.format("'%s' in files: %s",
                                entry.getKey(), FILES_EMOJI + String.join(", ", entry.getValue())));
            }
        }

        return !hasErrors;
    }

    private void trackDescription(Map<String, List<String>> map, String description, String fileName) {
        map.computeIfAbsent(description, k -> new ArrayList<>()).add(fileName);
    }

    private void trackScenario(Map<String, List<String>> map, String scenarioName, String fileName) {
        map.computeIfAbsent(scenarioName, k -> new ArrayList<>()).add(fileName);
    }

    private void printBanner(String title, String emoji) {
        String border = "═".repeat(title.length() + 4);
        getLog().info("\n╔" + border + "╗\n║ " + emoji + title + " ║\n╚" + border + "╝");
    }

    private void printSuccessBanner() {
        getLog().info("\n" +
                "╔══════════════════════════════════════╗\n" +
                "║          " + SUCCESS_EMOJI + " VALIDATION PASSED " + SUCCESS_EMOJI + "         ║\n" +
                "╠══════════════════════════════════════╣\n" +
                "║ All feature files validated          ║\n" +
                "║ " + CHECK_EMOJI + " No duplicates found              ║\n" +
                "╚══════════════════════════════════════╝");
    }
}