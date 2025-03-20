package org.example.maven.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Mojo to analyze feature files and detect duplicate features, scenarios, and empty table cells.
 */
@Mojo(name = "analyzefeaturefiles")
public class FeatureAnalyzerMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject mavenProject;

    /**
     * If true, the build will fail when validation errors are found.
     */
    @Parameter(property = "failOnValidationErrors", defaultValue = "true")
    private boolean failOnValidationErrors;

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("🔍 Scanning for feature files...");

        Path projectDir = Paths.get(mavenProject.getBasedir().getAbsolutePath());

        List<Path> featureFiles;
        try (Stream<Path> paths = Files.walk(projectDir)) {
            featureFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".feature")) // Only .feature files
                    .filter(p -> !p.toString().contains("target") && !p.toString().contains(".git") && !p.toString().contains("node_modules")) // Exclude unnecessary directories
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new MojoExecutionException("Error scanning feature files", e);
        }

        if (featureFiles.isEmpty()) {
            getLog().warn("⚠ No feature files found.");
            return;
        }

        getLog().info("✅ Found " + featureFiles.size() + " feature files.");
        featureFiles.forEach(file -> getLog().info("   ➜ " + file.getFileName()));

        // Analyze feature files
        Map<String, List<String>> duplicateFeatures = findDuplicateFeatures(featureFiles);
        Map<String, List<String>> duplicateScenarios = findDuplicateScenarios(featureFiles);
        List<String> emptyCellIssues = findEmptyCellsInTables(featureFiles);

        boolean hasErrors = false;

        // Print duplicate features
        if (!duplicateFeatures.isEmpty()) {
            hasErrors = true;
            getLog().error("🚨 Duplicate feature descriptions found:");
            for (Map.Entry<String, List<String>> entry : duplicateFeatures.entrySet()) {
                String fileList = String.join(", ", new HashSet<>(entry.getValue()).stream()
                        .map(f -> Paths.get(f).getFileName().toString())  // Print file name only
                        .collect(Collectors.toList()));
                getLog().error("   ❌ Feature: \"" + entry.getKey() + "\" found in files: " + fileList);
            }
        }

        // Print duplicate scenarios
        if (!duplicateScenarios.isEmpty()) {
            hasErrors = true;
            getLog().error("🚨 Duplicate scenarios found:");
            for (Map.Entry<String, List<String>> entry : duplicateScenarios.entrySet()) {
                String fileList = String.join(", ", new HashSet<>(entry.getValue()).stream()
                        .map(f -> Paths.get(f).getFileName().toString())  // Print file name only
                        .collect(Collectors.toList()));
                getLog().error("   ❌ Scenario: \"" + entry.getKey() + "\" found in files: " + fileList);
            }
        }

        // Print empty cell issues
        if (!emptyCellIssues.isEmpty()) {
            hasErrors = true;
            getLog().error("⚠️ Empty cell values found in data tables:");
            emptyCellIssues.forEach(issue -> getLog().error("   ❌ " + issue));
        }

        if (hasErrors && failOnValidationErrors) {
            throw new MojoExecutionException("❌ Feature analysis failed due to validation errors.");
        } else if (!hasErrors) {
            getLog().info("✅ No validation issues found.");
        }
    }

    /**
     * Finds duplicate feature descriptions across feature files.
     */
    private Map<String, List<String>> findDuplicateFeatures(List<Path> featureFiles) throws MojoExecutionException {
        Map<String, List<String>> featureOccurrences = new HashMap<>();

        for (Path file : featureFiles) {
            try {
                List<String> lines = Files.readAllLines(file);
                for (String line : lines) {
                    line = line.trim();
                    if (line.startsWith("Feature:")) {
                        String featureName = line.replaceFirst("Feature:", "").trim();
                        featureOccurrences
                                .computeIfAbsent(featureName, k -> new ArrayList<>())
                                .add(file.toString());
                    }
                }
            } catch (IOException e) {
                throw new MojoExecutionException("Error reading file: " + file, e);
            }
        }

        // Return only duplicates
        return featureOccurrences.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Finds duplicate scenario names across feature files.
     */
    private Map<String, List<String>> findDuplicateScenarios(List<Path> featureFiles) throws MojoExecutionException {
        Map<String, List<String>> scenarioOccurrences = new HashMap<>();

        for (Path file : featureFiles) {
            try {
                List<String> lines = Files.readAllLines(file);
                for (String line : lines) {
                    line = line.trim();
                    if (line.startsWith("Scenario:") || line.startsWith("Scenario Outline:")) {
                        String scenarioName = line.replaceFirst("Scenario( Outline)?:", "").trim();
                        scenarioOccurrences
                                .computeIfAbsent(scenarioName, k -> new ArrayList<>())
                                .add(file.toString());
                    }
                }
            } catch (IOException e) {
                throw new MojoExecutionException("Error reading file: " + file, e);
            }
        }

        // Return only duplicates
        return scenarioOccurrences.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Finds empty cells in data tables and example tables.
     */
    private List<String> findEmptyCellsInTables(List<Path> featureFiles) throws MojoExecutionException {
        List<String> emptyCellIssues = new ArrayList<>();

        for (Path file : featureFiles) {
            try {
                List<String> lines = Files.readAllLines(file);
                boolean inTable = false;

                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).trim();

                    if (line.startsWith("|")) {  // Inside a table
                        inTable = true;
                        String[] cells = line.split("\\|");
                        for (int j = 1; j < cells.length - 1; j++) { // Skip first and last empty splits
                            if (cells[j].trim().isEmpty()) {
                                emptyCellIssues.add("Empty cell found in file: " + file.getFileName() + " at line " + (i + 1));
                            }
                        }
                    } else {
                        inTable = false;  // Reset when table ends
                    }
                }
            } catch (IOException e) {
                throw new MojoExecutionException("Error reading file: " + file, e);
            }
        }

        return emptyCellIssues;
    }
}
