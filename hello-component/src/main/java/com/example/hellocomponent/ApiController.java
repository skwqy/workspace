package com.example.hellocomponent;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final MessageRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final StringRedisTemplate redis;

    public ApiController(MessageRepository repository,
                         KafkaTemplate<String, String> kafka,
                         StringRedisTemplate redis) {
        this.repository = repository;
        this.kafka = kafka;
        this.redis = redis;
    }

    /** MySQL：保存一条消息，并顺手发到 Kafka（MySQL→Kafka） */
    @PostMapping("/messages")
    public Message save(@RequestParam String content) {
        Message saved = repository.save(new Message(content));
        kafka.send("hello-topic", saved.getId() + ":" + content);
        return saved;
    }

    /** MySQL：查全部（新→旧） */
    @GetMapping("/messages")
    public List<Message> list() {
        return repository.findAllByOrderByIdDesc();
    }

    /** Redis：写（10 分钟过期） */
    @PostMapping("/redis/{key}")
    public String redisSet(@PathVariable String key, @RequestParam String value) {
        redis.opsForValue().set(key, value, Duration.ofMinutes(10));
        return "OK: " + key + " = " + value + " (TTL 10min)";
    }

    /** Redis：读 */
    @GetMapping("/redis/{key}")
    public String redisGet(@PathVariable String key) {
        String v = redis.opsForValue().get(key);
        return v == null ? "(null：不存在或已过期)" : v;
    }

    /** Kafka 链路回读：最近一条被消费者写进 Redis 的消息（证明 Kafka 收发 + 消费落 Redis） */
    @GetMapping("/kafka/last")
    public String kafkaLast() {
        String v = redis.opsForValue().get(KafkaConsumerService.LAST_KEY);
        return v == null ? "(还没消费到任何消息)" : v;
    }
}
