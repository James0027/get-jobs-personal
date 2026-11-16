package com.getjobs.application.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Persists and tests the explicit AI connection. Secrets stay in ConfigService and are never returned. */
@Service
public class AiConnectionService {
    public enum ConnectionKind { MODEL_API, AGENT_OR_MCP }

    public record ModelOption(String id, String label, boolean simulated) {}

    public record Preset(
            String id, String displayName, ConnectionKind connectionKind, String provider, String protocol,
            String defaultBaseUrl, List<ModelOption> models, boolean legacyProtocolSupported,
            boolean agentExecutionSupported, boolean simulated, String sourceUrl, String modelSourceUrl,
            String notes
    ) {}

    public record Connection(
            String id, String displayName, ConnectionKind connectionKind, String provider, String protocol,
            String baseUrl, String model, boolean enabled, boolean apiKeyConfigured, boolean executionVerified,
            boolean simulated, String lastTestAt, String lastTestMessage, List<ModelOption> models
    ) {}

    public record SaveRequest(String provider, String protocol, String baseUrl, String model) {}

    public record TestResult(
            boolean success, boolean configured, boolean verified, boolean simulated,
            String message, String testedAt
    ) {}

    private static final String OPENAI_CHAT = "OPENAI_CHAT_COMPLETIONS";
    private static final String OPENAI_RESPONSES = "OPENAI_RESPONSES";
    private static final String MCP = "MCP";
    private static final String STUB = "STUB";
    private static final Set<String> SUPPORTED_PROTOCOLS = Set.of(OPENAI_CHAT, OPENAI_RESPONSES, MCP, STUB);

    private static final List<Preset> PRESETS = List.of(
            new Preset("openai-chat", "OpenAI Chat Completions", ConnectionKind.MODEL_API, "OPENAI", OPENAI_CHAT,
                    "https://api.openai.com/v1",
                    List.of(
                            new ModelOption("gpt-6-astra", "GPT-6 Astra", false),
                            new ModelOption("gpt-6.1-sol", "GPT-6.1 Sol", false),
                            new ModelOption("gpt-6.1-terra", "GPT-6.1 Terra", false),
                            new ModelOption("gpt-6-luna", "GPT-6 Luna", false)),
                    true, false, false,
                    "https://platform.openai.com/docs/api-reference/chat",
                    "https://platform.openai.com/docs/models",
                    "Official model catalog checked 2026-11-02."),
            new Preset("openai-responses", "OpenAI Responses", ConnectionKind.MODEL_API, "OPENAI", OPENAI_RESPONSES,
                    "https://api.openai.com/v1",
                    List.of(
                            new ModelOption("gpt-6-astra", "GPT-6 Astra", false),
                            new ModelOption("gpt-6.1-sol", "GPT-6.1 Sol", false),
                            new ModelOption("gpt-6.1-terra", "GPT-6.1 Terra", false),
                            new ModelOption("gpt-6-luna", "GPT-6 Luna", false)),
                    true, false, false,
                    "https://platform.openai.com/docs/api-reference/responses",
                    "https://platform.openai.com/docs/models",
                    "Official model catalog checked 2026-11-02."),
            new Preset("deepseek-chat", "DeepSeek Chat Completions", ConnectionKind.MODEL_API, "DEEPSEEK", OPENAI_CHAT,
                    "https://api.deepseek.com",
                    List.of(
                            new ModelOption("deepseek-flash", "DeepSeek V4.1 Flash", false),
                            new ModelOption("deepseek-v4-pro", "DeepSeek V4 Pro", false)),
                    true, false, false,
                    "https://api-docs.deepseek.com/",
                    "https://api-docs.deepseek.com/quick_start/pricing",
                    "Official catalog: deepseek-flash is V4.1-Flash-1017; deepseek-v4-pro is V4-Pro-0813."),
            new Preset("mcp", "MCP（仅协议说明）", ConnectionKind.AGENT_OR_MCP, "MCP", MCP,
                    "", List.of(), false, false, false,
                    "https://modelcontextprotocol.io/docs/getting-started/intro",
                    "https://modelcontextprotocol.io/docs/getting-started/intro",
                    "MCP is an agent/tool protocol, not a model API. Execution is not supported."),
            new Preset("deepseek-responses", "DeepSeek Responses", ConnectionKind.MODEL_API, "DEEPSEEK", OPENAI_RESPONSES,
                    "https://api.deepseek.com",
                    List.of(
                            new ModelOption("deepseek-flash", "DeepSeek V4.1 Flash", false),
                            new ModelOption("deepseek-v4-pro", "DeepSeek V4 Pro", false)),
                    true, false, false,
                    "https://api-docs.deepseek.com/guides/responses_api",
                    "https://api-docs.deepseek.com/quick_start/pricing",
                    "Official Responses API support checked 2026-11-02."),
            new Preset("custom", "自定义模型接口", ConnectionKind.MODEL_API, "CUSTOM", OPENAI_CHAT,
                    "", List.of(), true, false, false,
                    "", "", "Keep custom base URL and model; protocol remains OpenAI-compatible."),
            new Preset("stub", "隔离验证 Stub", ConnectionKind.MODEL_API, "STUB", STUB,
                    "", List.of(new ModelOption("stub-deterministic", "Deterministic Stub", true)),
                    true, false, true, "", "", "Local deterministic stub; never a real model result.")
    );

    private final ConfigService configs;
    private final AiService ai;

    public AiConnectionService(ConfigService configs, AiService ai) {
        this.configs = configs;
        this.ai = ai;
    }

