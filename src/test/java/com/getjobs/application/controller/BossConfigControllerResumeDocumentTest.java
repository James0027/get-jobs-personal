package com.getjobs.application.controller;

import com.getjobs.application.entity.BlacklistEntity;
import com.getjobs.application.entity.BossConfigEntity;
import com.getjobs.application.entity.BossOptionEntity;
import com.getjobs.application.service.BossResumeDocumentService;
import com.getjobs.application.service.BossResumeImageService;
import com.getjobs.application.service.BossService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BossConfigControllerResumeDocumentTest {

    @TempDir
    Path tempDir;

    private BossService bossService;
    private BossResumeImageService bossResumeImageService;
    private BossResumeDocumentService bossResumeDocumentService;
    private BossConfigController controller;

    @BeforeEach
    void setUp() {
        bossService = mock(BossService.class);
        bossResumeImageService = mock(BossResumeImageService.class);
        bossResumeDocumentService = new BossResumeDocumentService(tempDir.toString());

        when(bossService.getFirstConfig()).thenReturn(new BossConfigEntity());
        when(bossService.getOptionsByType(anyString())).thenReturn(Collections.emptyList());
        when(bossService.getAllBlacklist()).thenReturn(Collections.emptyList());
        when(bossService.getIndustryGroups(any())).thenReturn(Collections.emptyList());
        when(bossResumeImageService.getMetadata()).thenReturn(Map.of("exists", false));

        controller = new BossConfigController(bossService, bossResumeImageService, bossResumeDocumentService);
    }

    private byte[] createValidPdf(String marker) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
        int offset1 = baos.size();
        baos.write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n".getBytes(StandardCharsets.US_ASCII));
        int offset2 = baos.size();
        baos.write("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n".getBytes(StandardCharsets.US_ASCII));
        int offset3 = baos.size();
        String body = "3 0 obj\n<< /Type /Page /Parent 2 0 R >>\n% " + marker + "\n% " + "X".repeat(150) + "\nendobj\n";
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
    @DisplayName("getAllBossConfig 结果包含 resumeDocument 键，且旧顶层键均保留")
    void getAllBossConfigIncludesResumeDocument() {
        Map<String, Object> allConfig = controller.getAllBossConfig();

        assertTrue(allConfig.containsKey("resumeDocument"));
        assertTrue(allConfig.containsKey("config"));
        assertTrue(allConfig.containsKey("options"));
        assertTrue(allConfig.containsKey("industryGroups"));
        assertTrue(allConfig.containsKey("industryCatalog"));
        assertTrue(allConfig.containsKey("blacklist"));
        assertTrue(allConfig.containsKey("resumeImage"));

        @SuppressWarnings("unchecked")
        Map<String, Object> resumeDocMeta = (Map<String, Object>) allConfig.get("resumeDocument");
        assertNotNull(resumeDocMeta);
        assertEquals(false, resumeDocMeta.get("exists"));
        assertEquals("", resumeDocMeta.get("assetId"));
    }

    @Test
    @DisplayName("getResumeDocumentMetadata 返回独立元信息")
    void getResumeDocumentMetadata() {
        Map<String, Object> meta = controller.getResumeDocumentMetadata();
        assertNotNull(meta);
        assertEquals(false, meta.get("exists"));
        assertEquals("", meta.get("assetId"));
    }

    @Test
    @DisplayName("uploadResumeDocument 上传合法文件返回 200 且 assetId 非空；上传非法文件返回 400")
    void uploadResumeDocument() throws Exception {
        byte[] validPdf = createValidPdf("ctrl-test");
        MockMultipartFile validFile = new MockMultipartFile("file", "resume.pdf", "application/pdf", validPdf);

        ResponseEntity<Map<String, Object>> response = controller.uploadResumeDocument(validFile);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().get("success"));
        assertEquals("PDF 简历上传成功", response.getBody().get("message"));

        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) response.getBody().get("resumeDocument");
        assertNotNull(meta);
        assertTrue(((String) meta.get("assetId")).startsWith("sha256:"));

        // 非法文件测试（空文件）
        MockMultipartFile invalidFile = new MockMultipartFile("file", "bad.pdf", "application/pdf", new byte[0]);
        ResponseEntity<Map<String, Object>> badResponse = controller.uploadResumeDocument(invalidFile);
        assertEquals(HttpStatus.BAD_REQUEST, badResponse.getStatusCode());
        assertNotNull(badResponse.getBody());
        assertEquals(false, badResponse.getBody().get("success"));
    }

    @Test
    @DisplayName("getResumeDocumentContent：有文件时 200 且为 application/pdf，无文件时 404")
    void getResumeDocumentContent() throws Exception {
        // 无文件时 404
        ResponseEntity<Resource> emptyResponse = controller.getResumeDocumentContent();
        assertEquals(HttpStatus.NOT_FOUND, emptyResponse.getStatusCode());

        // 上传文件后 200
        byte[] validPdf = createValidPdf("content-test");
        controller.uploadResumeDocument(new MockMultipartFile("file", "resume.pdf", "application/pdf", validPdf));

        ResponseEntity<Resource> contentResponse = controller.getResumeDocumentContent();
        assertEquals(HttpStatus.OK, contentResponse.getStatusCode());
        assertEquals(MediaType.APPLICATION_PDF, contentResponse.getHeaders().getContentType());
        assertEquals("inline", contentResponse.getHeaders().getFirst(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION));
        assertEquals("no-store", contentResponse.getHeaders().getCacheControl());
        assertEquals(validPdf.length, contentResponse.getHeaders().getContentLength());
        assertNotNull(contentResponse.getBody());
    }
}
