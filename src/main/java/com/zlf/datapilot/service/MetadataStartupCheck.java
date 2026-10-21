package com.zlf.datapilot.service;

import com.zlf.datapilot.knowledge.MetadataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动自检（Ticket 01b）：metric_def 为空则 ERROR 告警。
 * 指标白名单是存在性闸门的数据来源，空 = 指标题全量被拦，启动即暴露而非运行到一半才炸。
 */
@Component
public class MetadataStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MetadataStartupCheck.class);

    private final MetadataRepository repo;

    public MetadataStartupCheck(MetadataRepository repo) {
        this.repo = repo;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int count = repo.countMetrics();
            if (count == 0) {
                log.error("metric_def 为空！存在性闸门的指标白名单将是空的，指标题会被全量拦截。请先执行 deploy/seed.sql。");
            } else {
                log.info("元数据就绪：metric_def={} 条", count);
            }
        } catch (Exception e) {
            // 健康检查只告警不阻断：数据库暂时连不上时应用仍可启动，等 DB 恢复后功能可用
            log.error("启动自检失败：无法连接元数据库，metric_def 白名单状态未知。请检查 MySQL 3307 是否运行。", e);
        }
    }
}
