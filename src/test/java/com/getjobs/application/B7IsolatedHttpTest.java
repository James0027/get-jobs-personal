package com.getjobs.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.chat.BossChatStore;
import com.getjobs.application.chat.ChatContracts.*;
import com.getjobs.application.config.StartupRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import com.getjobs.application.controller.BossChatController;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class B7IsolatedHttpTest {

    private static final Path TEMP_DIR;

    static {
        try {
            TEMP_DIR = Files.createTempDirectory("b7-");
        } catch (IOException e) {
            throw new RuntimeException("无法创建临时测试目录", e);
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String dbPath = TEMP_DIR.resolve("b7.db").toAbsolutePath().toString().replace('\\', '/');
        String assetsPath = TEMP_DIR.resolve("assets").toAbsolutePath().toString().replace('\\', '/');

        // 在 Spring 上下文启动前初始化 SQLite 表结构
        try {
            String schemaSql = new String(new ClassPathResource("b7-schema.sql").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                 Statement stmt = conn.createStatement()) {
                for (String sql : schemaSql.split(";")) {
                    String trimmed = sql.trim();
                    if (!trimmed.isEmpty()) {
                        stmt.execute(trimmed);
                    }
                }
            }
        } catch (IOException | SQLException e) {
            throw new RuntimeException("初始化临时数据库 DDL 失败", e);
        }

        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("boss.resume.document.dir", () -> assetsPath);
        registry.add("boss.read-only-check-mode", () -> "false");
    }

    @MockBean
    private StartupRunner startupRunner;

    /** 启动期播种器只对全新库写入默认数据，与 B7 隔离验收无关，隔离上下文里替换为空实现以免要求其他三平台建表。 */
    @MockBean
    private com.getjobs.application.init.DefaultPageConfigInitializer defaultPageConfigInitializer;

    @MockBean
    private com.getjobs.application.init.CookieSeedInitializer cookieSeedInitializer;

    @MockBean
    private com.getjobs.application.init.ZhilianOptionInitializer zhilianOptionInitializer;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BossChatStore bossChatStore;

    private byte[] createMinimalValidPdf(String marker) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));

        int offset1 = baos.size();
        baos.write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n".getBytes(StandardCharsets.US_ASCII));

        int offset2 = baos.size();
        baos.write("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n".getBytes(StandardCharsets.US_ASCII));

        int offset3 = baos.size();
        String markerComment = "% marker: " + marker + "\n";
        String paddingComment = "% padding: " + "A".repeat(150) + "\n";
        byte[] obj3 = ("3 0 obj\n<< /Type /Page /Parent 2 0 R >>\n" + markerComment + paddingComment + "endobj\n")
                .getBytes(StandardCharsets.US_ASCII);
        baos.write(obj3);

        int xrefOffset = baos.size();
        String xref = "xref\n" +
                "0 4\n" +
                "0000000000 65535 f \r\n" +
                String.format("%010d 00000 n \r\n", offset1) +
                String.format("%010d 00000 n \r\n", offset2) +
                String.format("%010d 00000 n \r\n", offset3);
        baos.write(xref.getBytes(StandardCharsets.US_ASCII));

        String trailer = "trailer\n" +
                "<< /Size 4 /Root 1 0 R >>\n" +
                "startxref\n" +
                xrefOffset + "\n" +
                "%%EOF\n";
        baos.write(trailer.getBytes(StandardCharsets.US_ASCII));

        byte[] result = baos.toByteArray();
        assertTrue(result.length >= 200, "PDF 必须 >= 200 字节");
        return result;
    }

    @Test
    @DisplayName("用例1: 四类简历字段往返验证与事实派生")
    void fourCategoryProfileRoundTrip() throws Exception {
        WorkItem work = new WorkItem("w-isolated-1", "测试科技", "架构师", "2020-2024", "主导核心架构");
        ProjectItem project = new ProjectItem("p-isolated-1", "招聘系统", "TL", "2023-2024", "负责系统设计");
        EducationItem education = new EducationItem("e-isolated-1", "测试大学", "硕士", "计算机科学", "2017-2020");
        SkillItem skill = new SkillItem("s-isolated-1", "Java", "精通", "深入理解JVM与并发");
        Resume resume = new Resume(List.of(work), List.of(project), List.of(education), List.of(skill));

        Profile putRequest = new Profile(
                "", 2026, List.of("上海"), List.of(), "",
                false, false, true,
                resume, "", "", "", "", List.of(),
                false, new FollowUp(false, 168, 1)
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Profile> requestEntity = new HttpEntity<>(putRequest, headers);

        ResponseEntity<String> putResp = restTemplate.exchange(
                "/api/boss/chat/profile",
                HttpMethod.PUT,
                requestEntity,
                String.class
        );
        assertEquals(HttpStatus.OK, putResp.getStatusCode());

        ResponseEntity<String> getResp = restTemplate.getForEntity("/api/boss/chat/profile", String.class);
        assertEquals(HttpStatus.OK, getResp.getStatusCode());

        Profile profile = objectMapper.readValue(getResp.getBody(), Profile.class);
        assertNotNull(profile.version());
        assertFalse(profile.version().isBlank(), "version 必须非空");
        assertTrue(profile.enabled());
        assertFalse(profile.autoReply());
        assertFalse(profile.autoLocation());
        assertEquals(168, profile.followUp().cooldownHours());
        assertEquals(1, profile.followUp().maxPerConversation());

        assertEquals(1, profile.resume().work().size());
        assertEquals(work, profile.resume().work().get(0));

        assertEquals(1, profile.resume().project().size());
        assertEquals(project, profile.resume().project().get(0));

        assertEquals(1, profile.resume().education().size());
        assertEquals(education, profile.resume().education().get(0));

        assertEquals(1, profile.resume().skills().size());
        assertEquals(skill, profile.resume().skills().get(0));

        List<Fact> facts = profile.facts();
        long workCount = facts.stream().filter(f -> f.id().startsWith("work:")).count();
        long projectCount = facts.stream().filter(f -> f.id().startsWith("project:")).count();
        long educationCount = facts.stream().filter(f -> f.id().startsWith("education:")).count();
        long skillCount = facts.stream().filter(f -> f.id().startsWith("skill:")).count();

        assertEquals(1, workCount, "facts 必须含 work: 事实 1 条");
        assertEquals(1, projectCount, "facts 必须含 project: 事实 1 条");
        assertEquals(1, educationCount, "facts 必须含 education: 事实 1 条");
        assertEquals(1, skillCount, "facts 必须含 skill: 事实 1 条");
    }

    @Test
    @DisplayName("用例2: PDF 上传、批准与全量配置元信息验证")
    void pdfUploadApproveAndMetadata() throws Exception {
        byte[] pdfBytes = createMinimalValidPdf("b7-isolated-test");

        ByteArrayResource fileResource = new ByteArrayResource(pdfBytes) {
            @Override
            public String getFilename() {
                return "resume.pdf";
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource);

        HttpHeaders uploadHeaders = new HttpHeaders();
        uploadHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, Object>> uploadRequest = new HttpEntity<>(body, uploadHeaders);

        ResponseEntity<String> uploadResp = restTemplate.postForEntity(
                "/api/boss/config/resume-document",
                uploadRequest,
                String.class
        );
        assertEquals(HttpStatus.OK, uploadResp.getStatusCode());

        JsonNode uploadJson = objectMapper.readTree(uploadResp.getBody());
        assertTrue(uploadJson.path("success").asBoolean());
        String uploadedAssetId = uploadJson.path("resumeDocument").path("assetId").asText();
        assertNotNull(uploadedAssetId);
        assertTrue(uploadedAssetId.startsWith("sha256:"), "assetId 必须以 sha256: 开头");

        ResponseEntity<String> getDocResp = restTemplate.getForEntity("/api/boss/config/resume-document", String.class);
        assertEquals(HttpStatus.OK, getDocResp.getStatusCode());
        JsonNode getDocJson = objectMapper.readTree(getDocResp.getBody());
        assertEquals(uploadedAssetId, getDocJson.path("assetId").asText());

        ResponseEntity<String> getProfileResp = restTemplate.getForEntity("/api/boss/chat/profile", String.class);
        Profile currentProfile = objectMapper.readValue(getProfileResp.getBody(), Profile.class);

        Profile updatedProfile = new Profile(
                currentProfile.version(),
                currentProfile.graduationYear(),
                currentProfile.acceptedCities(),
                currentProfile.facts(),
                currentProfile.approvedResumeAssetId(),
                currentProfile.autoResume(),
                currentProfile.autoLocation(),
                currentProfile.enabled(),
                currentProfile.resume(),
                uploadedAssetId,
                currentProfile.resumeText(),
                currentProfile.resumeTextHash(),
                currentProfile.approvedResumeTextHash(),
                currentProfile.derivedFactIds(),
                currentProfile.autoReply(),
                currentProfile.followUp()
        );

        HttpHeaders putHeaders = new HttpHeaders();
        putHeaders.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Profile> putEntity = new HttpEntity<>(updatedProfile, putHeaders);

        ResponseEntity<String> putProfileResp = restTemplate.exchange(
                "/api/boss/chat/profile",
                HttpMethod.PUT,
                putEntity,
                String.class
        );
        assertEquals(HttpStatus.OK, putProfileResp.getStatusCode());

        ResponseEntity<String> configResp = restTemplate.getForEntity("/api/boss/config", String.class);
        assertEquals(HttpStatus.OK, configResp.getStatusCode());
        JsonNode configJson = objectMapper.readTree(configResp.getBody());

        assertTrue(configJson.has("resumeDocument"), "顶层必须存在 resumeDocument");
        assertEquals(uploadedAssetId, configJson.path("resumeDocument").path("assetId").asText());
        assertTrue(configJson.has("config"), "顶层必须存在 config");
        assertTrue(configJson.has("options"), "顶层必须存在 options");
        assertTrue(configJson.has("blacklist"), "顶层必须存在 blacklist");
        assertTrue(configJson.has("resumeImage"), "顶层必须存在 resumeImage");
    }

    @Test
    @DisplayName("用例3: 文本简历批准与修改后失效验证")
    void resumeTextApproveThenInvalidate() throws Exception {
        ResponseEntity<String> initialProfileResp = restTemplate.getForEntity("/api/boss/chat/profile", String.class);
        Profile initialProfile = objectMapper.readValue(initialProfileResp.getBody(), Profile.class);

        String textA = "这是文本A内容，详细介绍个人项目经验与能力。";
        Profile putA = new Profile(
                initialProfile.version(),
                initialProfile.graduationYear(),
                initialProfile.acceptedCities(),
                initialProfile.facts(),
                initialProfile.approvedResumeAssetId(),
                initialProfile.autoResume(),
                initialProfile.autoLocation(),
                initialProfile.enabled(),
                initialProfile.resume(),
                initialProfile.approvedPdfAssetId(),
                textA,
                "client-fake-hash",
                "client-fake-approved-hash",
                initialProfile.derivedFactIds(),
                initialProfile.autoReply(),
                initialProfile.followUp()
        );

        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> respA = restTemplate.exchange(
                "/api/boss/chat/profile",
                HttpMethod.PUT,
                new HttpEntity<>(putA, jsonHeaders),
                String.class
        );
        assertEquals(HttpStatus.OK, respA.getStatusCode());

        Profile profileA = objectMapper.readValue(respA.getBody(), Profile.class);
        assertNotNull(profileA.resumeTextHash());
        assertFalse(profileA.resumeTextHash().isBlank());
        assertEquals("", profileA.approvedResumeTextHash());
        assertTrue(profileA.facts().stream().noneMatch(f -> f.id().startsWith("resume:")));

        BossChatController.ApprovalRequest approvalReq = new BossChatController.ApprovalRequest(
                profileA.version(),
                profileA.resumeTextHash()
        );
        ResponseEntity<String> approveResp = restTemplate.postForEntity(
                "/api/boss/chat/profile/resume-text/approve",
                new HttpEntity<>(approvalReq, jsonHeaders),
                String.class
        );
        assertEquals(HttpStatus.OK, approveResp.getStatusCode());

        Profile approvedProfile = objectMapper.readValue(approveResp.getBody(), Profile.class);
        assertEquals(profileA.resumeTextHash(), approvedProfile.approvedResumeTextHash());
        assertTrue(approvedProfile.facts().stream().anyMatch(f -> f.id().startsWith("resume:")));

        String textB = "这是修改后的文本B内容，已进行部分调整。";
        Profile putB = new Profile(
                approvedProfile.version(),
                approvedProfile.graduationYear(),
                approvedProfile.acceptedCities(),
                approvedProfile.facts(),
                approvedProfile.approvedResumeAssetId(),
                approvedProfile.autoResume(),
                approvedProfile.autoLocation(),
                approvedProfile.enabled(),
                approvedProfile.resume(),
                approvedProfile.approvedPdfAssetId(),
                textB,
                "",
                approvedProfile.approvedResumeTextHash(),
                approvedProfile.derivedFactIds(),
                approvedProfile.autoReply(),
                approvedProfile.followUp()
        );

        ResponseEntity<String> respB = restTemplate.exchange(
                "/api/boss/chat/profile",
                HttpMethod.PUT,
                new HttpEntity<>(putB, jsonHeaders),
                String.class
        );
        assertEquals(HttpStatus.OK, respB.getStatusCode());

        Profile profileB = objectMapper.readValue(respB.getBody(), Profile.class);
        assertEquals("", profileB.approvedResumeTextHash());
        assertTrue(profileB.facts().stream().noneMatch(f -> f.id().startsWith("resume:")));
    }

    @Test
    @DisplayName("用例4: 记录过滤、对齐冲突与旧确认退役410")
    void recordsAndReconcileAndLegacyRetired() throws Exception {
        Suggestion blockedSug = new Suggestion(
                "s-b7-blocked", 1, "c-b7-1", "m-b7-1", "v1", "blocked", "回复文本1",
                Intent.OTHER, Action.NO_ACTION, "POLICY_BLOCKED", List.of(), "候选人来源消息1", "", List.of(), 1000L
        );
        Suggestion unknownSug = new Suggestion(
                "s-b7-unknown", 1, "c-b7-2", "m-b7-2", "v1", "unknown", "回复文本2",
                Intent.OTHER, Action.SUGGEST_REPLY, "UNKNOWN_INTENT", List.of(), "候选人来源消息2", "", List.of(), 2000L
        );

        bossChatStore.save(blockedSug);
        bossChatStore.save(unknownSug);

        ResponseEntity<String> recordsResp = restTemplate.getForEntity("/api/boss/chat/records?states=blocked", String.class);
        assertEquals(HttpStatus.OK, recordsResp.getStatusCode());
        JsonNode recordsJson = objectMapper.readTree(recordsResp.getBody());
        JsonNode recordsList = recordsJson.path("records");
        assertTrue(recordsList.isArray());
        assertTrue(recordsList.size() >= 1);
        for (JsonNode item : recordsList) {
            assertEquals("blocked", item.path("state").asText());
        }

        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);

        String reconcileBody = "{\"confirmed\":true}";
        ResponseEntity<String> reconcileResp1 = restTemplate.postForEntity(
                "/api/boss/chat/records/" + unknownSug.id() + "/reconcile",
                new HttpEntity<>(reconcileBody, jsonHeaders),
                String.class
        );
        assertEquals(HttpStatus.OK, reconcileResp1.getStatusCode());
        JsonNode reconcileJson = objectMapper.readTree(reconcileResp1.getBody());
        assertEquals("sent", reconcileJson.path("state").asText());

        ResponseEntity<String> reconcileResp2 = restTemplate.postForEntity(
                "/api/boss/chat/records/" + unknownSug.id() + "/reconcile",
                new HttpEntity<>(reconcileBody, jsonHeaders),
                String.class
        );
        assertEquals(HttpStatus.CONFLICT, reconcileResp2.getStatusCode());

        String legacyConfirmBody = "{\"version\":1,\"lastInboundMessageId\":\"m\",\"replyText\":\"x\"}";
        ResponseEntity<String> legacyResp = restTemplate.postForEntity(
                "/api/boss/chat/suggestions/anything/confirm",
                new HttpEntity<>(legacyConfirmBody, jsonHeaders),
                String.class
        );
        assertEquals(HttpStatus.GONE, legacyResp.getStatusCode());
        JsonNode legacyJson = objectMapper.readTree(legacyResp.getBody());
        assertEquals("LEGACY_SUGGESTIONS_RETIRED", legacyJson.path("code").asText());
    }

    @Test
    @DisplayName("用例5: 黑名单列表与增删验证")
    void blacklistStillWorks() throws Exception {
        ResponseEntity<String> getInitResp = restTemplate.getForEntity("/api/boss/config/blacklist", String.class);
        assertEquals(HttpStatus.OK, getInitResp.getStatusCode());
        JsonNode initialList = objectMapper.readTree(getInitResp.getBody());
        assertTrue(initialList.isArray());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String postBody = "{\"type\":\"company\",\"value\":\"B7测试公司\"}";

        ResponseEntity<String> addResp = restTemplate.postForEntity(
                "/api/boss/config/blacklist",
                new HttpEntity<>(postBody, headers),
                String.class
        );
        assertTrue(addResp.getStatusCode().is2xxSuccessful());

        ResponseEntity<String> getAfterAddResp = restTemplate.getForEntity("/api/boss/config/blacklist", String.class);
        assertEquals(HttpStatus.OK, getAfterAddResp.getStatusCode());
        JsonNode listAfterAdd = objectMapper.readTree(getAfterAddResp.getBody());
        assertTrue(listAfterAdd.isArray());

        Long createdId = null;
        for (JsonNode item : listAfterAdd) {
            if ("company".equals(item.path("type").asText()) && "B7测试公司".equals(item.path("value").asText())) {
                createdId = item.path("id").asLong();
                break;
            }
        }
        assertNotNull(createdId, "添加后必须能在黑名单列表中找到 B7测试公司");

        ResponseEntity<String> delResp = restTemplate.exchange(
                "/api/boss/config/blacklist/" + createdId,
                HttpMethod.DELETE,
                null,
                String.class
        );
        assertTrue(delResp.getStatusCode().is2xxSuccessful());

        ResponseEntity<String> getFinalResp = restTemplate.getForEntity("/api/boss/config/blacklist", String.class);
        assertEquals(HttpStatus.OK, getFinalResp.getStatusCode());
        JsonNode finalArray = objectMapper.readTree(getFinalResp.getBody());
        boolean foundDeleted = false;
        for (JsonNode item : finalArray) {
            if (item.path("id").asLong() == createdId) {
                foundDeleted = true;
                break;
            }
        }
        assertFalse(foundDeleted, "删除后 B7测试公司 不应存在于黑名单列表中");
    }
}
