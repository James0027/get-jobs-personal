package com.getjobs.application.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 管理 Boss PDF 文档简历。
 */
@Service
public class BossResumeDocumentService {

    public static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    public static final String PREVIEW_URL = "/api/boss/config/resume-document/content";

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PDF_EOF = "%%EOF".getBytes(StandardCharsets.US_ASCII);
    private static final int MIN_PDF_SIZE = 200;
    private static final int HEADER_CHECK_LIMIT = 1024;
    private static final int TRAILER_CHECK_LIMIT = 2048;

    private final Path storageDirectory;
    private final Path documentPath;

    public record ResumeDocumentInfo(Path path, String fileName, long size, long updatedAt, String assetId) {}

    public BossResumeDocumentService(@Value("${boss.resume.document.dir:}") String directory) {
        if (directory == null || directory.isBlank()) {
            this.storageDirectory = Paths.get(System.getProperty("user.dir"), "data", "boss", "resume")
                    .toAbsolutePath()
                    .normalize();
        } else {
            this.storageDirectory = Paths.get(directory).toAbsolutePath().normalize();
        }
        this.documentPath = this.storageDirectory.resolve("document.pdf").toAbsolutePath().normalize();
    }

    public synchronized ResumeDocumentInfo save(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的 PDF 简历");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("PDF 大小不能超过 10MB");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("请上传 PDF 格式的简历");
        }

        Files.createDirectories(storageDirectory);
        Path temporaryFile = Files.createTempFile(storageDirectory, "resume-doc-", ".tmp");
        try {
            file.transferTo(temporaryFile);
            long actualSize = Files.size(temporaryFile);
            if (actualSize > MAX_FILE_SIZE) {
                throw new IllegalArgumentException("PDF 大小不能超过 10MB");
            }

            validatePdfContent(temporaryFile, actualSize);

            moveReplacing(temporaryFile, documentPath);
            deleteOtherPdfs(documentPath);
            return toInfo(documentPath);
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    public synchronized Optional<ResumeDocumentInfo> getCurrentDocument() {
        if (!Files.isRegularFile(documentPath) || !Files.isReadable(documentPath)) {
            return Optional.empty();
        }
        try {
            return Optional.of(toInfo(documentPath));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public synchronized Optional<Path> getCurrentDocumentPath() {
        if (Files.isRegularFile(documentPath) && Files.isReadable(documentPath)) {
            return Optional.of(documentPath);
        }
        return Optional.empty();
    }

    public String currentAssetId() {
        if (!Files.isRegularFile(documentPath) || !Files.isReadable(documentPath)) {
            return "";
        }
        try {
            byte[] bytes = Files.readAllBytes(documentPath);
            return "sha256:" + computeSha256Hex(bytes);
        } catch (Exception e) {
            return "";
        }
    }

    public Map<String, Object> getMetadata() {
        return getCurrentDocument()
                .map(info -> Map.<String, Object>of(
                        "exists", true,
                        "fileName", info.fileName(),
                        "size", info.size(),
                        "updatedAt", info.updatedAt(),
                        "assetId", info.assetId(),
                        "previewUrl", PREVIEW_URL
                ))
                .orElseGet(() -> Map.of(
                        "exists", false,
                        "assetId", "",
                        "previewUrl", PREVIEW_URL
                ));
    }

    private void validatePdfContent(Path path, long size) throws IOException {
        if (size < MIN_PDF_SIZE) {
            throw new IllegalArgumentException("文件不是有效的 PDF");
        }

        byte[] allBytes = Files.readAllBytes(path);

        // 首 1024 字节含 ASCII %PDF-
        int headLength = (int) Math.min(allBytes.length, HEADER_CHECK_LIMIT);
        if (!containsSequence(allBytes, 0, headLength, PDF_MAGIC)) {
            throw new IllegalArgumentException("文件不是有效的 PDF");
        }

        // 末 2048 字节含 ASCII %%EOF
        int tailLength = (int) Math.min(allBytes.length, TRAILER_CHECK_LIMIT);
        int tailStart = allBytes.length - tailLength;
        if (!containsSequence(allBytes, tailStart, tailLength, PDF_EOF)) {
            throw new IllegalArgumentException("文件不是有效的 PDF");
        }
    }

    private boolean containsSequence(byte[] data, int start, int length, byte[] target) {
        if (length < target.length) {
            return false;
        }
        int end = start + length - target.length;
        for (int i = start; i <= end; i++) {
            boolean match = true;
            for (int j = 0; j < target.length; j++) {
                if (data[i + j] != target[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteOtherPdfs(Path retained) throws IOException {
        if (!Files.isDirectory(storageDirectory)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(storageDirectory, "*.pdf")) {
            for (Path entry : stream) {
                if (!entry.toAbsolutePath().normalize().equals(retained)) {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }

    private ResumeDocumentInfo toInfo(Path path) throws IOException {
        String fileName = path.getFileName().toString();
        long size = Files.size(path);
        long updatedAt = Files.getLastModifiedTime(path).toMillis();
        byte[] bytes = Files.readAllBytes(path);
        String assetId = "sha256:" + computeSha256Hex(bytes);
        return new ResumeDocumentInfo(path, fileName, size, updatedAt, assetId);
    }

    private String computeSha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
