package com.taiwei.aiagent.agent.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextMentionResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void returnsOriginalMessageWhenNoMention() {
        String msg = "帮我优化这段代码";
        assertEquals(msg, ContextMentionResolver.augment(tempDir.toString(), msg));
    }

    @Test
    void returnsOriginalMessageWhenMentionedFileDoesNotExist() {
        String msg = "联系 someone@example.com 获取帮助";
        assertEquals(msg, ContextMentionResolver.augment(tempDir.toString(), msg));
    }

    @Test
    void attachesMentionedFileContent() throws Exception {
        Files.writeString(tempDir.resolve("Config.java"), "public class Config {}", StandardCharsets.UTF_8);

        String result = ContextMentionResolver.augment(tempDir.toString(), "解释一下 @Config.java 的作用");

        assertTrue(result.startsWith("解释一下 @Config.java 的作用"));
        assertTrue(result.contains("<file path=\"Config.java\">"));
        assertTrue(result.contains("public class Config {}"));
    }

    @Test
    void attachesRelativePathInSubdirectory() throws Exception {
        Path sub = tempDir.resolve("src");
        Files.createDirectories(sub);
        Files.writeString(sub.resolve("app.properties"), "key=value", StandardCharsets.UTF_8);

        String result = ContextMentionResolver.augment(tempDir.toString(), "检查 @src/app.properties 配置");

        assertTrue(result.contains("<file path=\"src/app.properties\">"));
        assertTrue(result.contains("key=value"));
    }

    @Test
    void attachesDirectoryListing() throws Exception {
        Path sub = tempDir.resolve("module");
        Files.createDirectories(sub);
        Files.writeString(sub.resolve("A.java"), "class A {}", StandardCharsets.UTF_8);
        Files.writeString(sub.resolve("B.java"), "class B {}", StandardCharsets.UTF_8);

        String result = ContextMentionResolver.augment(tempDir.toString(), "看一下 @module 里有什么");

        assertTrue(result.contains("<directory path=\"module\">"));
        assertTrue(result.contains("A.java"));
        assertTrue(result.contains("B.java"));
    }

    @Test
    void truncatesOversizedFile() throws Exception {
        Files.writeString(tempDir.resolve("big.txt"), "x".repeat(30000), StandardCharsets.UTF_8);

        String result = ContextMentionResolver.augment(tempDir.toString(), "@big.txt 是什么");

        assertTrue(result.contains("已截断"));
        assertFalse(result.contains("x".repeat(20001)));
    }

    @Test
    void handlesNullBasePathWithRelativeMention() {
        String msg = "看看 @src/Main.java";
        assertEquals(msg, ContextMentionResolver.augment((String) null, msg));
    }
}
