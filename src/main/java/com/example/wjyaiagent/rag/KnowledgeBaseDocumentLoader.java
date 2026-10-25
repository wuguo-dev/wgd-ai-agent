package com.example.wjyaiagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class KnowledgeBaseDocumentLoader {

    private final String sourceDirectory;
    private final String knowledgeBaseId;
    private final ResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();

    public KnowledgeBaseDocumentLoader(
            @Value("${knowledge-base.rag.source-directory:classpath:document}") String sourceDirectory,
            @Value("${knowledge-base.rag.knowledge-base-id:default}") String knowledgeBaseId) {
        this.sourceDirectory = sourceDirectory.trim();
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public List<Document> loadMarkdowns() {
        if (sourceDirectory.startsWith("classpath:")) return loadClasspathMarkdowns();
        Path directory = Path.of(sourceDirectory).toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) return List.of();
        try {
            return Files.list(directory)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".md"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .flatMap(path -> parse(path).stream())
                    .toList();
        } catch (IOException error) {
            throw new IllegalStateException("无法读取知识库文档目录", error);
        }
    }

    private List<Document> loadClasspathMarkdowns() {
        String pattern = sourceDirectory.endsWith("/") ? sourceDirectory + "*.md" : sourceDirectory + "/*.md";
        try {
            Resource[] resources = resourceResolver.getResources(pattern);
            return java.util.Arrays.stream(resources)
                    .filter(Resource::exists)
                    .sorted(Comparator.comparing(resource -> resource.getFilename() == null ? "" : resource.getFilename()))
                    .flatMap(resource -> parse(resource).stream())
                    .toList();
        } catch (IOException error) {
            throw new IllegalStateException("无法读取 classpath 知识库文档目录：" + sourceDirectory, error);
        }
    }

    private List<Document> parse(Path path) {
        try {
            return parseContent(Files.readString(path, StandardCharsets.UTF_8), path.getFileName().toString());
        } catch (IOException error) {
            throw new IllegalStateException("无法读取知识库文档：" + path.getFileName(), error);
        }
    }

    private List<Document> parse(Resource resource) {
        String sourceName = resource.getFilename() == null ? "unknown.md" : resource.getFilename();
        try {
            String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return parseContent(content, sourceName);
        } catch (IOException error) {
            throw new IllegalStateException("无法读取知识库文档：" + sourceName, error);
        }
    }

    private List<Document> parseContent(String content, String sourceName) {
            String checksum = sha256(content);
            Map<String, String> frontMatter = parseFrontMatter(content);
            String documentId = frontMatter.getOrDefault("document_id", slug(sourceName));
            String documentVersion = frontMatter.getOrDefault("version", "1") + "-" + checksum.substring(0, 12);
            String rootTitle = frontMatter.getOrDefault("title", sourceName.replaceFirst("\\.md$", ""));
            String body = withoutFrontMatter(content);

            List<Document> blocks = new ArrayList<>();
            String currentTitle = null;
            StringBuilder currentText = new StringBuilder();
            int blockIndex = 0;
            for (String line : body.split("\\R", -1)) {
                if (line.startsWith("## FAQ-")) {
                    if (!currentText.toString().isBlank()) {
                        blocks.add(buildBlock(currentText.toString().trim(), sourceName, documentId,
                                documentVersion, checksum, rootTitle, currentTitle, blockIndex++));
                        currentText.setLength(0);
                    }
                    currentTitle = line.substring(3).trim();
                } else if (currentTitle == null) {
                    continue;
                }
                currentText.append(line).append('\n');
            }
            if (currentTitle != null && !currentText.toString().isBlank()) {
                blocks.add(buildBlock(currentText.toString().trim(), sourceName, documentId,
                        documentVersion, checksum, rootTitle, currentTitle, blockIndex));
            }
            return blocks;
    }

    private Document buildBlock(String text, String sourceName, String documentId, String documentVersion,
                                String checksum, String rootTitle, String title, int chunkIndex) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("knowledgeBaseId", knowledgeBaseId);
        metadata.put("documentId", documentId);
        metadata.put("documentVersion", documentVersion);
        metadata.put("chunkIndex", chunkIndex);
        metadata.put("titlePath", rootTitle + " > " + title);
        metadata.put("sourceName", sourceName);
        metadata.put("checksum", checksum);
        return Document.builder().text(text).metadata(metadata).build();
    }

    private static Map<String, String> parseFrontMatter(String content) {
        Map<String, String> result = new HashMap<>();
        if (!content.startsWith("---")) return result;
        String[] lines = content.split("\\R", -1);
        for (int index = 1; index < lines.length; index++) {
            if (lines[index].trim().equals("---")) break;
            int separator = lines[index].indexOf(':');
            if (separator > 0) {
                result.put(lines[index].substring(0, separator).trim(),
                        lines[index].substring(separator + 1).trim());
            }
        }
        return result;
    }

    private static String withoutFrontMatter(String content) {
        if (!content.startsWith("---")) return content;
        int end = content.indexOf("\n---", 3);
        return end < 0 ? content : content.substring(end + 5);
    }

    private static String slug(String fileName) {
        return fileName.replaceFirst("\\.md$", "").replaceAll("[^A-Za-z0-9_-]+", "-").toLowerCase();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("无法计算文档 checksum", error);
        }
    }
}
