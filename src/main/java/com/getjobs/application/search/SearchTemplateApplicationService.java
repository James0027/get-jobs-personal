package com.getjobs.application.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.getjobs.application.chat.BossChatStore;
import com.getjobs.application.chat.ChatContracts;
import com.getjobs.application.entity.BossConfigEntity;
import com.getjobs.application.entity.Job51ConfigEntity;
import com.getjobs.application.entity.LiepinConfigEntity;
import com.getjobs.application.entity.ZhilianConfigEntity;
import com.getjobs.application.service.BossService;
import com.getjobs.application.service.ConfigService;
import com.getjobs.application.service.Job51Service;
import com.getjobs.application.service.LiepinService;
import com.getjobs.application.service.ZhilianService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Explicitly applies a template to platform configuration. It never starts a task or sends anything. */
@Service
public class SearchTemplateApplicationService {
    private final SearchTemplateStore templates;
    private final SearchTemplateResolver resolver;
    private final BossChatStore profiles;
    private final ConfigService configs;
    private final BossService boss;
    private final LiepinService liepin;
    private final Job51Service job51;
    private final ZhilianService zhilian;

    public SearchTemplateApplicationService(SearchTemplateStore templates, SearchTemplateResolver resolver,
                                            BossChatStore profiles, ConfigService configs, BossService boss,
                                            LiepinService liepin, Job51Service job51, ZhilianService zhilian) {
        this.templates = templates;
        this.resolver = resolver;
        this.profiles = profiles;
        this.configs = configs;
        this.boss = boss;
        this.liepin = liepin;
        this.job51 = job51;
        this.zhilian = zhilian;
    }

    public Map<String, Object> effective(String templateId) {
        SearchTemplateStore.Template template = templates.get(templateId);
        ChatContracts.Profile profile = profiles.profile();
        JsonNode criteria = template.criteria();
        Map<String, Object> fields = new LinkedHashMap<>();

        JsonNode cities = criteria.get("targetCities");
        add(fields, "acceptedCities", cities == null ? profile.acceptedCities() : cities,
                cities == null ? "PROFILE" : "TEMPLATE");
        JsonNode workType = criteria.get("workType");
        add(fields, "workType",
                workType == null ? profile.intentions().workType() : workType,
                workType == null ? source(profile.intentions().workType()) : "TEMPLATE");
        add(fields, "expectedSalary", firstNonblank(template.expectedSalary(), profile.intentions().expectedSalary()),
                source(template.expectedSalary(), profile.intentions().expectedSalary()));
        add(fields, "arrivalIntention", firstNonblank(template.arrivalIntention(), profile.intentions().arrivalIntention()),
                source(template.arrivalIntention(), profile.intentions().arrivalIntention()));
        add(fields, "linkedResumeAssetId", firstNonblank(template.linkedResumeAssetId(), profile.preferredResumeAssetId()),
                source(template.linkedResumeAssetId(), profile.preferredResumeAssetId()));
        add(fields, "personalIntro", firstNonblank(template.personalIntro(), ""), source(template.personalIntro(), ""));
        add(fields, "communicationStyle", firstNonblank(template.communicationStyle(), ""), source(template.communicationStyle(), ""));
        add(fields, "sayHiDraft", template.sayHiDraft(), "TEMPLATE");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateId", template.id());
        result.put("templateVersion", template.version());
        result.put("profileVersion", profile.version());
        result.put("fields", fields);
        return result;
    }

