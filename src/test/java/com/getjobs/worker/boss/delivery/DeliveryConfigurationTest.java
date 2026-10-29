package com.getjobs.worker.boss.delivery;

import com.getjobs.worker.boss.BossConfig;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryConfigurationTest {
    private BossConfig config() {
        var config=new BossConfig();config.setTargetCount(50);config.setCityCode(List.of("A","B"));config.setKeywords(List.of("产品"));return config;
    }
    @Test void equalConfigurationsHaveStableFingerprintsAndPersistAcrossRestore() {
        var config=config();String fingerprint=DeliveryConfiguration.fingerprint(config);
        assertEquals(fingerprint,DeliveryConfiguration.fingerprint(config()));
        var run=new DeliveryRun(50,config.getCityCode(),null,fingerprint);
        assertDoesNotThrow(()->DeliveryConfiguration.requireSame(DeliveryRun.restore(run.snapshot(),null).snapshot(),config));
    }
    @Test void changedTargetCitiesAndKeywordsEachPreventSilentResume() {
        var original=config();var saved=Map.<String,Object>of("configFingerprint",DeliveryConfiguration.fingerprint(original));
        var target=config();target.setTargetCount(10);
        var cities=config();cities.setCityCode(List.of("C"));
        var keywords=config();keywords.setKeywords(List.of("研发"));
        for(var changed:List.of(target,cities,keywords)) {
            var error=assertThrows(IllegalStateException.class,()->DeliveryConfiguration.requireSame(saved,changed));
            assertTrue(error.getMessage().contains("已变更"));
        }
    }
    @Test void legacyUnfinishedTaskCannotBeResumedWithoutConfigurationEvidence() {
        assertThrows(IllegalStateException.class,()->DeliveryConfiguration.requireSame(Map.of(),config()));
    }
    @Test void mapInsertionOrderDoesNotChangeFingerprint() {
        var a=config();a.setCustomCityCode(new LinkedHashMap<>(Map.of("A","1","B","2")));
        var b=config();var reverse=new LinkedHashMap<String,String>();reverse.put("B","2");reverse.put("A","1");b.setCustomCityCode(reverse);
        assertEquals(DeliveryConfiguration.fingerprint(a),DeliveryConfiguration.fingerprint(b));
    }
}
