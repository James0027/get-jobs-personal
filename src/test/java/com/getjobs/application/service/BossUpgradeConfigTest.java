package com.getjobs.application.service;

import com.getjobs.application.entity.BossConfigEntity;
import com.getjobs.application.entity.BossOptionEntity;
import com.getjobs.application.mapper.BossConfigMapper;
import com.getjobs.application.mapper.BossOptionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BossUpgradeConfigTest {
    @TempDir Path directory;

    @Test
    void migrationAddsSafeDefaultsAndPreservesValuesAcrossRestart() throws Exception {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + directory.resolve("config.db"));
        BossService service = new BossService(null, null, null, null, null, source);
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE boss_config (id INTEGER PRIMARY KEY, city_code TEXT)");
            statement.execute("INSERT INTO boss_config VALUES (1, '上海')");
            service.ensureRepeatChatColumn();
            try (var rows = statement.executeQuery("SELECT city_code,target_count,ai_only,auto_resume,auto_location,enable_follow_up FROM boss_config")) {
                assertTrue(rows.next());
                assertEquals("上海", rows.getString(1));
                assertEquals(50, rows.getInt(2));
                assertEquals(1, rows.getInt(3));
                assertEquals(0, rows.getInt(4));
                assertEquals(0, rows.getInt(5));
                assertEquals(0, rows.getInt(6));
            }
            statement.execute("UPDATE boss_config SET target_count=7,ai_only=0,city_code='[上海,杭州]'");
            service.ensureRepeatChatColumn();
            try (var rows = statement.executeQuery("SELECT target_count,ai_only,city_code FROM boss_config")) {
                assertTrue(rows.next());
                assertEquals(7, rows.getInt(1));
                assertEquals(0, rows.getInt(2));
                assertEquals("[上海,杭州]", rows.getString(3));
            }
        }
    }

    @Test
    void citiesKeepAllValuesOrderAndOldScalarCompatibility() {
        BossService service = spy(new BossService(null, null, null, null, null, null));
        BossOptionEntity shanghai = new BossOptionEntity(); shanghai.setName("上海"); shanghai.setCode("101020100");
        BossOptionEntity hangzhou = new BossOptionEntity(); hangzhou.setName("杭州"); hangzhou.setCode("101210100");
        doReturn(shanghai).when(service).getOptionByTypeAndCode("city", "101020100");
        doReturn(hangzhou).when(service).getOptionByTypeAndCode("city", "101210100");
        doReturn(null).when(service).getOptionByTypeAndCode("city", "上海");
        doReturn(null).when(service).getOptionByTypeAndCode("city", "全国");
        assertEquals("[上海,杭州]", service.normalizeCitiesToNames("[101020100,101210100,101020100]"));
        assertEquals("[上海]", service.normalizeCitiesToNames("上海"));
        assertThrows(IllegalArgumentException.class, () -> service.normalizeCitiesToNames("[全国,上海]"));
        assertEquals(List.of("上海", "杭州"), service.parseListString("[\"上海\",\"杭州\"]"));
    }

    @Test
    void validatesTargetAndMergesExplicitFalseWithoutErasingOmittedFields() {
        BossConfigMapper mapper = mock(BossConfigMapper.class);
        BossConfigEntity existing = new BossConfigEntity(); existing.setId(1L); existing.setTargetCount(50); existing.setAiOnly(1);
        when(mapper.selectOne(any())).thenReturn(existing);
        BossService service = new BossService(null, null, mapper, null, null, null);
        BossConfigEntity patch = new BossConfigEntity(); patch.setTargetCount(7); patch.setAiOnly(0); patch.setCityCode("[上海,杭州]");
        assertEquals(7, service.saveOrUpdateFirstSelective(patch).getTargetCount());
        assertEquals(0, existing.getAiOnly());
        service.saveOrUpdateFirstSelective(new BossConfigEntity());
        assertEquals("[上海,杭州]", existing.getCityCode());
        assertEquals(7, existing.getTargetCount());
        patch.setTargetCount(0);
        assertThrows(IllegalArgumentException.class, () -> service.saveOrUpdateFirstSelective(patch));
        patch.setTargetCount(10001);
        assertThrows(IllegalArgumentException.class, () -> service.validateConfig(patch));
        patch.setTargetCount(10000);
        assertDoesNotThrow(() -> service.validateConfig(patch));
        patch.setTargetCount(1); patch.setGraduationYear(27);
        assertThrows(IllegalArgumentException.class, () -> service.validateConfig(patch));
    }

    @Test
    void officialIndustrySnapshotExposesRealGroupsAndPreservesUnknownLegacyOptions() {
        var catalog = BossIndustryCatalog.get();
        assertEquals("https://www.zhipin.com/wapi/zpCommon/data/industry.json", catalog.source());
        assertEquals(15, catalog.groups().size());
        var internet = catalog.groups().stream().filter(g -> g.code().equals("100000")).findFirst().orElseThrow();
        assertEquals("互联网/AI", internet.name());
        assertTrue(internet.options().stream().anyMatch(o -> o.code().equals("100028") && o.name().equals("人工智能")));
        BossService service = new BossService(null, null, null, null, null, null);
        BossOptionEntity parent = new BossOptionEntity(); parent.setCode("100000"); parent.setName("互联网/AI");
        BossOptionEntity child = new BossOptionEntity(); child.setCode("100028"); child.setName("人工智能");
        BossOptionEntity legacy = new BossOptionEntity(); legacy.setCode("legacy"); legacy.setName("旧行业");
        var options = List.of(parent, child, legacy);
        var groups = service.getIndustryGroups(options);
        assertEquals(List.of(parent, child), groups.getFirst().get("options"));
        assertEquals(3, options.size());
    }

    @Test
    void addsMissingOfficialIndustryCodesOnceWithoutOverwritingOldRows() {
        BossOptionMapper mapper = mock(BossOptionMapper.class);
        BossOptionEntity old = new BossOptionEntity(); old.setId(9L); old.setCode("100028"); old.setName("用户原有行业名");
        when(mapper.selectList(any())).thenReturn(List.of(old));
        when(mapper.selectCount(any())).thenReturn(1L);
        BossService service = new BossService(mapper, null, null, null, null, null);
        assertEquals(List.of(old), service.getOptionsByType("industry"));
        service.getOptionsByType("industry");
        var inserted = org.mockito.ArgumentCaptor.forClass(BossOptionEntity.class);
        verify(mapper, times(148)).insert(inserted.capture());
        assertTrue(inserted.getAllValues().stream().anyMatch(o -> o.getCode().equals("100000") && o.getName().equals("互联网/AI")));
        assertTrue(inserted.getAllValues().stream().noneMatch(o -> o.getCode().equals("100028")));
        assertEquals("用户原有行业名", old.getName());
    }
}
