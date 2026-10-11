-- 首次启动自动执行（仅 MySQL 数据目录为空时）
CREATE DATABASE IF NOT EXISTS study DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE study;

-- 演示表：验证连接与事务实验用
CREATE TABLE IF NOT EXISTS demo_user (
    id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    balance     DECIMAL(12,2) NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB;

INSERT INTO demo_user (name, balance) VALUES ('alice', 100.00), ('bob', 50.00);