    public List<Preset> getPresets() {
        return PRESETS;
    }

    public Connection getActive() {
        String base = value("BASE_URL");
        String model = value("MODEL");
        String provider = firstNonblank(value("AI_PROVIDER"), inferProvider(base));
        String protocol = firstNonblank(value("AI_PROTOCOL"), STUB.equals(provider) ? STUB : OPENAI_CHAT);
        String key = value("API_KEY");
        boolean simulated = STUB.equals(provider) || STUB.equals(protocol);
        boolean verified = "1".equals(value("AI_CONNECTION_VERIFIED")) && !simulated;
        boolean enabled = nonblank(provider) && nonblank(protocol)
                && (simulated || (nonblank(base) && nonblank(model) && nonblank(key)));
        Preset preset = findPreset(provider, protocol);
        return new Connection(
                "active", preset == null ? "当前连接" : preset.displayName(),
                MCP.equals(protocol) ? ConnectionKind.AGENT_OR_MCP : ConnectionKind.MODEL_API,
                provider, protocol, base, model,
                enabled,
                nonblank(key), verified, simulated,
                value("AI_CONNECTION_LAST_TEST_AT"), value("AI_CONNECTION_LAST_TEST_MESSAGE"),
                preset == null ? List.of() : preset.models()
        );
    }

    public Connection save(SaveRequest request) {
        if (request == null) throw new IllegalArgumentException("连接配置不能为空");
        String provider = upper(request.provider());
        String protocol = upper(request.protocol());
        if (provider.isBlank()) provider = "CUSTOM";
        if (protocol.isBlank()) protocol = OPENAI_CHAT;
        if (!SUPPORTED_PROTOCOLS.contains(protocol)) throw new IllegalArgumentException("不支持的协议");
        if (MCP.equals(provider) || MCP.equals(protocol)) {
            throw new IllegalArgumentException("MCP 是工具协议，不支持作为模型 API 连接");
        }

        Preset preset = findPreset(provider, protocol);
        String base = trim(request.baseUrl());
        String model = trim(request.model());
        if (STUB.equals(provider)) {
            protocol = STUB;
            base = "";
            model = "stub-deterministic";
        } else {
            if (base.isBlank() && preset != null && nonblank(preset.defaultBaseUrl())) base = preset.defaultBaseUrl();
            if (model.isBlank() && preset != null && !preset.models().isEmpty()) model = preset.models().getFirst().id();
            if (base.isBlank() || model.isBlank()) throw new IllegalArgumentException("连接地址和模型不能为空");
        }

        configs.batchUpdateConfigs(Map.of(
                "AI_PROVIDER", provider,
                "AI_PROTOCOL", protocol,
                "BASE_URL", base,
                "MODEL", model,
                "AI_CONNECTION_VERIFIED", "0",
                "AI_CONNECTION_LAST_TEST_AT", "",
                "AI_CONNECTION_LAST_TEST_MESSAGE", ""
        ));
        return getActive();
    }

    public TestResult test() {
        Connection active = getActive();
        String now = Instant.now().toString();
        if (active.simulated()) {
            saveTestState("0", now, "模拟连接，未验证真实模型");
            return new TestResult(true, true, false, true, "模拟连接，未验证真实模型", now);
        }
        if (active.connectionKind() == ConnectionKind.AGENT_OR_MCP) {
            throw new IllegalArgumentException("MCP 执行不支持，无法测试模型连接");
        }
        if (!active.apiKeyConfigured() || !nonblank(active.baseUrl()) || !nonblank(active.model())) {
            saveTestState("0", now, "连接配置不完整");
            return new TestResult(false, false, false, false, "连接配置不完整", now);
        }
        try {
            String reply = ai.sendRequest("请只回复 OK");
            if (reply == null || reply.isBlank()) {
                saveTestState("0", now, "连接测试未返回内容");
                return new TestResult(false, true, false, false, "连接测试未返回内容", now);
            }
            saveTestState("1", now, "连接测试成功");
            return new TestResult(true, true, true, false, "连接测试成功", now);
        } catch (Exception e) {
            saveTestState("0", now, "连接测试失败，请检查地址、模型或密钥");
            return new TestResult(false, true, false, false, "连接测试失败，请检查地址、模型或密钥", now);
        }
    }

    public boolean isSimulated() {
        return getActive().simulated();
    }

    public String activeModel() {
        return getActive().model();
    }

    private void saveTestState(String verified, String at, String message) {
        configs.batchUpdateConfigs(Map.of(
                "AI_CONNECTION_VERIFIED", verified,
                "AI_CONNECTION_LAST_TEST_AT", at,
                "AI_CONNECTION_LAST_TEST_MESSAGE", message
        ));
    }

    private String value(String key) {
        String value = configs.getConfigValue(key);
        return value == null ? "" : value;
    }

    private static Preset findPreset(String provider, String protocol) {
        return PRESETS.stream()
                .filter(p -> p.provider().equals(provider) && p.protocol().equals(protocol))
                .findFirst()
                .orElse(null);
    }

    private static String inferProvider(String base) {
        String lower = base == null ? "" : base.toLowerCase(Locale.ROOT);
        if (lower.contains("deepseek")) return "DEEPSEEK";
        if (lower.contains("openai")) return "OPENAI";
        return "CUSTOM";
    }

    private static String firstNonblank(String first, String second) {
        return nonblank(first) ? first : second;
    }

    private static boolean nonblank(String value) {
        return value != null && !value.isBlank();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
