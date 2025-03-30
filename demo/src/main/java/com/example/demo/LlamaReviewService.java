package com.example.demo;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.springframework.stereotype.Service;
import com.example.demo.ChatController.CourseInfo;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class LlamaReviewService {
    private final ChatLanguageModel llamaModel;
    private final DocumentLoader documentLoader;
    private final CourseSubjectValidator subjectValidator;

    public LlamaReviewService(ChatLanguageModel llamaModel,
                              DocumentLoader documentLoader,
                              CourseSubjectValidator subjectValidator) {
        this.llamaModel = llamaModel;
        this.documentLoader = documentLoader;
        this.subjectValidator = subjectValidator;
    }

    public String reviewMatches(CourseInfo query, List<EmbeddingMatch<TextSegment>> matches) {
        try {
            List<String> availableCourses = documentLoader.getAllCourse();
            String validation = validateMatches(query, matches, availableCourses);

            if (validation.contains("INVALID")) {
                String suggestions = generateValidSuggestions(query, availableCourses);
                return formatOutput(validation, suggestions);
            }

            return "VALIDATION RESULTS:\nAll matches are valid";
        } catch (IOException e) {
            return "Error loading course data";
        }
    }

    private String validateMatches(CourseInfo query,
                                   List<EmbeddingMatch<TextSegment>> matches,
                                   List<String> availableCourses) {
        StringBuilder result = new StringBuilder();

        // 1. Check exact match exists in catalog
        if (!hasExactMatch(query.title(), availableCourses)) {
            result.append("- INVALID: No exact title match found\n");
        }

        // 2. Validate each match's subject compatibility
        matches.forEach(m -> {
            if (!subjectValidator.isValidMatch(query.subjectCode(), extractSubject(m))) {
                result.append(String.format("- INVALID: %s has incompatible subject (%s)\n",
                        extractTitle(m), extractSubject(m)));
            }
        });

        return result.toString();
    }

    private String generateValidSuggestions(CourseInfo query, List<String> availableCourses) {
        // 1. Get keywords from query title
        Set<String> keywords = extractKeywords(query.title());

        // 2. Find courses with matching keywords
        List<String> candidates = findCandidateCourses(availableCourses, keywords);

        if (candidates.isEmpty()) {
            return "No valid suggestions available";
        }

        // 3. Let LLM select top 3 from filtered candidates
        String prompt = buildSuggestionPrompt(query, candidates);
        String response = llamaModel.generate(prompt);

        return formatValidSuggestions(response, candidates);
    }

    // Helper methods
    private boolean hasExactMatch(String queryTitle, List<String> courses) {
        return courses.stream()
                .anyMatch(title -> CourseTextUtils.isLikelySameCourse(queryTitle, title));
    }

    private Set<String> extractKeywords(String title) {
        return Arrays.stream(title.split("\\s+"))
                .map(word -> word.replaceAll("[^a-zA-Z]", "").toLowerCase())
                .filter(word -> word.length() > 3 && !CourseTextUtils.STOP_WORDS.contains(word))
                .collect(Collectors.toSet());
    }

    private List<String> findCandidateCourses(List<String> courses, Set<String> keywords) {
        return courses.stream()
                .filter(title -> keywords.stream()
                        .anyMatch(keyword -> title.toLowerCase().contains(keyword)))
                .collect(Collectors.toList());
    }

    private String buildSuggestionPrompt(CourseInfo query, List<String> candidates) {
        return String.format(
                "Select 3 most relevant courses for '%s' (%s) from:\n%s\n\n" +
                        "Requirements:\n" +
                        "- Must maintain academic level\n" +
                        "- Prefer similar subject areas\n\n" +
                        "Respond ONLY with:\n" +
                        "1. [Exact Title]\n" +
                        "2. [Exact Title]\n" +
                        "3. [Exact Title]",
                query.title(), query.subjectCode(), String.join("\n", candidates)
        );
    }

    private String formatValidSuggestions(String response, List<String> validCourses) {
        return Arrays.stream(response.split("\n"))
                .limit(3)
                .map(line -> line.replaceAll("^\\d+\\.\\s*", "").trim())
                .filter(validCourses::contains)
                .map(s -> (Arrays.asList(response.split("\n")).indexOf(s) + 1) + ". " + s)
                .collect(Collectors.joining("\n"));
    }

    private String formatOutput(String validation, String suggestions) {
        return String.format(
                "VALIDATION RESULTS:\n%s\n\n" +
                        "SUGGESTED ALTERNATIVES:\n%s",
                validation,
                suggestions
        );
    }

    private String extractTitle(EmbeddingMatch<TextSegment> match) {
        return CourseTextUtils.extractField(match.embedded().text(), "TITLE");
    }

    private String extractSubject(EmbeddingMatch<TextSegment> match) {
        return CourseTextUtils.extractField(match.embedded().text(), "SUBJECT");
    }
}