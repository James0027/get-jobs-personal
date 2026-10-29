package com.getjobs.worker.boss.delivery;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.getjobs.worker.boss.BossConfig;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** Bind unfinished runs to the complete configuration used to create them. */
public final class DeliveryConfiguration {
    private DeliveryConfiguration() {}
    public static String fingerprint(BossConfig config) {
        try {
            var mapper = JsonMapper.builder().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(config)));
        } catch (Exception e) { throw new IllegalStateException("无法校验投递配置，未启动任务", e); }
    }
    public static void requireSame(Map<String,Object> saved, BossConfig config) {
        Object previous = saved.get("configFingerprint");
        if (!(previous instanceof String value) || value.isBlank())
            throw new IllegalStateException("旧未完成任务缺少配置校验记录，已拒绝自动恢复；请先人工处理旧任务，避免重复投递。");
        if (!fingerprint(config).equals(previous))
            throw new IllegalStateException("未完成任务的目标数量、城市、关键词或其他配置已变更，已拒绝继续旧任务；请恢复原配置后继续，原任务成功数不会清零。");
    }
}
