package com.example.demo;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * REST Controller for handling course matching operations.
 * Processes course information and finds similar courses based on semantic similarity.
 */
@RestController
@RequestMapping("/api/v1")
public class ChatController {
    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final DocumentLoader documentLoader;
    private final LlamaReviewService llamaReviewService;

    // Subject groups for better matching
    private static final Map<String, Set<String>> RELATED_SUBJECTS = Map.of(
            "COMPUTER_SCIENCE", Set.of("COMPUTER_ENGINEERING", "SOFTWARE_ENGINEERING", "INFORMATION_TECHNOLOGY"),
            "ENGINEERING", Set.of("PHYSICS", "MATHEMATICS", "CHEMISTRY", "COMPUTER_ENGINEERING"),
            "MATHEMATICS", Set.of("APPLIED_MATH", "STATISTICS", "NUMERICAL_MATH", "OPERATIONS_RESEARCH"),
            "PRACTICAL_TRAINING", Set.of("WORK_EXPERIENCE", "INTERNSHIP"),
            "BUSINESS_MANAGEMENT", Set.of("ECONOMICS", "MANAGEMENT")
    );

    public ChatController(EmbeddingStore<TextSegment> embeddingStore,
                          EmbeddingModel embeddingModel,
                          DocumentLoader documentLoader, LlamaReviewService llamaReviewService) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.documentLoader = documentLoader;
        this.llamaReviewService = llamaReviewService;
    }

    // ================== Public Endpoints ================== //

    @GetMapping("/load")
    public ResponseEntity<String> loadCourses() {
        try {
            documentLoader.loadDocument();
            return ResponseEntity.ok("Courses loaded successfully");
        } catch (IOException e) {
            logger.error("Failed to load courses", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to load courses: " + e.getMessage());
        }
    }

    @GetMapping("/chat")
    public String chat(@RequestParam("userQuery") String userQuery) {
        try {
            return processUserQuery(userQuery);
        } catch (Exception e) {
            logger.error("Processing failed", e);
            return "Error: " + e.getMessage();
        }
    }

    // ================== Core Processing Methods ================== //

    /**
     * Processes multi-line user query and returns formatted results
     */
    private String processUserQuery(String userQuery) {
        return Arrays.stream(userQuery.split("\\r?\\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(this::processCourseLine)
                .filter(response -> !response.isEmpty())
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * Processes a single course line through the full pipeline
     */
    private String processCourseLine(String courseLine) {
        try {
            CourseInfo courseInfo = parseCourseLine(courseLine);
            List<EmbeddingMatch<TextSegment>> matches = findSimilarCourses(courseInfo);
            return formatCourseResponse(courseInfo, matches);
        } catch (Exception e) {

            logger.warn("Failed to process line: '{}'", courseLine, e);
            return formatProcessingError(courseLine, e.getMessage());
        }
    }

    // ================== Course Matching Logic ================== //

    /**
     * Finds similar courses using semantic search
     */
    private List<EmbeddingMatch<TextSegment>> findSimilarCourses(CourseInfo course) {
        String query = buildSemanticQuery(course);
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(queryEmbedding, 15);
        return filterAndScoreMatches(matches, course);
    }

    /**
     * Builds enhanced query for semantic search
     */
    private String buildSemanticQuery(CourseInfo course) {
        String subject = documentLoader.loadSubjectMapping()
                .getOrDefault(course.subjectCode(), "GENERAL");

        return String.format(
                "COURSE: %s | SUBJECT: %s | FIELD: %s | CREDITS: %d",
                normalizeText(course.title()),
                subject,
                getAcademicField(course.subjectCode()),
                course.credits()
        );
    }

    /**
     * Filters and scores matches based on relevance
     */
    private List<EmbeddingMatch<TextSegment>> filterAndScoreMatches(
            List<EmbeddingMatch<TextSegment>> matches, CourseInfo course) {

        String targetSubject = documentLoader.loadSubjectMapping()
                .getOrDefault(course.subjectCode(), "GENERAL");

        return matches.stream()
                .map(match -> {
                    String text = match.embedded().text();
                    String matchSubject = extractField(text, "SUBJECT");
                    int matchedCredits = Integer.parseInt(extractField(text, "CREDITS"));

                    // Calculate scoring factors
                    double subjectBoost = calculateSubjectBoost(matchSubject, targetSubject);
                    double creditFactor = calculateCreditFactor(matchedCredits, course.credits());
                    double titleSimilarity = calculateTitleSimilarity(
                            extractField(text, "TITLE"),
                            course.title()
                    );

                    return new EmbeddingMatch<>(
                            match.score() * subjectBoost * creditFactor * titleSimilarity,
                            match.embeddingId(),
                            match.embedding(),
                            match.embedded()
                    );
                })
                .filter(match -> {
                    String text = match.embedded().text();
                    String matchSubject = extractField(text, "SUBJECT");
                    int matchedCredits = Integer.parseInt(extractField(text, "CREDITS"));

                    return !isClearlyIrrelevant(matchSubject, targetSubject, text) &&
                            isCreditDifferenceAcceptable(matchedCredits, course.credits(), targetSubject);
                })
                .sorted(Comparator.comparingDouble(match -> -match.score()))
                .limit(3)
                .collect(Collectors.toList());
    }

    /**
     * Calculates subject relevance boost factor
     */
    private double calculateSubjectBoost(String matchSubject, String targetSubject) {
        if (matchSubject.equals(targetSubject)) {
            return 1.5; // Exact match boost
        }
        if (isRelatedSubject(matchSubject, targetSubject)) {
            return 1.2; // Related subject boost
        }
        // Penalize mismatches between theoretical and practical subjects
        if (matchSubject.equals("PRACTICAL_TRAINING") || targetSubject.equals("PRACTICAL_TRAINING")) {
            return 0.3;
        }
        return 0.7; // Penalty for unrelated subjects
    }

    // ================== Course Parsing Methods ================== //

    /**
     * Parses a course line into structured CourseInfo
     */
    private CourseInfo parseCourseLine(String line) throws IllegalArgumentException {
        if (isNonCourseLine(line)) {
            throw new IllegalArgumentException("Not a valid course line");
        }

        String normalized = normalizeCourseLine(line);


        // Try both subject-first and subject-last patterns
        return tryParseCourse(normalized)
                .or(() -> tryLanguageCourse(normalized))
                .orElseThrow(() -> new IllegalArgumentException("Cannot parse course line"));
    }

    private boolean isNonCourseLine(String line) {
        return line.matches("(?i).*(anno|year|semestre|semester).*") ||
                line.trim().split("\\s+").length < 3;
    }

    private String normalizeCourseLine(String line) {
        return line.trim()
                .replaceAll("[\\s,;|]+", " ")
                .replaceAll("\\s*[-–]\\s*", "-")
                .replaceAll("[^\\p{L}\\p{N}\\s-/]", "")
                .replaceAll("(?i)\\b(cfu|crediti|credits)\\b", "")
                .replaceAll("\\s+", " ");
    }

    public Optional<CourseInfo> tryParseCourse(String line) {
        if (line == null || line.trim().isEmpty()) {
            return Optional.empty();
        }

        // Normalize the input string
        line = line.trim().replaceAll("\\s+", " ");

        // Attempt 1: Structured pattern (code after name)
        Pattern structuredPattern1 = Pattern.compile(
                "^(?:(\\d+)\\s+)?" +                     // Optional leading digits (group 1)
                        "(.+?)" +                                // Course name (group 2)
                        "\\s+([A-Za-z]{2,4}(?:-[A-Za-z]{2,4})?/\\d{1,2})" +  // Course code (group 3)
                        "\\s+(\\d+)" +                           // Credits (group 4)
                        "(?:\\s+([IVXLCDM]+))?$",                // Optional Roman numerals (group 5)
                Pattern.CASE_INSENSITIVE
        );

        Matcher m1 = structuredPattern1.matcher(line);
        if (m1.find()) {
            return Optional.of(new CourseInfo(
                    m1.group(1) != null ? m1.group(1) : "",
                    m1.group(2).trim(),
                    m1.group(3).toUpperCase(),
                    Integer.parseInt(m1.group(4))
            ));
        }

        // Attempt 2: Structured pattern (code before name)
        Pattern structuredPattern2 = Pattern.compile(
                "^(?:(\\d+)\\s+)?" +                     // Optional leading digits (group 1)
                        "([A-Za-z]{2,4}(?:-[A-Za-z]{2,4})?/\\d{1,2})" +  // Course code (group 2)
                        "\\s+(.+?)" +                            // Course name (group 3)
                        "\\s+(\\d+)" +                           // Credits (group 4)
                        "(?:\\s+([IVXLCDM]+))?$",                // Optional Roman numerals (group 5)
                Pattern.CASE_INSENSITIVE
        );

        Matcher m2 = structuredPattern2.matcher(line);
        if (m2.find()) {
            return Optional.of(new CourseInfo(
                    m2.group(1) != null ? m2.group(1) : "",
                    m2.group(3).trim(),
                    m2.group(2).toUpperCase(),
                    Integer.parseInt(m2.group(4))
            ));
        }

        // Attempt 3: Flexible fallback approach
        // Find course code anywhere in string
        Pattern codePattern = Pattern.compile(
                "([A-Za-z]{2,4}(?:-[A-Za-z]{2,4})?/\\d{1,2})",
                Pattern.CASE_INSENSITIVE
        );

        Matcher codeMatcher = codePattern.matcher(line);
        if (codeMatcher.find()) {
            String courseCode = codeMatcher.group(1).toUpperCase();

            // Find last number in string (credits)
            Pattern creditPattern = Pattern.compile("(\\d+)(?!.*\\d)");
            Matcher creditMatcher = creditPattern.matcher(line);

            if (creditMatcher.find()) {
                int credits = Integer.parseInt(creditMatcher.group(1));

                // Course name is everything except code and credits
                String courseName = line.replace(courseCode, "")
                        .replace(creditMatcher.group(1), "")
                        .replaceAll("[^a-zA-Z0-9\\s]", "")
                        .trim()
                        .replaceAll("\\s+", " ");

                return Optional.of(new CourseInfo(
                        "",
                        courseName,
                        courseCode,
                        credits
                ));
            }
        }

        // Final fallback: Try to extract just code and credits
        Matcher lastTryMatcher = Pattern.compile(
                "([A-Za-z]{2,4}(?:-[A-Za-z]{2,4})?/\\d{1,2}).*?(\\d+)",
                Pattern.CASE_INSENSITIVE
        ).matcher(line);

        if (lastTryMatcher.find()) {
            return Optional.of(new CourseInfo(
                    "",
                    "Unknown Course",
                    lastTryMatcher.group(1).toUpperCase(),
                    Integer.parseInt(lastTryMatcher.group(2))
            ));
        }
        Pattern businessPattern = Pattern.compile(
                "^(\\w+)\\s+([A-Z]{2,4}-[A-Z]{2,4}/\\d{2})\\s+(\\d+)$",
                Pattern.CASE_INSENSITIVE
        );

        Matcher m = businessPattern.matcher(line);
        if (m.find()) {
            return Optional.of(new CourseInfo(
                    "",
                    m.group(1).trim(),
                    m.group(2).toUpperCase(),
                    Integer.parseInt(m.group(3))
            ));
        }

        return Optional.empty();
    }

    private Optional<CourseInfo> tryLanguageCourse(String line) {
        Pattern pattern = Pattern.compile(
                "^(\\d+\\s+)?(lingua\\s+)?inglese(.*?)(L-LIN/12)?\\s+(\\d+)(?:\\s+\\d+)*$",
                Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return Optional.of(new CourseInfo(
                    "",
                    "Lingua Inglese",
                    "L-LIN/12",
                    Integer.parseInt("6"))
            );
        }
        return Optional.empty();
    }

    // ================== Match Validation Methods ================== //


    private boolean isClearlyIrrelevant(String matchSubject, String targetSubject, String text) {
        // Filter language courses for technical subjects
        if (matchSubject.equals("ENGLISH") &&
                (targetSubject.equals("COMPUTER_SCIENCE") ||
                        targetSubject.equals("ENGINEERING"))) {
            return true;
        }

        // Filter internships unless explicitly matched
        if (text.contains("STAGE") || text.contains("TIROCINIO") || text.contains("INTERNSHIP")) {
            return !targetSubject.equals("PRACTICAL_TRAINING");
        }

        return false;
    }

    /**
     * Checks if two subjects are related
     */
    private boolean isRelatedSubject(String subject1, String subject2) {
        if (subject1.equalsIgnoreCase(subject2)) {
            return true;
        }

        // Handle subject codes directly
        String field1 = getAcademicField(subject1);
        String field2 = getAcademicField(subject2);

        if (field1.equals(field2)) return true;

        // Check if subjects are in the same group
        for (Set<String> group : RELATED_SUBJECTS.values()) {
            if (group.contains(field1) && group.contains(field2)) {
                return true;
            }
        }
        return false;
    }

    // ================== Scoring Functions ================== //

    /**
     * Calculates title similarity score (prioritized)
     */
    private double calculateTitleSimilarity(String matchTitle, String queryTitle) {
        // Exact match gets highest score
        if (normalizeText(matchTitle).equals(normalizeText(queryTitle))) {
            return 1.5;
        }
        // Partial match gets medium score
        if (matchTitle.toLowerCase().contains(queryTitle.toLowerCase()) ||
                queryTitle.toLowerCase().contains(matchTitle.toLowerCase())) {
            return 1.2;
        }
        return 1.0;
    }


    /**
     * Calculates a credit matching factor between 0.1 and 1.5 based on credit difference
     * @param matchedCredits Credits of the potential match
     * @param targetCredits Credits of the original course
     * @return Multiplier factor for the match score
     */
    private double calculateCreditFactor(int matchedCredits, int targetCredits) {
        int creditDiff = Math.abs(matchedCredits - targetCredits);

        // Exact match gets highest boost
        if (creditDiff == 0) {
            return 1.5;
        }

        // Small difference gets moderate boost
        if (creditDiff <= 2) {
            return 1.2;
        }

        // Moderate difference gets neutral treatment
        if (creditDiff <= 5) {
            return 1.0;
        }

        // Large difference gets penalty
        if (creditDiff <= 8) {
            return 0.8;
        }

        // Very large difference gets heavy penalty
        return 0.5;
    }

    /**
     * Checks if credit difference is acceptable for the subject type
     */
    private boolean isCreditDifferenceAcceptable(int matchedCredits, int targetCredits, String subjectCode) {
        int diff = Math.abs(matchedCredits - targetCredits);
        String subjectType = getAcademicField(subjectCode);

        return switch (subjectType) {
            case "MATHEMATICS", "STATISTICS", "NUMERICAL_MATH" -> diff <= 6;
            case "LANGUAGE" -> diff <= 1; // Language courses typically have fixed credits
            case "PRACTICAL_TRAINING" -> true; // Practical training can vary widely
            case "COMPUTER_SCIENCE", "COMPUTER_ENGINEERING" -> diff <= 3;
            default -> diff <= 4;
        };
    }

    // ================== Utility Methods ================== //

    /**
     * Extracts field from embedded text
     */
    protected String extractField(String text, String field) {
        try {
            return text.split(field + ":")[1].split("\\|")[0].trim();
        } catch (Exception e) {
            return "Unknown";
        }
    }

    /**
     * Normalizes text for comparison
     */
    private String normalizeText(String text) {
        return text.replaceAll("[^a-zA-Z0-9\\s]", "").trim().toLowerCase();
    }

    /**
     * Determines academic field from subject code
     */
    private String getAcademicField(String subjectCode) {
        if (subjectCode == null || subjectCode.isEmpty()) return "GENERAL";

        if (subjectCode.startsWith("INF/")) return "COMPUTER_SCIENCE";
        if (subjectCode.startsWith("ING-INF/")) return "COMPUTER_ENGINEERING";
        if (subjectCode.startsWith("MAT/")) {
            if (subjectCode.equals("MAT/08")) return "NUMERICAL_MATH";
            if (subjectCode.equals("MAT/09")) return "OPERATIONS_RESEARCH";
            return "MATHEMATICS";
        }
        if (subjectCode.startsWith("SECS-P/")) return "BUSINESS_MANAGEMENT";
        if (subjectCode.startsWith("FIS/")) return "PHYSICS";
        if (subjectCode.startsWith("CHIM/")) return "CHEMISTRY";
        if (subjectCode.startsWith("L-LIN/")) return "LANGUAGE";
        if (subjectCode.equals("PRACTICAL_TRAINING")) return "PRACTICAL_TRAINING";

        return "GENERAL";
    }

    // ================== Response Formatting ================== //

    /**
     * Formats the final course response
     */
    private String formatCourseResponse(CourseInfo course, List<EmbeddingMatch<TextSegment>> matches) {
        String subjectCategory = documentLoader.loadSubjectMapping()
                .getOrDefault(course.subjectCode(), "GENERAL");

        StringBuilder response = new StringBuilder();
        response.append(String.format("### %s %s (%s %d CFU)\n",
                course.code(), course.title(), subjectCategory, course.credits()));

        if (matches.isEmpty()) {
            return response.append("No suitable matches found").toString();
        }

        // Get subject from mapping for comparison
        String targetSubject = documentLoader.loadSubjectMapping()
                .getOrDefault(course.subjectCode(), "GENERAL");

        System.out.println(matches);
        matches.forEach(match -> {
            String text = match.embedded().text();
            // Now passing all 3 required arguments
            response.append(formatMatch(text, match.score(), targetSubject));
        });

        // Llama review integration
        try {
            String review = llamaReviewService.reviewMatches(course, matches);
            response.append("\nLLAMA REVIEW:\n").append(review);
        } catch (Exception e) {
            logger.error("Llama review failed", e);
        }

        return response.toString();
    }
    /**
     * Formats a single match
     */
    private String formatMatch(String matchText, double score, String targetSubject) {
        String matchSubject = extractField(matchText, "SUBJECT");
        int credits = Integer.parseInt(extractField(matchText, "CREDITS"));

        String relevanceTag = matchSubject.equals(targetSubject) ? "[Exact Subject Match]" :
                isRelatedSubject(matchSubject, targetSubject) ? "[Related Subject]" : "";

        return String.format(
                "- %s (Score: %.2f) %s\n" +
                        "  ▸ Subject: %s\n" +
                        "  ▸ Credits: %d\n" +
                        "  ▸ Topics: %s\n",
                extractField(matchText, "TITLE"),
                score,
                relevanceTag,
                matchSubject,
                credits,
                String.join("; ", extractTopTopics(matchText, 3))
        );
    }


    /**
     * Formats error response for unprocessable lines
     */
    private String formatProcessingError(String courseLine, String error) {
        String[] parts = courseLine.split("\\s+");
        String courseName = parts.length > 1 ? parts[1] : "Unknown Course";
        return String.format("### %s\nError: %s\nInput: %s",
                courseName, error, courseLine);
    }

    /**
     * Extracts top topics from course content
     */
    private List<String> extractTopTopics(String text, int limit) {
        try {
            String topics = text.split("CONTENTS:")[1];
            return Arrays.stream(topics.split(";"))
                    .map(String::trim)
                    .filter(t -> !t.isEmpty())
                    .limit(limit)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of("No topics available");
        }
    }

    // ================== Record Definitions ================== //

    /**
     * Represents course information
     */
    record CourseInfo(String code, String title, String subjectCode, int credits) {}
}