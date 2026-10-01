package com.gogo.travel.utils;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IdGenerator 单元测试：验证高频连续生成（覆盖同一毫秒场景）不产生重复 ID。
 *
 */
class IdGeneratorTest {

    @Test
    void next_uniqueUnderBurstGeneration() {
        int count = 20_000;
        Set<String> ids = new HashSet<>(count * 2);
        for (int i = 0; i < count; i++) {
            String id = IdGenerator.next("to_");
            assertTrue(id.startsWith("to_"));
            ids.add(id);
        }
        assertEquals(count, ids.size(), "同一毫秒内高频生成不应出现重复 ID");
    }
}
