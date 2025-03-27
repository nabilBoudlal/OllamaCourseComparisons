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
 * REST Controller for handling course-related operations including loading courses and finding similar courses.
 */
@RestController
@RequestMapping("/api/v1")
public class ChatController {
    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final DocumentLoader documentLoader;

    /**
     * Constructs a new ChatController with required dependencies.
     *
     * @param embeddingStore   The embedding store for course data
     * @param embeddingModel   The model for generating embeddings
     * @param documentLoader   The loader for course documents
     */
    public ChatController(EmbeddingStore<TextSegment> embeddingStore,
                          EmbeddingModel embeddingModel,
                          DocumentLoader documentLoader) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.documentLoader = documentLoader;
    }

    /**
     * Loads courses into the embedding store.
     *
     * @return ResponseEntity indicating success or failure
     */
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

    /**
     * Processes a user query to find similar courses.
     *
     * @param userQuery The query containing course information
     * @return Formatted response with matching courses or error messages
     */
    @GetMapping("/chat")
    public String chat(@RequestParam("userQuery") String userQuery) {
        try {
            logger.info("Received query:\n{}", userQuery);

            return Arrays.stream(userQuery.split("\\r?\\n"))
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .peek(line -> logger.debug("Processing line: '{}'", line))
                    .map(line -> {
                        try {
                            return processCourse(line);
                        } catch (Exception e) {
                            logger.warn("Skipping unprocessable line: '{}'", line);
                            return formatSkippedLine(line);
                        }
                    })
                    .filter(response -> !response.isEmpty())
                    .collect(Collectors.joining("\n\n"));
        } catch (Exception e) {
            logger.error("Processing failed", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Formats a line that was skipped during processing.
     *
     * @param line The line that couldn't be processed
     * @return Formatted message about the skipped line
     */
    private String formatSkippedLine(String line) {
        if (isNonCourseLine(line)) {
            return ""; // Skip non-course lines silently
        }
        return String.format("### Could not process line\n%s", line);
    }

    /**
     * Processes a single course line to find similar courses.
     *
     * @param courseLine The course information line to process
     * @return Formatted response with matching courses
     */
    private String processCourse(String courseLine) {
        try {
            logger.debug("Attempting to parse: '{}'", courseLine);
            CourseInfo courseInfo = parseCourseLine(courseLine);

            String processedQuery = buildEnhancedQuery(courseInfo);
            logger.debug("Enhanced query: '{}'", processedQuery);

            Embedding queryEmbedding = embeddingModel.embed(processedQuery).content();
            List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(queryEmbedding, 10);
            List<EmbeddingMatch<TextSegment>> filteredMatches = filterMatches(matches, courseInfo);

            return formatResponse(courseInfo, filteredMatches);
        } catch (Exception e) {
            logger.error("Failed to process line: '{}'", courseLine, e);
            return formatErrorResponse(courseLine, e.getMessage());
        }
    }

    /**
     * Formats an error response for a course line that couldn't be processed.
     *
     * @param courseLine The course line that caused the error
     * @param error      The error message
     * @return Formatted error response
     */
    private String formatErrorResponse(String courseLine, String error) {
        String[] parts = courseLine.split("\\s+");
        String courseName = parts.length > 1 ? parts[1] : "Unknown Course";
        return String.format("### %s\nError: %s\nInput: %s", courseName, error, courseLine);
    }

    /**
     * Builds an enhanced query string for embedding generation.
     *
     * @param course The course information
     * @return Enhanced query string
     */
    private String buildEnhancedQuery(CourseInfo course) {
        String specificSubject = documentLoader.loadSubjectMapping()
                .getOrDefault(course.subjectCode(), "GENERAL");

        return String.format(
                "TITLE: %s | SUBJECT: %s | CREDITS: %d",
                normalizeTitle(course.title()),
                specificSubject,
                course.credits()
        );
    }

    /**
     * Adjusts the match score based on subject code relevance.
     *
     * @param match  The embedding match
     * @param course The course information
     * @return Adjusted embedding match
     */
    private EmbeddingMatch<TextSegment> adjustMatchScore(EmbeddingMatch<TextSegment> match, CourseInfo course) {
        double newScore = match.score();
        String text = match.embedded().text();

        // Credit match bonus
        int matchedCredits = Integer.parseInt(extractField(text, "CREDITS"));
        if (matchedCredits == course.credits()) {
            newScore *= 1.15; // 15% boost for exact credit match
        }

        // Rest of your existing scoring logic
        return new EmbeddingMatch<>(newScore, match.embeddingId(), match.embedding(), match.embedded());
    }
    /**
     * Normalizes a course title by removing special characters and converting to lowercase.
     *
     * @param title The title to normalize
     * @return Normalized title
     */
    private String normalizeTitle(String title) {
        return title.replaceAll("[^a-zA-Z0-9\\s]", "").trim().toLowerCase();
    }

    /**
     * Parses a course line into structured CourseInfo.
     *
     * @param line The course information line
     * @return Parsed CourseInfo
     * @throws IllegalArgumentException if the line cannot be parsed
     */
    private CourseInfo parseCourseLine(String line) {
        if (isNonCourseLine(line)) {
            throw new IllegalArgumentException("Skipping non-course line");
        }

        String normalized = normalizeCourseLine(line);
        logger.debug("Normalized input: {}", normalized);

        return trySubjectFirst(normalized)
                .or(() -> trySubjectLast(normalized))
                .orElseThrow(() -> new IllegalArgumentException("Cannot parse course line: " + line));
    }

    /**
     * Checks if a line is likely not a course description.
     *
     * @param line The line to check
     * @return true if the line is not a course description
     */
    private boolean isNonCourseLine(String line) {
        return line.matches("(?i).*(anno|year|semestre|semester).*") ||
                line.trim().split("\\s+").length < 3;
    }

    /**
     * Normalizes a course line by cleaning OCR artifacts and standardizing format.
     *
     * @param line The line to normalize
     * @return Normalized line
     */
    private String normalizeCourseLine(String line) {
        return line.trim()
                .replaceAll("[\\s,;|]+", " ")
                .replaceAll("\\s*[-–]\\s*", "-")
                .replaceAll("[^\\p{L}\\p{N}\\s-/]", "")
                .replaceAll("(?i)\\b(cfu|crediti|credits)\\b", "")
                .replaceAll("\\s+", " ");
    }

    /**
     * Attempts to parse a course line in subject-first format.
     *
     * @param line The line to parse
     * @return Optional containing CourseInfo if parsing succeeds
     */
    private Optional<CourseInfo> trySubjectFirst(String line) {
        Pattern pattern = Pattern.compile(
                "^([A-Z]{2,4}(?:-[A-Z]{2,4})?/\\d{1,2})\\s+(.*?)\\s+(\\d+)\\s*([IV-]+)?$",
                Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return Optional.of(new CourseInfo(
                    "",
                    matcher.group(2).trim(),
                    matcher.group(1).toUpperCase(),
                    Integer.parseInt(matcher.group(3))
            ));
        }
        return Optional.empty();
    }

    /**
     * Attempts to parse a course line in subject-last format.
     *
     * @param line The line to parse
     * @return Optional containing CourseInfo if parsing succeeds
     */
    private Optional<CourseInfo> trySubjectLast(String line) {
        Pattern pattern = Pattern.compile(
                "^(.*?)\\s+([A-Z]{2,4}(?:-[A-Z]{2,4})?/\\d{1,2})\\s+(\\d+)\\s*([IV-]+)?$",
                Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return Optional.of(new CourseInfo(
                    "",
                    matcher.group(1).trim(),
                    matcher.group(2).toUpperCase(),
                    Integer.parseInt(matcher.group(3))
            ));
        }
        return Optional.empty();
    }

    /**
     * Formats the response for a course with its matches.
     *
     * @param course  The course information
     * @param matches List of matching courses
     * @return Formatted response string
     */
    private String formatResponse(CourseInfo course, List<EmbeddingMatch<TextSegment>> matches) {
        String subjectCategory = documentLoader.loadSubjectMapping().getOrDefault(course.subjectCode(), "GENERAL");

        // Define which subjects are considered core CS
        Set<String> coreCSSubjects = Set.of("COMPUTER_SCIENCE", "COMPUTER_ENGINEERING");

        // Define which subjects are considered relevant (including math)
        Set<String> relevantSubjects = Set.of("COMPUTER_SCIENCE", "COMPUTER_ENGINEERING", "MATHEMATICS");

        if (!relevantSubjects.contains(subjectCategory)) {
            return String.format(
                    "### %s %s (%s %d CFU)\n" +
                            "Note: This course appears to be outside our core Computer Science domain.\n" +
                            "While we found some potential matches, they may not be fully relevant:\n\n%s",
                    course.code(), course.title(), course.subjectCode(), course.credits(),
                    formatMatches(matches, false)
            );
        }

        // Special header for math courses
        if (subjectCategory.equals("MATHEMATICS")) {
            return String.format(
                    "### %s %s (%s %d CFU)\n" +
                            "Note: Found relevant mathematical concepts:\n\n%s",
                    course.code(), course.title(), course.subjectCode(), course.credits(),
                    formatMatches(matches, true)
            );
        }
        if (matches.isEmpty()) {
            return String.format(
                    "### %s %s (%s %d CFU)\n" +
                            "No strong matches found. Possible reasons:\n" +
                            "1. Course may be specialized/unique\n" +
                            "2. Knowledge base may lack coverage\n" +
                            "3. Try manual review with syllabus",
                    course.code(), course.title(), course.subjectCode(), course.credits()
            );
        }

        StringBuilder response = new StringBuilder();
        response.append(String.format("### %s %s (%s %d CFU)\n",
                course.code(), course.title(), course.subjectCode(), course.credits()));

        matches.forEach(match -> {
            String text = match.embedded().text();
            response.append(String.format(
                    "- %s (Score: %.2f)\n" +
                            "  ▸ Credits: %s\n" +
                            "  ▸ Subject: %s\n" +
                            "  ▸ Content Match: %s\n",
                    extractField(text, "TITLE"),
                    match.score(),
                    extractField(text, "CREDITS"),
                    extractField(text, "SUBJECT"),
                    String.join("; ", extractTopTopics(text, 3))
            ));
        });

        if (matches.stream().anyMatch(m -> extractField(m.embedded().text(), "CONTENTS").contains("[KB]"))) {
            response.append("\nℹ️ Some results enhanced with knowledge base");
        }

        return response.toString();
    }

    /**
     * Extracts a field value from embedded text.
     *
     * @param text  The text containing fields
     * @param field The field to extract
     * @return The field value or "Unknown" if not found
     */
    private String extractField(String text, String field) {
        try {
            return text.split(field + ":")[1].split("\\|")[0].trim();
        } catch (Exception e) {
            return "Unknown";
        }
    }

    /**
     * Extracts the top topics from embedded text.
     *
     * @param text  The text containing topics
     * @param limit Maximum number of topics to return
     * @return List of top topics
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

    /**
     * Filters and sorts embedding matches for a course.
     *
     * @param matches List of all matches
     * @param course  The course to match against
     * @return Filtered and sorted list of matches
     */
    private List<EmbeddingMatch<TextSegment>> filterMatches(List<EmbeddingMatch<TextSegment>> matches, CourseInfo course) {
        String subjectCategory = documentLoader.loadSubjectMapping().getOrDefault(course.subjectCode(), "GENERAL");

        return matches.stream()
                .filter(match -> {
                    String text = match.embedded().text();
                    String matchSubject = extractField(text, "SUBJECT");

                    // Always show mathematics matches
                    if (subjectCategory.equals("MATHEMATICS")) {
                        return true;
                    }

                    // For non-CS courses, only show matches with same category
                    if (!subjectCategory.equals("COMPUTER_SCIENCE") &&
                            !subjectCategory.equals("COMPUTER_ENGINEERING")) {
                        return matchSubject.equalsIgnoreCase(subjectCategory);
                    }

                    // Original CS course filtering
                    if (text.contains("INGLESE") && !course.title().contains("INGLESE")) {
                        return false;
                    }
                    return !text.contains("STAGE") || course.title().contains("STAGE");
                })
                .sorted(Comparator.comparingDouble(match -> -match.score()))
                .limit(5)
                .collect(Collectors.toList());
    }

    private String formatMatches(List<EmbeddingMatch<TextSegment>> matches, boolean isCS) {
        StringBuilder response = new StringBuilder();
        matches.forEach(match -> {
            String text = match.embedded().text();
            response.append(String.format(
                    "- %s (Score: %.2f)%s\n" +
                            "  ▸ Credits: %s\n" +
                            "  ▸ Subject: %s\n" +
                            "  ▸ Content Match: %s\n",
                    extractField(text, "TITLE"),
                    match.score(),
                    isCS ? "" : " [Low Relevance]",
                    extractField(text, "CREDITS"),
                    extractField(text, "SUBJECT"),
                    String.join("; ", extractTopTopics(text, 3))
            ));
        });
        return response.toString();
    }

    /**
     * Record representing course information.
     *
     * @param code        Course code
     * @param title       Course title
     * @param subjectCode Subject code
     * @param credits     Number of credits
     */
    record CourseInfo(String code, String title, String subjectCode, int credits) {}
}