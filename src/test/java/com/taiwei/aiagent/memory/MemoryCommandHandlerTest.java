package com.taiwei.aiagent.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryCommandHandlerTest {

    private MemoryManager manager;
    private MemoryCommandHandler handler;

    private void newHandler() {
        manager = new MemoryManager(new MemoryStore());
        handler = new MemoryCommandHandler(manager);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.dispose();
        }
    }

    @Test
    void tryHandle_chineseRememberPattern_storesMemoryAndConfirms() {
        newHandler();

        Optional<String> reply = handler.tryHandle("记住 我喜欢用 tab 缩进");

        assertTrue(reply.isPresent());
        assertTrue(reply.get().contains("我喜欢用 tab 缩进"));
        assertEquals(1, manager.getMemoryCount());
    }

    @Test
    void tryHandle_englishRememberPattern_storesMemoryAndConfirms() {
        newHandler();

        Optional<String> reply = handler.tryHandle("remember this: I prefer Java over Kotlin");

        assertTrue(reply.isPresent());
        assertEquals(1, manager.getMemoryCount());
    }

    @Test
    void tryHandle_chineseForgetPattern_removesMatchingMemory() {
        newHandler();
        manager.remember("user prefers dark theme", MemoryCategory.PREFERENCE, List.of("theme"), 5);

        Optional<String> reply = handler.tryHandle("忘了 dark theme");

        assertTrue(reply.isPresent());
        assertEquals(0, manager.getMemoryCount());
    }

    @Test
    void tryHandle_englishForgetPattern_removesMatchingMemory() {
        newHandler();
        manager.remember("user prefers dark theme", MemoryCategory.PREFERENCE, List.of("theme"), 5);

        Optional<String> reply = handler.tryHandle("forget about dark theme");

        assertTrue(reply.isPresent());
        assertEquals(0, manager.getMemoryCount());
    }

    @Test
    void tryHandle_recallPattern_returnsMatchingMemoriesWithoutDeleting() {
        newHandler();
        manager.remember("user's preferred build tool is Gradle", MemoryCategory.FACT, List.of("build"), 5);

        Optional<String> reply = handler.tryHandle("我上次说的 build tool");

        assertTrue(reply.isPresent());
        assertTrue(reply.get().contains("Gradle"));
        assertEquals(1, manager.getMemoryCount(), "recall must not delete memories");
    }

    @Test
    void tryHandle_recallPattern_noMatch_saysSoWithoutError() {
        newHandler();

        Optional<String> reply = handler.tryHandle("what do you know about quantum computing");

        assertTrue(reply.isPresent());
        assertTrue(reply.get().contains("quantum computing"));
    }

    @Test
    void tryHandle_ordinaryChatMessage_isNotIntercepted() {
        newHandler();

        Optional<String> reply = handler.tryHandle("can you help me fix this bug?");

        assertTrue(reply.isEmpty());
        assertEquals(0, manager.getMemoryCount());
    }

    @Test
    void tryHandle_blankText_isNotIntercepted() {
        newHandler();
        assertTrue(handler.tryHandle("   ").isEmpty());
        assertTrue(handler.tryHandle(null).isEmpty());
    }
}
