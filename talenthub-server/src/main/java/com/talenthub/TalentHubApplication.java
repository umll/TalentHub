package com.talenthub;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.talenthub.mapper")
public class TalentHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(TalentHubApplication.class, args);
    }
}
