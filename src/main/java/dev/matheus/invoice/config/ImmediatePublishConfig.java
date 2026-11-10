package dev.matheus.invoice.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Executor da publicação imediata depois do commit. É limitado de propósito: se a fila encher (broker lento),
 * a tarefa é rejeitada e o relay do worker publica a solicitação, sem segurar o request.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!webhook")
class ImmediatePublishConfig {

    @Bean
    ExecutorService immediatePublishExecutor() {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory threads = task -> {
            Thread thread = new Thread(task, "immediate-publish-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100), threads,
                new ThreadPoolExecutor.AbortPolicy());
    }
}
