package com.zlf.datapilot.agent.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionStore 单测（Ticket 10）：只测外部行为 —— 存/取/上限/TTL/隔离。
 *
 * <p>三条上限都各有一条用例，因为它们防的是「内存随会话数无界增长」，
 * 而这种问题在单机作品里最容易到演示当天才暴露（用户不会一直重启应用）。
 */
class SessionStoreTest {

    private static SessionStore store(int maxRounds, int maxSessions, long ttlMinutes) {
        return new SessionStore(maxRounds, maxSessions, ttlMinutes);
    }

    private static SessionStore.Round round(String q) {
        return new SessionStore.Round(q, q, List.of("gmv_total"), List.of(), 7, "SELECT 1", System.currentTimeMillis());
    }

    @Test
    @DisplayName("无 sessionId / 未知会话 → last() 为 null（等价于「没有上下文」）")
    void unknownSessionHasNoContext() {
        SessionStore s = store(5, 500, 30);
        assertThat(s.last(null)).isNull();
        assertThat(s.last(" ")).isNull();
        assertThat(s.last("never-seen")).isNull();
        assertThat(s.tail("never-seen", 2)).isEmpty();
    }

    @Test
    @DisplayName("append 后 last() 拿到最近一轮，tail(n) 按老→新返回")
    void appendAndReadBack() {
        SessionStore s = store(5, 500, 30);
        s.append("s1", round("第一问"));
        s.append("s1", round("第二问"));

        assertThat(s.last("s1").question()).isEqualTo("第二问");
        assertThat(s.tail("s1", 5)).extracting(SessionStore.Round::question)
                .containsExactly("第一问", "第二问");
        assertThat(s.tail("s1", 1)).extracting(SessionStore.Round::question)
                .containsExactly("第二问");
    }

    @Test
    @DisplayName("maxRounds=2：连问三轮只留最近两轮（最老的被丢）")
    void maxRoundsDropsOldest() {
        SessionStore s = store(2, 500, 30);
        s.append("s1", round("Q1"));
        s.append("s1", round("Q2"));
        s.append("s1", round("Q3"));

        assertThat(s.tail("s1", 10)).extracting(SessionStore.Round::question)
                .containsExactly("Q2", "Q3");
    }

    @Test
    @DisplayName("TTL 过期：最后一轮写入时间超过 ttl-minutes → 上下文失效")
    void expiredSessionIsDropped() {
        SessionStore s = store(5, 500, 1);
        long old = System.currentTimeMillis() - 61_000L;
        s.append("s1", new SessionStore.Round("旧问题", "旧问题", List.of(), List.of(), 7, "SELECT 1", old));

        assertThat(s.last("s1")).isNull();
        assertThat(s.sessionCount()).isZero();
    }

    @Test
    @DisplayName("maxSessions=2：新增第三个会话时淘汰最久未用的那个")
    void maxSessionsEvictsLeastRecentlyUsed() {
        SessionStore s = store(5, 2, 30);
        s.append("a", round("a1"));
        s.append("b", round("b1"));
        // 读一下 a，让 b 变成「最久未用」
        assertThat(s.last("a")).isNotNull();

        s.append("c", round("c1"));

        assertThat(s.sessionCount()).isEqualTo(2);
        assertThat(s.last("a")).isNotNull();
        assertThat(s.last("b")).isNull();
        assertThat(s.last("c")).isNotNull();
    }

    @Test
    @DisplayName("会话之间不串：s1 的上下文不会成为 s2 的上一轮")
    void sessionsAreIsolated() {
        SessionStore s = store(5, 500, 30);
        s.append("s1", round("s1 的问题"));

        assertThat(s.last("s2")).isNull();
    }

    @Test
    @DisplayName("空白 sessionId / null round 直接丢弃，不产生垃圾会话")
    void blankSessionIsIgnored() {
        SessionStore s = store(5, 500, 30);
        s.append("", round("x"));
        s.append(null, round("x"));
        s.append("s1", null);

        assertThat(s.sessionCount()).isZero();
    }
}
