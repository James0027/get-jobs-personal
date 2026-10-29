package com.getjobs.application.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

import static com.getjobs.application.chat.ChatContracts.*;

/** Deliberately rejects markdown, coercions, extra fields and unsupported facts. */
@Component
public class ChatDecisionValidator {
    private static final Set<String> FIELDS_V1 = Set.of(
            "schemaVersion", "requestId", "intent", "action",
            "replyText", "resumeAssetId", "evidenceFactIds", "missingFacts", "reasonCode"
    );
    private static final Set<String> FIELDS_V2 = Set.of(
            "schemaVersion", "requestId", "intent", "action",
            "replyText", "resumeAssetId", "evidenceFactIds", "missingFacts", "reasonCode", "resumeAssetKind"
    );

    private final ObjectMapper mapper;

    public ChatDecisionValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Decision parse(String raw, String requestId, Profile profile, int maxChars) {
        return parse(raw, requestId, profile, maxChars, "FOLLOW_UP");
    }

    public Decision parse(String raw, String requestId, Profile profile, int maxChars, String task) {
        try {
            if (raw == null || raw.length() > 32000) throw new IllegalArgumentException("AI输出为空或过长");
            JsonNode node = mapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("AI输出必须是JSON对象");

            JsonNode svNode = node.get("schemaVersion");
            if (svNode == null || !svNode.isTextual()) throw new IllegalArgumentException("AI输出缺少有效的schemaVersion");
            String sv = svNode.textValue();
            if (!"1".equals(sv) && !"2".equals(sv)) throw new IllegalArgumentException("不支持的schemaVersion: " + sv);

            Set<String> actual = new HashSet<>();
            node.fieldNames().forEachRemaining(actual::add);

            Set<String> expectedFields = "1".equals(sv) ? FIELDS_V1 : FIELDS_V2;
            if (!actual.equals(expectedFields)) throw new IllegalArgumentException("AI输出字段不符合契约");

            for (String field : expectedFields) {
                if ("evidenceFactIds".equals(field) || "missingFacts".equals(field)) {
                    if (!node.get(field).isArray()) throw new IllegalArgumentException("AI字段应为数组:" + field);
                    for (JsonNode value : node.get(field)) {
                        if (!value.isTextual() || value.asText().length() > 500) throw new IllegalArgumentException("AI数组类型错误");
                    }
                } else {
                    if (!node.get(field).isTextual()) throw new IllegalArgumentException("AI字段类型错误:" + field);
                }
            }

            // Do not inherit a permissive ObjectMapper's unknown-enum-to-null policy.
            Intent.valueOf(node.get("intent").textValue());
            Action.valueOf(node.get("action").textValue());

            Decision parsed = mapper.treeToValue(node, Decision.class);
            if (!requestId.equals(parsed.requestId()))
                throw new IllegalArgumentException("AI请求标识不匹配");

            String kind = parsed.resumeAssetKind();
            if ("1".equals(sv)) {
                // v1 kind inferred from assetId
                if (parsed.action() == Action.SEND_RESUME) {
                    if (!parsed.resumeAssetId().isBlank() && parsed.resumeAssetId().equals(profile.approvedResumeAssetId())) {
                        kind = "IMAGE";
                    } else if (!parsed.resumeAssetId().isBlank() && parsed.resumeAssetId().equals(profile.approvedPdfAssetId())) {
                        kind = "PDF";
                    } else {
                        kind = "";
                    }
                } else {
                    kind = "";
                }
            }

            if (!Set.of("", "IMAGE", "PDF").contains(kind)) {
                throw new IllegalArgumentException("非法resumeAssetKind: " + kind);
            }

            Decision d = new Decision(parsed.schemaVersion(), parsed.requestId(), parsed.intent(), parsed.action(),
                    parsed.replyText(), parsed.resumeAssetId(), parsed.evidenceFactIds(), parsed.missingFacts(),
                    parsed.reasonCode(), kind);

            if (d.replyText().codePointCount(0, d.replyText().length()) > maxChars || d.reasonCode().length() > 120)
                throw new IllegalArgumentException("AI回复超过长度限制");

            Set<String> facts = new HashSet<>();
            profile.facts().forEach(f -> facts.add(f.id()));
            profile.acceptedCities().forEach(city -> facts.add("location:" + city));
            if (profile.graduationYear() != null) facts.add("graduationYear");
            if (!facts.containsAll(d.evidenceFactIds())) throw new IllegalArgumentException("AI引用了未批准事实");

            if (d.action() == Action.SEND_RESUME) {
                if (!"IMAGE".equals(d.resumeAssetKind()) && !"PDF".equals(d.resumeAssetKind())) {
                    throw new IllegalArgumentException("发简历须指定IMAGE或PDF kind");
                }
                if ("IMAGE".equals(d.resumeAssetKind())) {
                    if (d.resumeAssetId().isBlank() || profile.approvedResumeAssetId().isBlank() || !d.resumeAssetId().equals(profile.approvedResumeAssetId())) {
                        throw new IllegalArgumentException("发图片简历须引用非空批准图片资产");
                    }
                } else {
                    if (d.resumeAssetId().isBlank() || profile.approvedPdfAssetId().isBlank() || !d.resumeAssetId().equals(profile.approvedPdfAssetId())) {
                        throw new IllegalArgumentException("发PDF简历须引用非空批准PDF资产");
                    }
                }
            } else {
                if (!d.resumeAssetId().isEmpty() && !d.resumeAssetId().equals(profile.approvedResumeAssetId()) && !d.resumeAssetId().equals(profile.approvedPdfAssetId())) {
                    throw new IllegalArgumentException("AI引用了未批准简历");
                }
            }

            if (d.action() == Action.SEND_RESUME && d.intent() != Intent.REQUEST_RESUME
                    || d.action() == Action.CONFIRM_LOCATION && d.intent() != Intent.CONFIRM_LOCATION)
                throw new IllegalArgumentException("AI动作与意图冲突");

            if ((d.intent() == Intent.MIXED || d.intent() == Intent.UNKNOWN) &&
                    (d.action() == Action.SEND_RESUME || d.action() == Action.CONFIRM_LOCATION))
                throw new IllegalArgumentException("混合或未知意图禁止自动动作");

            if (d.action() == Action.AUTO_REPLY) {
                if (d.replyText().isBlank()) throw new IllegalArgumentException("AUTO_REPLY回复不能为空");
                if (d.evidenceFactIds().isEmpty()) throw new IllegalArgumentException("AUTO_REPLY必须包含证据事实");
                if (d.intent() == Intent.MIXED || d.intent() == Intent.UNKNOWN) throw new IllegalArgumentException("混合或未知意图禁止AUTO_REPLY");
            }

            if ("MATCH_EXPLANATION".equals(task) && d.action() != Action.SUGGEST_REPLY && d.action() != Action.NO_ACTION)
                throw new IllegalArgumentException("匹配说明任务不允许此动作");

            if (d.action() == Action.SUGGEST_REPLY && d.replyText().isBlank())
                throw new IllegalArgumentException("建议回复不能为空");

            if (d.action() == Action.NEEDS_INFORMATION && d.missingFacts().isEmpty())
                throw new IllegalArgumentException("缺资料动作须列出缺失资料");

            var years = java.util.regex.Pattern.compile("(20\\d{2})(?:年)?(?:届|毕业)").matcher(d.replyText());
            while (years.find()) {
                if (profile.graduationYear() == null || Integer.parseInt(years.group(1)) != profile.graduationYear())
                    throw new IllegalArgumentException("回复毕业届别与批准资料不符");
            }

            if (d.replyText().matches("(?s).*(?:薪资|月薪|年薪|工资|到岗|入职|\\d+[kK]|\\d+元).*")) {
                boolean approved = profile.facts().stream().anyMatch(f -> d.evidenceFactIds().contains(f.id()) && f.text().contains(d.replyText()));
                if (!approved) throw new IllegalArgumentException("薪资或到岗承诺必须有明确批准事实");
            }

            return d;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("AI JSON无法校验", e);
        }
    }
}
