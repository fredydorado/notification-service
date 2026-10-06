package com.fardorado.notification.configuration;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Configuration of the notification event processing pipeline: processing
 * properties, the clock abstraction and the bounded delivery worker pool.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(NotificationProcessingProperties.class)
public class NotificationProcessingConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The delivery worker pool. Virtual threads are cheap carriers for the
     * blocking webhook HTTP calls; the explicit concurrency limit bounds the
     * number of concurrent external deliveries so an unbounded number of HTTP
     * calls is impossible. When the limit is reached, submissions block until
     * a worker slot frees up (claimed events are never lost).
     */
    @Bean("deliveryWorkerExecutor")
    public AsyncTaskExecutor deliveryWorkerExecutor(NotificationProcessingProperties properties) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("delivery-worker-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(properties.workerPoolSize());
        return executor;
    }
}
