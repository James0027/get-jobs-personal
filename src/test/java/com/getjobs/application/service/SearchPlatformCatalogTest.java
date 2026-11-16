package com.getjobs.application.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.entity.BossOptionEntity;
import com.getjobs.application.entity.Job51OptionEntity;
import com.getjobs.application.search.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class SearchPlatformCatalogTest {
    @Test void usesReadOnlyBossCatalogAndActualJobAreaType() throws Exception {
        var boss=mock(BossService.class);var job51=mock(Job51Service.class);
        var catalog=new SearchPlatformCatalog(boss,mock(LiepinService.class),job51,mock(ZhilianService.class));
        var city=new BossOptionEntity();city.setType("city");city.setCode("fixture-sh");city.setName("上海");
        when(boss.getAllOptions()).thenReturn(List.of(city));
        var area=new Job51OptionEntity();area.setCode("fixture-area");area.setName("上海");
        when(job51.getOptionsByType("jobArea")).thenReturn(List.of(area));
        assertEquals("上海",catalog.options("BOSS","cityCode").get("fixture-sh"));
        assertEquals("上海",catalog.options("JOB51","jobArea").get("fixture-area"));
        var mapper=new ObjectMapper();
        assertThrows(SearchTemplateStore.Failure.class,()->catalog.validate("BOSS",mapper.readTree("{\"cityCode\":[\"unknown\"]}")));
        catalog.validate("BOSS",mapper.readTree("{\"cityCode\":[]}"));
        verify(boss,never()).getOptionsByType(anyString());
    }
}
