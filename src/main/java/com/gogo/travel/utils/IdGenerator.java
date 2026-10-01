package com.gogo.travel.utils;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务ID生成器：前缀 + 毫秒时间戳 + 6位十六进制序列后缀。
 *
 * <p>保留时间戳的可读性与大致有序性；后缀来自随机起点的进程内原子计数器，
 * 保证同一毫秒并发生成也互不碰撞——碰撞的ID会被 insert-or-update 型 save
 * 静默当作更新执行，造成已有数据被覆盖；随机起点同时降低多节点/重启后的碰撞概率。
 *
 */
public final class IdGenerator {

    /** 进程内自增序列（随机起点），取低 24 位作为 6 位十六进制后缀 */
    private static final AtomicLong SEQUENCE = new AtomicLong(ThreadLocalRandom.current().nextLong());

    private IdGenerator() {
    }

    /**
     * 生成带前缀的唯一ID，如 {@code to_1753948800000_a3f9c2}。
     *
     * @param prefix 业务前缀（如 to_ / pi_）
     */
    public static String next(String prefix) {
        return prefix + System.currentTimeMillis() + "_"
                + String.format("%06x", SEQUENCE.getAndIncrement() & 0xFFFFFF);
    }
}
