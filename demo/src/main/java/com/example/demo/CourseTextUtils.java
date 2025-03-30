package com.example.demo;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class CourseTextUtils {
    public static final Set<String> STOP_WORDS = Set.of(
            "degli", "dei", "e", "per", "del", "della", "di", "a", "in", "con"
    );

    public static boolean isLikelySameCourse(String title1, String title2) {
        return normalizeTitle(title1).equals(normalizeTitle(title2));
    }

    public static String normalizeTitle(String title) {
        return Arrays.stream(title.toLowerCase().split("\\s+"))
                .filter(word -> !STOP_WORDS.contains(word))
                .map(word -> word.replaceAll("[^a-z]", ""))
                .collect(Collectors.joining(" "));
    }

    public static String extractField(String text, String field) {
        try {
            return text.split(field + ":")[1].split("\\|")[0].trim();
        } catch (Exception e) {
            return "Unknown";
        }
    }
}