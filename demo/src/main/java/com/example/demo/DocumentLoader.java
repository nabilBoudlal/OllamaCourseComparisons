package com.example.demo;

import com.google.gson.*;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.apache.commons.text.similarity.LevenshteinDistance;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service class responsible for loading and enhancing course documents with knowledge base content.
 * <p>
 * This service loads course data from JSON files, enhances it with additional knowledge,
 * and stores the processed content in an embedding store for similarity searches.
 * </p>
 */
@Service
public class DocumentLoader {
    private static final Logger logger = LoggerFactory.getLogger(DocumentLoader.class);
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    @Value("classpath:unicam_courses.json")
    private Resource unicamResource;

    @Value("classpath:courses_base_knowledge.json")
    private Resource knowledgeBaseResource;

    private static final List<String> GENERIC_TOPICS = List.of(
            "introduction to", "fondamenti di", "basics of",
            "storia", "applications", "teoria generale"
    );

    /**
     * Constructs a new DocumentLoader with required dependencies.
     *
     * @param embeddingStore The embedding store for course data
     * @param embeddingModel The model for generating embeddings
     */
    public DocumentLoader(EmbeddingStore<TextSegment> embeddingStore,
                          EmbeddingModel embeddingModel) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Loads and processes course documents from JSON files.
     * <p>
     * This method performs the following operations:
     * 1. Clears existing embeddings from the store
     * 2. Loads knowledge base contents
     * 3. Processes each course from the UNICAM courses file
     * 4. Enhances course data with knowledge base content
     * 5. Generates embeddings and stores them
     * </p>
     *
     * @throws IOException if there's an error reading the input files
     */
    public void loadDocument() throws IOException {
        logger.info("Loading course documents...");
        embeddingStore.removeAll();

        Map<String, List<String>> kbContents = loadKnowledgeBaseContents();

        try (InputStream inputStream = unicamResource.getInputStream();
             InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {

            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            JsonArray courses = json.getAsJsonArray("courses");
            List<Embedding> embeddings = new ArrayList<>();
            List<TextSegment> segments = new ArrayList<>();

            for (JsonElement course : courses) {
                try {
                    JsonObject courseObj = course.getAsJsonObject();
                    String text = enhanceCourseText(courseObj, kbContents);
                    embeddings.add(embeddingModel.embed(text).content());
                    segments.add(TextSegment.from(text));
                } catch (Exception e) {
                    logger.error("Error processing course: {}", course, e);
                }
            }

            embeddingStore.addAll(embeddings, segments);
            logger.info("Successfully loaded {} UNICAM courses with KB enhancements", segments.size());
        }
    }

    /**
     * Retrieves knowledge context for a given course title.
     *
     * @param courseTitle The title of the course to find context for
     * @return A semicolon-separated string of relevant topics, or empty string if not found
     */
    public String getKnowledgeContext(String courseTitle) {
        try {
            Map<String, List<String>> kbContents = loadKnowledgeBaseContents();
            return findBestFuzzyMatch(kbContents, courseTitle)
                    .map(entry -> String.join("; ", entry.getValue()))
                    .orElse("");
        } catch (IOException e) {
            logger.warn("Failed to load KB context", e);
            return "";
        }
    }

    /**
     * Loads knowledge base contents from the JSON resource file.
     *
     * @return Map of course titles to their associated topics
     * @throws IOException if there's an error reading the knowledge base file
     */
    private Map<String, List<String>> loadKnowledgeBaseContents() throws IOException {
        Map<String, List<String>> kbContents = new HashMap<>();
        try (InputStream is = knowledgeBaseResource.getInputStream()) {
            JsonObject json = JsonParser.parseReader(new InputStreamReader(is)).getAsJsonObject();
            JsonArray kbCourses = json.getAsJsonArray("courses");

            for (JsonElement course : kbCourses) {
                try {
                    JsonObject c = course.getAsJsonObject();
                    String title = cleanTitle(c.get("title").getAsString());
                    List<String> contents = new ArrayList<>();
                    if (c.has("contents")) {
                        c.getAsJsonArray("contents").forEach(el -> contents.add(el.getAsString()));
                    }
                    kbContents.put(title, contents);
                } catch (Exception e) {
                    logger.error("Error processing KB course: {}", course, e);
                }
            }
        }
        return kbContents;
    }

    /**
     * Enhances course text with knowledge base content and standardized formatting.
     *
     * @param course The course JSON object
     * @param kbContents Map of knowledge base contents
     * @return Enhanced course text in standardized format
     */
    private String enhanceCourseText(JsonObject course, Map<String, List<String>> kbContents) {
        String code = course.has("code") ? course.get("code").getAsString() : "";
        String rawTitle = course.get("title").getAsString();
        String title = cleanTitle(rawTitle);
        int credits = course.get("credits").getAsInt();
        String subjectCode = course.has("subjectCode") ? course.get("subjectCode").getAsString() : "";

        List<String> contents = extractCourseContents(course);

        // Add relevant knowledge base topics
        List<String> kbTopics = findBestFuzzyMatch(kbContents, title)
                .map(Map.Entry::getValue)
                .orElse(Collections.emptyList());

        contents.addAll(kbTopics.stream()
                .filter(topic -> !isGenericTopic(topic))
                .map(topic -> "[KB] " + topic)
                .toList());

        // Get specific subject mapping
        String subject = Optional.of(subjectCode)
                .map(sc -> loadSubjectMapping().getOrDefault(sc, "GENERAL"))
                .orElse("GENERAL");

        return String.format(
                "CODE: %s | TITLE: %s | CREDITS: %d | SUBJECT: %s | CONTENTS: %s",
                code,
                rawTitle,
                credits,
                subject,
                String.join("; ", contents)
        );
    }

    /**
     * Finds the best fuzzy match for a course title in the knowledge base.
     *
     * @param kbContents Map of knowledge base contents
     * @param queryTitle The title to match against
     * @return Optional containing the best matching entry if found
     */
    private Optional<Map.Entry<String, List<String>>> findBestFuzzyMatch(
            Map<String, List<String>> kbContents, String queryTitle) {
        queryTitle = cleanTitle(queryTitle);
        LevenshteinDistance levenshtein = new LevenshteinDistance();

        String finalQueryTitle = queryTitle;
        String finalQueryTitle1 = queryTitle;
        return kbContents.entrySet().stream()
                .min(Comparator.comparingInt(entry ->
                        levenshtein.apply(finalQueryTitle, cleanTitle(entry.getKey()))
                ))
                .filter(entry ->
                        levenshtein.apply(finalQueryTitle1, cleanTitle(entry.getKey())) <= 3
                );
    }

    /**
     * Cleans and normalizes a course title.
     *
     * @param title The title to clean
     * @return Cleaned title with removed prefixes/suffixes and lowercase
     */
    private String cleanTitle(String title) {
        return title.replaceAll("T-[12]", "")
                .replaceAll("\\(.*?\\)", "")
                .trim()
                .toLowerCase();
    }

    /**
     * Extracts non-generic course contents from JSON object.
     *
     * @param course The course JSON object
     * @return List of relevant course topics
     */
    private List<String> extractCourseContents(JsonObject course) {
        List<String> contents = new ArrayList<>();
        if (course.has("contents")) {
            course.getAsJsonArray("contents").forEach(el -> {
                String topic = el.getAsString();
                if (!isGenericTopic(topic)) {
                    contents.add(topic);
                }
            });
        }
        return contents;
    }

    /**
     * Builds a general subject context from subject code.
     *
     * @param subjectCode The specific subject code
     * @return General subject category
     */
    private String buildSubjectContext(String subjectCode) {
        if (subjectCode.contains("MAT")) return "MATHEMATICS";
        if (subjectCode.contains("INF")) return "COMPUTER_SCIENCE";
        if (subjectCode.contains("ING")) return "ENGINEERING";
        if (subjectCode.contains("FIS")) return "PHYSICS";
        return "GENERAL";
    }

    /**
     * Checks if a topic is considered generic.
     *
     * @param topic The topic to check
     * @return true if the topic is generic, false otherwise
     */
    private boolean isGenericTopic(String topic) {
        String lowerTopic = topic.toLowerCase();
        return GENERIC_TOPICS.stream().anyMatch(lowerTopic::contains);
    }

    /**
     * Provides a mapping from specific subject codes to general subjects.
     *
     * @return Map of subject codes to general subjects
     */
    public Map<String, String> loadSubjectMapping() {
        Map<String, String> mapping = new HashMap<>();
        // Computer Science
        mapping.put("INF/01", "COMPUTER_SCIENCE");
        mapping.put("ING-INF/05", "COMPUTER_ENGINEERING");

        // Mathematics
        mapping.put("MAT/02", "MATHEMATICS");
        mapping.put("MAT/05", "MATHEMATICS");
        mapping.put("MAT/06", "MATHEMATICS");

        // Languages
        mapping.put("L-LIN/12", "LANGUAGE");

        // Business/Economics
        mapping.put("SECS-P/07", "ECONOMICS");

        // Sciences
        mapping.put("FIS/01", "PHYSICS");
        mapping.put("CHIM/03", "CHEMISTRY");

        return mapping;
    }
}