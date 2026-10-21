package com.zlf.datapilot.exception;

/**
 * 目标不存在（Ticket 14 之后顺手修的 QA-13 P3-1）。
 *
 * <p>为什么单独立一个类：此前「要删的东西不存在」和「参数不合法」都抛
 * {@link IllegalArgumentException}，于是两者都是 400。对调用方来说这两种情况的处置完全不同 ——
 * 400 是「你传错了，改参数」，404 是「东西没了，刷新看看」。混在一个状态码里，前端只能靠读中文文案猜。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
