package com.getjobs.application.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getjobs.application.chat.BossChatStore;
import com.getjobs.application.chat.ChatContracts;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves template criteria plus platform override inheritance without mutating anything. */
@Service
public class SearchTemplateResolver {
    public record Resolved(
            String templateId, long templateVersion, String profileVersion, String platformId,
            long version, JsonNode fields, JsonNode effectiveCriteria, Map<String, String> sources,
            List<String> draftOnlyFields, boolean executionSupported
    ) {}

    private final SearchTemplateStore store;
    private final SearchPlatformCatalog catalog;
    private final BossChatStore profiles;
    private final ObjectMapper mapper;

    public SearchTemplateResolver(SearchTemplateStore store, SearchPlatformCatalog catalog,
                                  BossChatStore profiles, ObjectMapper mapper) {
        this.store = store;
        this.catalog = catalog;
        this.profiles = profiles;
        this.mapper = mapper;
    }

    public Resolved resolve(String templateId, String platform) {
        Map<String, Boolean> allowed = SearchTemplateStore.FIELDS.get(platform);
        if (allowed == null) throw SearchTemplateStore.invalid("platformId", "Unknown platform");
        SearchTemplateStore.Template template = store.get(templateId);
        ChatContracts.Profile profile = profiles.profile();
        SearchTemplateStore.Override override = store.overrides(templateId).stream()
                .filter(item -> item.platformId().equals(platform))
                .findFirst().orElse(null);

        ObjectNode effective = mapper.createObjectNode();
        Map<String, String> sources = new LinkedHashMap<>();
        List<String> draft = new ArrayList<>();
        JsonNode criteria = template.criteria();
        JsonNode overlay = override == null ? mapper.createObjectNode() : override.fields();

        if (!criteria.has("targetCities")) {
            resolveField(platform, "targetCities", mapper.valueToTree(profile.acceptedCities()), "PROFILE", overlay, effective, sources, draft);
        }
        criteria.properties().forEach(entry ->
                resolveField(platform, entry.getKey(), entry.getValue(), "TEMPLATE", overlay, effective, sources, draft));
        if (override != null) {
            override.fields().properties().forEach(entry -> {
                effective.set(entry.getKey(), entry.getValue());
                sources.put(entry.getKey(), "PLATFORM_OVERRIDE");
            });
        }

        return new Resolved(template.id(), template.version(), profile.version(), platform,
                override == null ? 0 : override.version(),
                override == null ? mapper.createObjectNode() : override.fields(),
                effective, sources, draft, false);
    }

    private void resolveField(String platform, String common, JsonNode value, String source, JsonNode overlay,
                              ObjectNode effective, Map<String, String> sources, List<String> draft) {
        String target = switch (common) {
            case "targetCities" -> platform.equals("JOB51") ? "jobArea" : "cityCode";
            case "companyScale" -> "scale";
            case "financingStage" -> "stage";
            case "workType" -> "jobType";
            default -> common;
        };
        Boolean array = SearchTemplateStore.FIELDS.get(platform).get(target);
        if (array == null) {
            draft.add(common);
            return;
        }
        if (overlay.has(target)) return;
        if (target.equals("keywords")) {
            effective.set(target, value);
            sources.put(target, source);
            return;
        }
        if (!array && value.size() != 1) {
            draft.add(common);
            return;
        }
        Map<String, String> options = catalog.options(platform, target);
        var resolved = mapper.createArrayNode();
        for (JsonNode item : value) {
            String code = options.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(item.asText()))
                    .map(Map.Entry::getKey)
                    .findFirst().orElse(null);
            if (code == null) {
                draft.add(common);
                return;
            }
            resolved.add(code);
        }
        effective.set(target, array ? resolved : resolved.get(0));
        sources.put(target, source);
    }
}
