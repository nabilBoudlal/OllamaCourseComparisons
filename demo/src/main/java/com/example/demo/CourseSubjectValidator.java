package com.example.demo;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class CourseSubjectValidator {
    private static final Map<String, Set<String>> SUBJECT_GROUPS = Map.of(
            "COMPUTER_ENGINEERING", Set.of("ING-INF/05", "ING-INF/04", "INF/01"),
            "BUSINESS", Set.of("SECS-P/07", "SECS-P/08"),
            "MATHEMATICS", Set.of("MAT/02", "MAT/05", "MAT/06")
    );

    public boolean isValidMatch(String querySubjectCode, String matchSubjectCode) {
        String queryGroup = findSubjectGroup(querySubjectCode);
        String matchGroup = findSubjectGroup(matchSubjectCode);
        return queryGroup.equals(matchGroup);
    }

    private String findSubjectGroup(String subjectCode) {
        return SUBJECT_GROUPS.entrySet().stream()
                .filter(entry -> entry.getValue().contains(subjectCode))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("GENERAL");
    }
}