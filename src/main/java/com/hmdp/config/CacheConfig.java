package com.hmdp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class CacheConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService cacheRebuildExecutor() {
        return Executors.newFixedThreadPool(10);
    }
}
