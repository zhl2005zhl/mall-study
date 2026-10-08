package com.macro.mall.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启定时任务：
 * 商品索引的「拉取对账」（EsSyncReconcileTask）依赖它。
 * 只靠 MQ 推送的话，消息一丢索引就永久不一致了 —— 对账是最后一道防线。
 */
@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.macro.mall")
public class MallSearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(MallSearchApplication.class, args);
    }
}
