package com.getjobs.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

/** Read-only, distributable snapshot of BOSS's official industry hierarchy. */
public final class BossIndustryCatalog {
    public record Option(String code, String name) {}
    public record Group(String code, String name, List<Option> options) {}
    public record Catalog(int schemaVersion, String source, String verifiedAt, List<Group> groups) {}

    private static final Catalog CATALOG = load();
    private BossIndustryCatalog() {}
    public static Catalog get() { return CATALOG; }
    private static Catalog load() {
        try (var input = new ClassPathResource("boss/industry-catalog.json").getInputStream()) {
            Catalog result = new ObjectMapper().readValue(input, Catalog.class);
            if (result.schemaVersion() != 1 || result.groups() == null || result.groups().isEmpty()) {
                throw new IllegalStateException("行业字典格式无效");
            }
            return result;
        } catch (IOException e) { throw new IllegalStateException("无法加载已核验行业字典", e); }
    }
}
