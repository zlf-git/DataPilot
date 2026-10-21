package com.zlf.datapilot.agent.step;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.agent.AgentStep;
import com.zlf.datapilot.agent.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ① 问题改写节点（Ticket 10 起承担多轮补全）。
 *
 * <p>补全的边界是刻意划窄的：<b>只续「能确定语义」的追问</b>。
 * <ul>
 *   <li><b>环比</b>（「那环比呢」）：沿用上一轮的指标/维度/窗口天数，把问题补成
 *       「&lt;上一轮问题&gt;，环比：与前 N 天对比」，并交给引擎跑第二个窗口。</li>
 *   <li><b>纯指代</b>（「再算一遍」「那呢」）：去掉指代词后没有新增信息，等价于上一轮问题，
 *       直接沿用（语义确定，不会答错）。</li>
 *   <li><b>带新增修饰的追问</b>（「那渠道呢」「换成上个月呢」）：<b>显式拒绝</b>并提示完整提问。
 *       这类追问要么换维度、要么换时间，靠字符串拼接补出来的是「上一轮问题 + 一句新话」，
 *       LLM/桩很可能只答上一轮 —— 那就是静默错数（比拒绝严重得多）。</li>
 * </ul>
 *
 * <p>没有 sessionId、会话为空或已过 TTL 时一律透传，行为与 Ticket 01 完全一致。
 */
@Component
public class RewriteStep implements AgentStep {

    private static final Logger log = LoggerFactory.getLogger(RewriteStep.class);

    /** 环比：沿用上一轮窗口，再往前挪一个等长窗口 */
    private static final String MOM = "环比";
    private static final String YOY = "同比";

    /** 指代词（去掉这些后若没有剩余语义，说明只是「再说一遍」） */
    private static final List<String> ANAPHORA = List.of(
            "那", "呢", "这个", "那个", "它", "上面", "刚才", "前面", "再算一遍", "再来一遍", "重新算一遍", "以上");

    /** 上一轮没窗口时的兜底天数（与评测集「近 7 天」口径一致） */
    private static final int DEFAULT_WINDOW_DAYS = 7;

    /** 追问的长度上限：超过这个长度就当作完整问题，不做补全 */
    private static final int FOLLOW_UP_MAX_CHARS = 12;

    private final SessionStore sessions;

    public RewriteStep(SessionStore sessions) {
        this.sessions = sessions;
    }

    @Override
    public String name() {
        return "rewrite";
    }

    @Override
    public AgentContext execute(AgentContext ctx) {
        String question = ctx.getUserQuestion() == null ? "" : ctx.getUserQuestion().trim();
        SessionStore.Round last = sessions.last(ctx.getSessionId());
        if (last == null) {
            ctx.setRewrittenQuestion(question);
            return ctx;
        }

        boolean mom = question.contains(MOM);
        boolean yoy = question.contains(YOY);

        // 同比先说死：库内只有 7 天数据，同比需要去年同期，硬算只能编
        if (yoy) {
            ctx.setErrorMessage("暂不支持同比：库内数据只有最近 " + windowDaysOf(last)
                    + " 天，没有去年同期数据，无法计算同比");
            return ctx;
        }

        if (mom) {
            int days = windowDaysOf(last);
            ctx.setComparisonDays(days);
            ctx.setRewrittenQuestion(last.rewrittenQuestion() + "，环比：与前 " + days + " 天对比");
            log.debug("多轮补全（环比）：session={} 上一轮「{}」→「{}」",
                    ctx.getSessionId(), last.rewrittenQuestion(), ctx.getRewrittenQuestion());
            return ctx;
        }

        // 去掉指代词与标点后还剩不剩东西：剩空 = 纯指代，可以安全沿用上一轮
        if (!looksLikeFollowUp(question)) {
            // 不是追问（哪怕是「那么近7天GMV是多少」这种带口语前缀的完整问句）：透传
            ctx.setRewrittenQuestion(question);
            return ctx;
        }
        String residue = stripAnaphora(question);
        if (residue.isEmpty()) {
            ctx.setRewrittenQuestion(last.rewrittenQuestion());
            log.debug("多轮补全（纯指代）：session={} 「{}」→「{}」",
                    ctx.getSessionId(), question, ctx.getRewrittenQuestion());
            return ctx;
        }

        // 带新增修饰的追问：补不出来，拒绝比猜好
        ctx.setErrorMessage("多轮追问暂只支持环比（如「那环比呢」）。"
                + "「" + question + "」这类换了维度/时间的追问请把条件说全，例如「10月19日各渠道的GMV是多少」");
        return ctx;
    }

    /** 上一轮的窗口天数（没窗口时按 7 天，与评测集口径一致） */
    private int windowDaysOf(SessionStore.Round last) {
        Integer days = last.windowDays();
        return days == null || days < 1 ? DEFAULT_WINDOW_DAYS : days;
    }

    /**
     * 是不是「追问」：含指代词<b>且</b>足够短。
     *
     * <p>长度门槛是必须的：中文口语前缀（「那么近7天GMV是多少」「那个10月19日的GMV是多少」）
     * 含指代词但本身是完整问题，按追问处理会被误判成「补不出来」而拒绝。
     * 反过来，真正的追问都很短（「那渠道呢」「环比呢」「再算一遍」），12 字够用。
     */
    private boolean looksLikeFollowUp(String question) {
        if (question.length() > FOLLOW_UP_MAX_CHARS) {
            return false;
        }
        return ANAPHORA.stream().anyMatch(question::contains);
    }

    /** 去掉指代词、标点、空白，返回「真正的增量语义」 */
    private String stripAnaphora(String question) {
        String s = question;
        // 长的先剔，避免「再算一遍」被「再」截断后剩「算一遍」
        List<String> sorted = ANAPHORA.stream().sorted((a, b) -> b.length() - a.length()).toList();
        for (String word : sorted) {
            s = s.replace(word, "");
        }
        // 中文/英文标点与空白一律不算语义
        return s.replaceAll("[\\p{Punct}\\s，。？！、；：（）【】「」…—]", "").trim();
    }
}
