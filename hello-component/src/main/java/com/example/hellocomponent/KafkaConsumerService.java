package com.example.hellocomponent;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class KafkaConsumerService {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerService.class);

    /** 消费者把最近一条消息写进 Redis 这个 key，供 /api/kafka/last 回读验证 */
    public static final String LAST_KEY = "kafka:last";

    private final StringRedisTemplate redis;

    public KafkaConsumerService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 消费 hello-topic：记日志 + 写 Redis（证明 Kafka→Redis 链路通了） */
    @KafkaListener(topics = "hello-topic", groupId = "hello-component")
    public void onMessage(String message) {
        log.info("收到 Kafka 消息: {}", message);
        redis.opsForValue().set(LAST_KEY, message, Duration.ofDays(1));
    }
}
