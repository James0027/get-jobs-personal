package com.getjobs.worker.boss.delivery;

import java.util.*;
import java.util.regex.Pattern;

public final class GraduationEligibility {
    public enum Decision { ELIGIBLE, INELIGIBLE, NEEDS_REVIEW }
    private static final Pattern YEARS = Pattern.compile("(?<![\\d第])((?:20)?\\d{2}(?:\\s*届)?(?:\\s*[,，、/及和或]\\s*(?:20)?\\d{2}(?:\\s*届)?)*?)\\s*届");
    private static final Pattern DIGITS = Pattern.compile("(?:20)?\\d{2}");
    private static final Pattern CAMPUS = Pattern.compile("校招|校园招聘|应届|在校|毕业生|实习生");
    private static final Pattern CONTEXT = Pattern.compile("面向|招收|招募|招聘|限定|仅限|校招|应届|在校|毕业|学生|实习");
    private static final Pattern EVENTS = Pattern.compile("^\\s*(?:的)?(?:活动|大会|论坛|比赛|展会|竞赛|会议|峰会)");
    private static final Pattern RANGE = Pattern.compile("(?:20)?\\d{2}\\s*届?\\s*[-—~～至到]\\s*(?:20)?\\d{2}\\s*届");
    private GraduationEligibility() {}
    public static Decision evaluate(Integer year,String title,Collection<String> platformTags,String jd) {
        return evaluate(year,Objects.toString(title,"")+"\n"+
                String.join("\n",platformTags==null?List.of():platformTags)+"\n"+Objects.toString(jd,""));
    }
    public static Decision evaluate(Integer year, String text) {
        String content = text == null ? "" : text;
        var matcher = YEARS.matcher(content);
        Set<Integer> years = new HashSet<>();
        while (matcher.find()) {
            String suffix=content.substring(matcher.end(),Math.min(content.length(),matcher.end()+15));
            if (EVENTS.matcher(suffix).find()) continue;
            String nearby=content.substring(Math.max(0,matcher.start()-35),Math.min(content.length(),matcher.end()+35));
            // Bare cohort strings in a job title count as recruitment context; event ordinals do not.
            if (!CONTEXT.matcher(nearby).find() && !content.trim().equals(matcher.group().trim())) continue;
            var digits=DIGITS.matcher(matcher.group(1));
            while(digits.find()) {
                int value = Integer.parseInt(digits.group());
                years.add(value < 100 ? value + 2000 : value);
            }
        }
        if (RANGE.matcher(content).find() && CONTEXT.matcher(content).find()) return Decision.NEEDS_REVIEW;
        if (years.isEmpty()) {
            return CAMPUS.matcher(content).find() ? Decision.NEEDS_REVIEW : Decision.ELIGIBLE;
        }
        if (year == null) return Decision.NEEDS_REVIEW;
        // Ranges, exclusion language or conflicting non-recruitment references require human review.
        if (content.matches("(?s).*(不招|不接受|非).*届.*")) return Decision.NEEDS_REVIEW;
        return years.contains(year) ? Decision.ELIGIBLE : Decision.INELIGIBLE;
    }
}
