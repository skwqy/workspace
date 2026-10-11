package com.example.hellocomponent;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;

@SpringBootApplication
public class HelloComponentApplication {

    public static void main(String[] args) {
        SpringApplication.run(HelloComponentApplication.class, args);
    }

    /** 启动时自动创建 hello-topic（1 分区 1 副本，学习栈够用） */
    @Bean
    public NewTopic helloTopic() {
        return TopicBuilder.name("hello-topic").partitions(1).replicas(1).build();
    }
}
