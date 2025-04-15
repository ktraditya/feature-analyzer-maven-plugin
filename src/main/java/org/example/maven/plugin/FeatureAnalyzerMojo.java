package com.example.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Mojo(name = "analyzefeaturefiles", defaultPhase = LifecyclePhase.VERIFY)
public class FeatureAnalyzerMojo extends AbstractMojo {

    private static final int MAX_SCENARIOS_PER_FILE = 100;

    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private File baseDir;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            List<Path> featureFiles = findFeatureFiles(baseDir.toPath());

            boolean validationFailed = false;

            // Check for scenario count limit
            boolean tooManyScenarios = validateScenarioCounts(featureFiles);
            if (tooManyScenarios) validationFailed = true;

            // Check for duplicate feature descriptions
            Map<String, Set<String>> duplicateFeatures = findDuplicateFeatures(featureFiles);
            if (!duplicateFeatures.isEmpty()) {
                printDuplicates(duplicateFeatures, "Duplicate feature descriptions");
                validationFailed = true;
            }

            // Check for duplicate scenario names
            Map<String, Set<String>> duplicateScenarios = findDuplicateScenarios(featureFiles);
            if (!duplicateScenarios.isEmpty()) {
                printDuplicates(duplicateScenarios, "Duplicate scenario names");
                validationFailed = true;
            }

            if (validationFailed) {
                throw new MojoExecutionException("Feature file validation failed.");
            }

        } catch (IOException e) {
            throw new MojoExecutionException("Error reading feature files", e);
        }
    }

    private List<Path> findFeatureFiles(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(p -> p.toString().endsWith(".feature"))
                         .collect(Collectors.toList());
        }
    }

    private Map<String, Set<String>> findDuplicateFeatures(List<Path> featureFiles) {
        Map<String, Set<String>> featureMap = new HashMap<>();

        for (Path file : featureFiles) {
            try {
                Optional<String> featureLine = Files.lines(file)
                        .map(String::trim)
                        .filter(l -> l.startsWith("Feature:"))
                        .map(l -> l.substring("Feature:".length()).trim())
                        .findFirst();

                if (featureLine.isPresent()) {
                    String name = featureLine.get();
                    featureMap.computeIfAbsent(name, k -> new HashSet<>()).add(file.getFileName().toString());
                }

            } catch (IOException ignored) {}
        }

        return featureMap.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Map<String, Set<String>> findDuplicateScenarios(List<Path> featureFiles) {
        Map<String, Set<String>> scenarioMap = new HashMap<>();

        for (Path file : featureFiles) {
            try {
                List<String> lines = Files.readAllLines(file);
                for (String line : lines) {
                    line = line.trim();
                    if (line.startsWith("Scenario:") || line.startsWith("Scenario Outline:")) {
                        String name = line.substring(line.indexOf(":") + 1).trim();
                        if (!name.isEmpty()) {
                            scenarioMap.computeIfAbsent(name, k -> new HashSet<>()).add(file.getFileName().toString());
                        }
                    }
                }
            } catch (IOException ignored) {}
        }

        return scenarioMap.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private int countScenariosInFile(Path featureFile) {
        int scenarioCount = 0;
        try {
            List<String> lines = Files.readAllLines(featureFile);
            boolean inScenarioOutline = false;
            boolean inExamples = false;

            for (String line : lines) {
                line = line.trim();

                if (line.startsWith("Scenario:")) {
                    scenarioCount++;
                    inScenarioOutline = false;
                    inExamples = false;
                } else if (line.startsWith("Scenario Outline:")) {
                    inScenarioOutline = true;
                    inExamples = false;
                } else if (inScenarioOutline && line.startsWith("Examples:")) {
                    inExamples = true;
                } else if (inExamples && line.startsWith("|")) {
                    if (!line.toLowerCase().contains("example") && !line.toLowerCase().contains("name")) {
                        scenarioCount++;
                    }
                } else if (line.isEmpty()) {
                    inExamples = false;
                }
            }
        } catch (IOException ignored) {}

        return scenarioCount;
    }

    private boolean validateScenarioCounts(List<Path> featureFiles) {
        boolean hasViolations = false;

        for (Path file : featureFiles) {
            int count = countScenariosInFile(file);
            System.out.println("📄 " + file.getFileName() + " (Total Scenarios: " + count + ")");
            if (count > MAX_SCENARIOS_PER_FILE) {
                getLog().error("❌ Scenario count exceeded in file: " + file.getFileName() +
                        " (Count: " + count + ", Max allowed: " + MAX_SCENARIOS_PER_FILE + ")");
                hasViolations = true;
            }
        }

        return hasViolations;
    }

    private void printDuplicates(Map<String, Set<String>> map, String title) {
        getLog().error("🚨 " + title + ":");
        map.forEach((key, files) -> {
            getLog().error("- '" + key + "' in: " + String.join(", ", files));
        });
    }
}