package com.devcli.memory;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemorySubjectExtractorTest {

    @Test
    void fastjsonAndJacksonMapToSameJsonLibrarySubject() {
        String s1 = MemorySubjectExtractor.extract("项目使用 Fastjson 做序列化", Map.of());
        String s2 = MemorySubjectExtractor.extract("出于合规要求改用 Jackson", Map.of());
        assertEquals("project.json_library", s1);
        assertEquals("project.json_library", s2);
        assertEquals(s1, s2, "Fastjson 与 Jackson 必须归并到同一主题，后写的才能覆盖先写的");
    }

    @Test
    void explicitSubjectInMetadataWins() {
        String s = MemorySubjectExtractor.extract("随便什么内容", Map.of("subject", "custom.key"));
        assertEquals("custom.key", s);
    }

    @Test
    void testCommandFactMapsToTestCommandSubject() {
        assertEquals("project.default_test_command",
                MemorySubjectExtractor.extract("项目默认测试命令是 mvn test -Pquick", Map.of()));
    }

    @Test
    void responseStylePreferenceMapsToSubject() {
        assertEquals("preference.response_style",
                MemorySubjectExtractor.extract("以后默认用中文、短句回答", Map.of()));
    }

    @Test
    void occupationProfileMapsToSubject() {
        assertEquals("profile.occupation",
                MemorySubjectExtractor.extract("我是一名后端工程师", Map.of()));
    }

    @Test
    void unrecognizedFactReturnsEmptySubject() {
        assertTrue(MemorySubjectExtractor.extract("今天天气不错", Map.of()).isBlank(),
                "无法确定主题时返回空串，调用方退回追加不覆盖");
    }
}
