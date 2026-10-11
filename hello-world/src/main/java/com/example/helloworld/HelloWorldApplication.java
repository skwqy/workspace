package com.example.helloworld;

import java.net.InetAddress;
import java.time.LocalDateTime;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class HelloWorldApplication {

    public static void main(String[] args) {
        SpringApplication.run(HelloWorldApplication.class, args);
    }

    @GetMapping("/")
    public String hello() throws Exception {
        // hostname 能看出代码跑在哪个容器里
        String host = InetAddress.getLocalHost().getHostName();
        return "Hello, World!  (Spring Boot 4.1.1 | host=" + host + " | " + LocalDateTime.now() + ")";
    }
}
