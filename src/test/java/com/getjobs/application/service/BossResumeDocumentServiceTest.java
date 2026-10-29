package com.getjobs.application.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BossResumeDocumentServiceTest {

    @TempDir
    Path tempDir;

    private BossResumeDocumentService service;

    @BeforeEach
    void setUp() {
        service = new BossResumeDocumentService(tempDir.toString());
    }

    /**
     * 构造结构完整的最小合法 PDF。
     * 包含 %PDF-1.4、Catalog、Pages、Page 3个对象、xref 表、trailer、startxref、%%EOF，总长 >= 200 字节。
     */
    private byte[] createValidPdf(String customMarker) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        byte[] header = "%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII);
        baos.write(header);

        int offset1 = baos.size();
        byte[] obj1 = ("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n").getBytes(StandardCharsets.US_ASCII);
        baos.write(obj1);

        int offset2 = baos.size();
        byte[] obj2 = ("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n").getBytes(StandardCharsets.US_ASCII);
        baos.write(obj2);

        int offset3 = baos.size();
        // 插入 customMarker 保证内容唯一性，并填充 padding 确保整体大小 >= 200 字节
        String markerComment = "% marker: " + customMarker + "\n";
        String paddingComment = "% padding: " + "A".repeat(150) + "\n";
        byte[] obj3 = ("3 0 obj\n<< /Type /Page /Parent 2 0 R >>\n" + markerComment + paddingComment + "endobj\n").getBytes(StandardCharsets.US_ASCII);
        baos.write(obj3);

        int xrefOffset = baos.size();
        StringBuilder xref = new StringBuilder();
        xref.append("xref\n");
        xref.append("0 4\n");
        xref.append("0000000000 65535 f \r\n");
        xref.append(String.format("%010d 00000 n \r\n", offset1));
        xref.append(String.format("%010d 00000 n \r\n", offset2));
        xref.append(String.format("%010d 00000 n \r\n", offset3));
        baos.write(xref.toString().getBytes(StandardCharsets.US_ASCII));

        StringBuilder trailer = new StringBuilder();
        trailer.append("trailer\n");
        trailer.append("<< /Size 4 /Root 1 0 R >>\n");
        trailer.append("startxref\n");
        trailer.append(xrefOffset).append("\n");
        trailer.append("%%EOF\n");
        baos.write(trailer.toString().getBytes(StandardCharsets.US_ASCII));

        byte[] result = baos.toByteArray();
        assertTrue(result.length >= 200, "PDF 必须 >= 200 字节");
        return result;
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("初始状态：无文件时 currentAssetId 为空串，getCurrentDocument 为 empty，getMetadata 正确")
    void initialEmptyState() {
        assertEquals("", service.currentAssetId());
        assertTrue(service.getCurrentDocument().isEmpty());
        assertTrue(service.getCurrentDocumentPath().isEmpty());

        Map<String, Object> meta = service.getMetadata();
        assertEquals(false, meta.get("exists"));
        assertEquals("", meta.get("assetId"));
        assertEquals(BossResumeDocumentService.PREVIEW_URL, meta.get("previewUrl"));
        assertNull(meta.get("fileName"));
        assertNull(meta.get("size"));
    }

    @Test
    @DisplayName("上传合规 PDF 后正常保存，assetId、大小、元信息匹配，替换后 assetId 改变且目录内只剩 1 个 pdf")
    void saveAndReplaceValidPdf() throws Exception {
        byte[] pdf1Bytes = createValidPdf("v1");
        MockMultipartFile file1 = new MockMultipartFile("file", "my_resume.PDF", "application/pdf", pdf1Bytes);

        var info1 = service.save(file1);
        Path targetPath = tempDir.resolve("document.pdf");
        assertTrue(Files.exists(targetPath));
        assertEquals("document.pdf", info1.fileName());
        assertEquals(pdf1Bytes.length, info1.size());
        assertEquals("sha256:" + sha256Hex(pdf1Bytes), info1.assetId());
        assertEquals(info1.assetId(), service.currentAssetId());

        Map<String, Object> meta1 = service.getMetadata();
        assertEquals(true, meta1.get("exists"));
        assertEquals("document.pdf", meta1.get("fileName"));
        assertEquals(pdf1Bytes.length, ((Number) meta1.get("size")).longValue());
        assertEquals("sha256:" + sha256Hex(pdf1Bytes), meta1.get("assetId"));
        assertEquals(BossResumeDocumentService.PREVIEW_URL, meta1.get("previewUrl"));
        assertNotNull(meta1.get("updatedAt"));

        // 替换第二份内容不同的 PDF
        byte[] pdf2Bytes = createValidPdf("v2");
        MockMultipartFile file2 = new MockMultipartFile("file", "resume_updated.pdf", "application/pdf", pdf2Bytes);

        var info2 = service.save(file2);
        assertEquals(pdf2Bytes.length, info2.size());
        assertEquals("sha256:" + sha256Hex(pdf2Bytes), info2.assetId());
        assertNotEquals(info1.assetId(), info2.assetId());
        assertEquals(info2.assetId(), service.currentAssetId());

        // 验证目录内只有 1 个 pdf 文件
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tempDir, "*.pdf")) {
            int count = 0;
            for (Path entry : stream) {
                count++;
                assertEquals("document.pdf", entry.getFileName().toString());
            }
            assertEquals(1, count);
        }
    }

    @Test
    @DisplayName("负例：PNG 字节冒充 PDF，校验失败抛异常且旧文件保持不变")
    void pngDisguisedAsPdfFailsAndPreservesExisting() throws Exception {
        byte[] validPdf = createValidPdf("initial");
        service.save(new MockMultipartFile("file", "resume.pdf", "application/pdf", validPdf));
        String originalAssetId = service.currentAssetId();
        byte[] originalBytes = Files.readAllBytes(tempDir.resolve("document.pdf"));

        byte[] fakePdfBytes = new byte[300];
        // PNG magic
        fakePdfBytes[0] = (byte) 0x89;
        fakePdfBytes[1] = 'P';
        fakePdfBytes[2] = 'N';
        fakePdfBytes[3] = 'G';

        MockMultipartFile fakeFile = new MockMultipartFile("file", "fake.pdf", "application/pdf", fakePdfBytes);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.save(fakeFile));
        assertEquals("文件不是有效的 PDF", ex.getMessage());

        // 旧文件保持原样
        assertEquals(originalAssetId, service.currentAssetId());
        assertArrayEquals(originalBytes, Files.readAllBytes(tempDir.resolve("document.pdf")));
    }

    @Test
    @DisplayName("负例：缺少 %%EOF 的截断 PDF 校验失败")
    void truncatedPdfWithoutEofFails() throws Exception {
        byte[] validPdf = createValidPdf("truncated");
        String pdfStr = new String(validPdf, StandardCharsets.US_ASCII);
        String withoutEof = pdfStr.replace("%%EOF", "%!NO!");
        byte[] invalidBytes = withoutEof.getBytes(StandardCharsets.US_ASCII);

        MockMultipartFile file = new MockMultipartFile("file", "truncated.pdf", "application/pdf", invalidBytes);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.save(file));
        assertEquals("文件不是有效的 PDF", ex.getMessage());
    }

    @Test
    @DisplayName("负例：空文件上传校验失败")
    void emptyFileFails() {
        MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.save(emptyFile));
        assertEquals("请选择要上传的 PDF 简历", ex.getMessage());

        IllegalArgumentException exNull = assertThrows(IllegalArgumentException.class, () -> service.save(null));
        assertEquals("请选择要上传的 PDF 简历", exNull.getMessage());
    }

    @Test
    @DisplayName("负例：超过 10MB 校验失败")
    void oversizedFileFails() {
        byte[] oversized = new byte[(int) (BossResumeDocumentService.MAX_FILE_SIZE + 1)];
        MockMultipartFile file = new MockMultipartFile("file", "large.pdf", "application/pdf", oversized);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.save(file));
        assertEquals("PDF 大小不能超过 10MB", ex.getMessage());
    }

    @Test
    @DisplayName("负例：非 .pdf 扩展名校验失败")
    void nonPdfExtensionFails() throws Exception {
        byte[] validPdf = createValidPdf("text");
        MockMultipartFile file = new MockMultipartFile("file", "resume.txt", "text/plain", validPdf);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.save(file));
        assertEquals("请上传 PDF 格式的简历", ex.getMessage());
    }
}
