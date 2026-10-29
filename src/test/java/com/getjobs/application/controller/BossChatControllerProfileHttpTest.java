package com.getjobs.application.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.chat.*;
import com.getjobs.application.service.BossResumeDocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BossChatControllerProfileHttpTest {

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;
    private BossResumeDocumentService documents;

    @BeforeEach
    void setUp() throws IOException {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        BossChatStore store = new BossChatStore(jdbcTemplate, new ObjectMapper());
        store.init();

        documents = new BossResumeDocumentService(tempDir.toString());
        byte[] pdfBytes = createMinimalValidPdf();
        documents.save(new MockMultipartFile("file", "resume.pdf", "application/pdf", pdfBytes));

        BossAiWorkflowService workflow = mock(BossAiWorkflowService.class);
        BossChatMonitor monitor = mock(BossChatMonitor.class);
        BossChatBrowserGateway browser = mock(BossChatBrowserGateway.class);

        BossChatController controller = new BossChatController(store, workflow, monitor, browser, documents);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private byte[] createMinimalValidPdf() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
        int offset1 = baos.size();
        baos.write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n".getBytes(StandardCharsets.US_ASCII));
        int offset2 = baos.size();
        baos.write("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n".getBytes(StandardCharsets.US_ASCII));
        int offset3 = baos.size();
        String body = "3 0 obj\n<< /Type /Page /Parent 2 0 R >>\n% Padding to satisfy size requirement\n% " + "A".repeat(120) + "\nendobj\n";
        baos.write(body.getBytes(StandardCharsets.US_ASCII));
        int xrefOffset = baos.size();
        baos.write(("xref\n0 4\n0000000000 65535 f \r\n" +
                String.format("%010d 00000 n \r\n", offset1) +
                String.format("%010d 00000 n \r\n", offset2) +
                String.format("%010d 00000 n \r\n", offset3)).getBytes(StandardCharsets.US_ASCII));
        baos.write(("trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n" + xrefOffset + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII));
        return baos.toByteArray();
    }

    @Test
    @DisplayName("用例1: PUT /api/boss/chat/profile 保存初始资料，文本未批准")
    void putProfileSuccess() throws Exception {
        String currentPdf = documents.currentAssetId();
        String body = """
                {
                  "version": "x",
                  "graduationYear": 2026,
                  "acceptedCities": [],
                  "facts": [],
                  "approvedResumeAssetId": "",
                  "autoResume": true,
                  "autoLocation": false,
                  "enabled": true,
                  "resume": {"work": [], "project": [], "education": [], "skills": []},
                  "approvedPdfAssetId": "%s",
                  "resumeText": "一段简历文本",
                  "resumeTextHash": "forged",
                  "approvedResumeTextHash": "forged",
                  "derivedFactIds": [],
                  "autoReply": false,
                  "followUp": {"enabled": false, "cooldownHours": 168, "maxPerConversation": 1}
                }
                """.formatted(currentPdf);

        mockMvc.perform(put("/api/boss/chat/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedResumeTextHash").value(""))
                .andExpect(jsonPath("$.resumeTextHash", matchesPattern("^[0-9a-f]{64}$")))
                .andExpect(jsonPath("$.facts[?(@ =~ /^resume:/)]", hasSize(0)));
    }

    @Test
    @DisplayName("用例2: approvedPdfAssetId 改成 sha256:deadbeef 时返回 400 且包含提示")
    void putProfileRejectsMismatchedApprovedPdf() throws Exception {
        String body = """
                {
                  "version": "x",
                  "graduationYear": 2026,
                  "acceptedCities": [],
                  "facts": [],
                  "approvedResumeAssetId": "",
                  "autoResume": true,
                  "autoLocation": false,
                  "enabled": true,
                  "resume": {"work": [], "project": [], "education": [], "skills": []},
                  "approvedPdfAssetId": "sha256:deadbeef",
                  "resumeText": "一段简历文本",
                  "resumeTextHash": "forged",
                  "approvedResumeTextHash": "forged",
                  "derivedFactIds": [],
                  "autoReply": false,
                  "followUp": {"enabled": false, "cooldownHours": 168, "maxPerConversation": 1}
                }
                """;

        mockMvc.perform(put("/api/boss/chat/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("请先上传并批准当前 PDF 简历")));
    }

    @Test
    @DisplayName("用例3: autoResume=true 且 approvedPdfAssetId 为空串时返回 400")
    void putProfileRejectsEmptyApprovedPdfWhenAutoResume() throws Exception {
        String body = """
                {
                  "version": "x",
                  "graduationYear": 2026,
                  "acceptedCities": [],
                  "facts": [],
                  "approvedResumeAssetId": "",
                  "autoResume": true,
                  "autoLocation": false,
                  "enabled": true,
                  "resume": {"work": [], "project": [], "education": [], "skills": []},
                  "approvedPdfAssetId": "",
                  "resumeText": "一段简历文本",
                  "resumeTextHash": "forged",
                  "approvedResumeTextHash": "forged",
                  "derivedFactIds": [],
                  "autoReply": false,
                  "followUp": {"enabled": false, "cooldownHours": 168, "maxPerConversation": 1}
                }
                """;

        mockMvc.perform(put("/api/boss/chat/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("用例4: 批准简历文本成功后哈希一致并产生事实，重放同一请求返回 409")
    void approveResumeTextSuccessAndConflictOnReplay() throws Exception {
        String currentPdf = documents.currentAssetId();
        String saveBody = """
                {
                  "version": "x",
                  "graduationYear": 2026,
                  "acceptedCities": [],
                  "facts": [],
                  "approvedResumeAssetId": "",
                  "autoResume": true,
                  "autoLocation": false,
                  "enabled": true,
                  "resume": {"work": [], "project": [], "education": [], "skills": []},
                  "approvedPdfAssetId": "%s",
                  "resumeText": "一段简历文本",
                  "resumeTextHash": "forged",
                  "approvedResumeTextHash": "forged",
                  "derivedFactIds": [],
                  "autoReply": false,
                  "followUp": {"enabled": false, "cooldownHours": 168, "maxPerConversation": 1}
                }
                """.formatted(currentPdf);

        String savedJson = mockMvc.perform(put("/api/boss/chat/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        ObjectMapper om = new ObjectMapper();
        var node = om.readTree(savedJson);
        String version = node.get("version").asText();
        String resumeTextHash = node.get("resumeTextHash").asText();

        String approveBody = """
                {
                  "expectedProfileVersion": "%s",
                  "expectedTextHash": "%s"
                }
                """.formatted(version, resumeTextHash);

        mockMvc.perform(post("/api/boss/chat/profile/resume-text/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedResumeTextHash").value(resumeTextHash))
                .andExpect(jsonPath("$.facts[*].id", hasItem(startsWith("resume:"))))
                .andExpect(jsonPath("$.derivedFactIds", hasSize(greaterThan(0))));

        mockMvc.perform(post("/api/boss/chat/profile/resume-text/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody))
                .andExpect(status().is(409));
    }

    @Test
    @DisplayName("用例5: GET records 过滤参数校验，非法状态 400，合法状态 200")
    void getRecordsValidation() throws Exception {
        mockMvc.perform(get("/api/boss/chat/records?states=bogus"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/boss/chat/records?states=unknown&limit=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records", isA(Iterable.class)));
    }
}
