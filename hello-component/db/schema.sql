-- ============================================================================
-- hello-component 数据库对象清单：study.hello_message
-- 生成方式：应用首次启动时由 JPA（ddl-auto: update）按 Message 实体自动创建
-- 本文件用途：规范化 DDL 存档（在 JPA 生成版基础上补列 COMMENT 与 IF NOT EXISTS），
--             可用于手工重建；正式项目应改由 Flyway/Liquibase 管理（见 README §三）
-- 手工执行：mysql -h<host> -uroot -p study < db/schema.sql
-- ============================================================================

CREATE TABLE IF NOT EXISTS `hello_message` (
  `id`         bigint       NOT NULL AUTO_INCREMENT COMMENT '主键（Message.id，IDENTITY 自增）',
  `content`    varchar(255) DEFAULT NULL              COMMENT '消息内容（Message.content，String 默认 255）',
  `created_at` datetime(6)  DEFAULT NULL              COMMENT '创建时间（Message.createdAt，LocalDateTime 微秒精度）',
  PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'Kafka 演示消息表（POST /api/messages 写入）';

-- Kafka topic（由应用启动时 KafkaAdmin 创建，此处仅作记录）：
--   hello-topic   1 partition / 1 replica，生产者与 @KafkaListener 使用
--   __consumer_offsets（Kafka 内部 topic，自动生成，勿动）
