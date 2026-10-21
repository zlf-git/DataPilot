package com.zlf.datapilot.agent.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多轮会话的轮次存储（Ticket 10）。
 *
 * <p>存的是<b>改写结果摘要</b>而不是整份 IR：RewriteStep 补全指代只需要知道
 * 「上一轮问的是什么、看了哪几个指标/维度、时间窗口多大」，把 IR 全文留着反而会诱使
 * 后续代码直接复用上一轮的 SQL —— 那等于绕过本轮的白名单校验与闸门。
 *
 * <p>为什么放在进程内存而不是元数据库：单机单用户作品，多轮上下文丢了只是「再问一遍」，
 * 而每轮写一次库会给主链路加一次 fsync（Ticket 09 刚因为埋点写库踩过 231ms → 56ms 的坑）。
 * 重启后会话清空是<b>可接受且有意的</b>降级，代价写进票面「已知边界」。
 *
 * <p>三条上限，都是防「无人清理的内存增长」：单会话保留 rounds 轮、会话 TTL、
 * 会话总数上限（超了按最久未用淘汰，{@link LinkedHashMap} 的 accessOrder）。
 */
@Component
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);

    /** 一轮问答的摘要：够补全指代，不够重放查询 */
    public record Round(String question,
                        String rewrittenQuestion,
                        List<String> metricIds,
                        List<String> dimensions,
                        Integer windowDays,
                        String sql,
                        long atMillis) {
    }

    private final int maxRounds;
    private final int maxSessions;
    private final long ttlMillis;

    /** accessOrder=true：读到即挪到队尾，淘汰时先淘汰最久未用的会话 */
    private final Map<String, Deque<Round>> sessions = new LinkedHashMap<>(16, 0.75f, true);

    public SessionStore(@Value("${datapilot.session.max-rounds:5}") int maxRounds,
                        @Value("${datapilot.session.max-sessions:500}") int maxSessions,
                        @Value("${datapilot.session.ttl-minutes:30}") long ttlMinutes) {
        this.maxRounds = Math.max(1, maxRounds);
        this.maxSessions = Math.max(1, maxSessions);
        this.ttlMillis = Math.max(1, ttlMinutes) * 60_000L;
        log.info("会话存储就绪：maxRounds={} maxSessions={} ttl={}min（进程内存，重启即清空）",
                this.maxRounds, this.maxSessions, ttlMinutes);
    }

    /**
     * 最近一轮（无会话 / 已过期 / 空会话均返回 null）。
     *
     * <p>过期判定按<b>该会话最后一轮的写入时间</b>：用户连续问了三轮、中间隔 40 分钟，
     * 从第二轮起就不该再续上第一轮的指代 —— 那已经不是同一段对话了。
     */
    public Round last(String sessionId) {
        Deque<Round> rounds = alive(sessionId);
        return rounds == null || rounds.isEmpty() ? null : rounds.peekLast();
    }

    /** 最近 n 轮，按时间正序（老 → 新）；用于「补全指代」和排查时回放上下文 */
    public List<Round> tail(String sessionId, int n) {
        Deque<Round> rounds = alive(sessionId);
        if (rounds == null || rounds.isEmpty() || n <= 0) {
            return List.of();
        }
        List<Round> all = new ArrayList<>(rounds);
        return List.copyOf(all.subList(Math.max(0, all.size() - n), all.size()));
    }

    /** 追加一轮；超过 maxRounds 丢最老的 */
    public void append(String sessionId, Round round) {
        if (sessionId == null || sessionId.isBlank() || round == null) {
            return;
        }
        synchronized (sessions) {
            if (!sessions.containsKey(sessionId) && sessions.size() >= maxSessions) {
                // LinkedHashMap(accessOrder=true) 的迭代顺序就是「最久未用在前」
                String eldest = sessions.keySet().iterator().next();
                sessions.remove(eldest);
                log.debug("会话数达上限 {}，淘汰最久未用会话 {}", maxSessions, eldest);
            }
            Deque<Round> rounds = sessions.computeIfAbsent(sessionId, k -> new ArrayDeque<>());
            rounds.addLast(round);
            while (rounds.size() > maxRounds) {
                rounds.removeFirst();
            }
        }
    }

    /** 当前存活会话数（健康检查/测试用） */
    public int sessionCount() {
        synchronized (sessions) {
            purgeExpired();
            return sessions.size();
        }
    }

    public int maxRounds() {
        return maxRounds;
    }

    public int maxSessions() {
        return maxSessions;
    }

    public long ttlMinutes() {
        return ttlMillis / 60_000L;
    }

    /** 仅供测试：清空所有会话 */
    public void clear() {
        synchronized (sessions) {
            sessions.clear();
        }
    }

    private Deque<Round> alive(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        synchronized (sessions) {
            Deque<Round> rounds = sessions.get(sessionId);
            if (rounds == null || rounds.isEmpty()) {
                return null;
            }
            Round lastRound = rounds.peekLast();
            if (lastRound != null && System.currentTimeMillis() - lastRound.atMillis() > ttlMillis) {
                sessions.remove(sessionId);
                log.debug("会话 {} 超过 TTL {} 分钟，已失效", sessionId, ttlMinutes());
                return null;
            }
            return rounds;
        }
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> {
            Round lastRound = e.getValue().peekLast();
            return lastRound == null || now - lastRound.atMillis() > ttlMillis;
        });
    }

    /** 空的不可变列表，免得到处判 null（保留给调用方拼装轮次用） */
    public static List<String> emptyIfNull(List<String> v) {
        return v == null ? Collections.emptyList() : v;
    }
}