    public Map<String, Object> apply(String templateId, String platform) {
        SearchTemplateResolver.Resolved resolved = resolver.resolve(templateId, platform);
        SearchTemplateStore.Template template = templates.get(templateId);
        Map<String, Object> applied = new LinkedHashMap<>();
        LinkedHashSet<String> unapplied = new LinkedHashSet<>(resolved.draftOnlyFields());

        JsonNode criteria = resolved.effectiveCriteria();
        switch (platform) {
            case "BOSS" -> {
                BossConfigEntity entity = new BossConfigEntity();
                String keywords = join(criteria.get("keywords"));
                if (keywords != null) { entity.setKeywords(keywords); applied.put("keywords", keywords); }
                String cityCode = join(criteria.get("cityCode"));
                if (cityCode != null) { entity.setCityCode(cityCode); applied.put("cityCode", cityCode); }
                String salary = join(criteria.get("salary"));
                if (salary != null) { entity.setSalary(salary); applied.put("salary", salary); }
                String experience = join(criteria.get("experience"));
                if (experience != null) { entity.setExperience(experience); applied.put("experience", experience); }
                String degree = join(criteria.get("degree"));
                if (degree != null) { entity.setDegree(degree); applied.put("degree", degree); }
                String industry = join(criteria.get("industry"));
                if (industry != null) { entity.setIndustry(industry); applied.put("industry", industry); }
                String scale = join(criteria.get("scale"));
                if (scale != null) { entity.setScale(scale); applied.put("scale", scale); }
                String stage = join(criteria.get("stage"));
                if (stage != null) { entity.setStage(stage); applied.put("stage", stage); }
                String jobType = text(criteria.get("jobType"));
                if (jobType != null) { entity.setJobType(jobType); applied.put("jobType", jobType); }
                if (nonblank(template.sayHiDraft())) { entity.setSayHi(template.sayHiDraft()); applied.put("sayHi", template.sayHiDraft()); }
                boss.saveOrUpdateFirstSelective(entity);
            }
            case "LIEPIN" -> {
                LiepinConfigEntity entity = new LiepinConfigEntity();
                String keywords = join(criteria.get("keywords"));
                if (keywords != null) { entity.setKeywords(keywords); applied.put("keywords", keywords); }
                String city = text(criteria.get("cityCode"));
                if (city != null) { entity.setCity(city); applied.put("cityCode", city); }
                String salary = text(criteria.get("salary"));
                if (salary != null) { entity.setSalaryCode(salary); applied.put("salary", salary); }
                liepin.saveOrUpdateFirstSelective(entity);
                addUnapplied(unapplied, "sayHiDraft", template.sayHiDraft());
            }
            case "JOB51" -> {
                Job51ConfigEntity entity = new Job51ConfigEntity();
                String keywords = join(criteria.get("keywords"));
                if (keywords != null) { entity.setKeywords(keywords); applied.put("keywords", keywords); }
                String jobArea = join(criteria.get("jobArea"));
                if (jobArea != null) { entity.setJobArea(jobArea); applied.put("jobArea", jobArea); }
                String salary = join(criteria.get("salary"));
                if (salary != null) { entity.setSalary(salary); applied.put("salary", salary); }
                job51.saveOrUpdateFirstSelective(entity);
                addUnapplied(unapplied, "sayHiDraft", template.sayHiDraft());
            }
            case "ZHILIAN" -> {
                ZhilianConfigEntity entity = new ZhilianConfigEntity();
                String keywords = join(criteria.get("keywords"));
                if (keywords != null) { entity.setKeywords(keywords); applied.put("keywords", keywords); }
                String city = text(criteria.get("cityCode"));
                if (city != null) { entity.setCityCode(city); applied.put("cityCode", city); }
                String salary = text(criteria.get("salary"));
                if (salary != null) { entity.setSalary(salary); applied.put("salary", salary); }
                zhilian.saveOrUpdateFirstSelective(entity);
                addUnapplied(unapplied, "sayHiDraft", template.sayHiDraft());
            }
            default -> throw SearchTemplateStore.invalid("platformId", "Unknown platform");
        }

        addUnapplied(unapplied, "expectedSalary", template.expectedSalary());
        addUnapplied(unapplied, "arrivalIntention", template.arrivalIntention());
        addUnapplied(unapplied, "linkedResumeAssetId", template.linkedResumeAssetId());
        addUnapplied(unapplied, "personalIntro", template.personalIntro());
        addUnapplied(unapplied, "communicationStyle", template.communicationStyle());
        unapplied.removeAll(applied.keySet());

        String now = Instant.now().toString();
        String prefix = "TEMPLATE_APPLIED_" + platform + "_";
        configs.batchUpdateConfigs(Map.of(
                prefix + "ID", templateId,
                prefix + "VERSION", String.valueOf(template.version()),
                prefix + "AT", now,
                prefix + "FIELDS", String.join(",", applied.keySet())
        ));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateId", templateId);
        result.put("templateVersion", template.version());
        result.put("platformId", platform);
        result.put("appliedFields", applied);
        result.put("unappliedFields", new ArrayList<>(unapplied));
        result.put("tasksStarted", false);
        result.put("appliedAt", now);
        return result;
    }

    private static void add(Map<String, Object> fields, String key, Object value, String source) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("value", value);
        entry.put("source", source);
        fields.put(key, entry);
    }

    private static String source(String templateValue, String profileValue) {
        return nonblank(templateValue) ? "TEMPLATE" : nonblank(profileValue) ? "PROFILE" : "UNSET";
    }

    private static String source(String value) {
        return nonblank(value) ? "PROFILE" : "UNSET";
    }

    private static void addUnapplied(LinkedHashSet<String> unapplied, String field, String value) {
        if (nonblank(value)) unapplied.add(field);
    }

    private static String firstNonblank(String first, String second) {
        return nonblank(first) ? first : nonblank(second) ? second : "";
    }

    private static boolean nonblank(String value) {
        return value != null && !value.isBlank();
    }

    private static String text(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        if (value.isArray()) return value.isEmpty() ? null : text(value.get(0));
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static String join(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        if (!value.isArray()) return text(value);
        List<String> values = new ArrayList<>();
        value.forEach(item -> {
            if (item != null && !item.isNull() && nonblank(item.asText())) values.add(item.asText());
        });
        return values.isEmpty() ? null : String.join(",", values);
    }
}
