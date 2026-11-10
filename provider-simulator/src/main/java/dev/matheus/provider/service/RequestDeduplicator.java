package dev.matheus.provider.service;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Lembra os {@code messageId} de solicitação já aceitos: o envio do worker é <i>at-least-once</i> e a mesma
 * solicitação não pode gerar dois callbacks. Fica em memória (perde-se ao reiniciar e não expira), o que basta para
 * um simulador; ver as limitações na spec (seção 12).
 */
@Component
public class RequestDeduplicator {

    private final Set<UUID> seen = ConcurrentHashMap.newKeySet();

    /** {@code true} na primeira vez que o {@code messageId} aparece. */
    public boolean firstTime(UUID messageId) {
        return seen.add(messageId);
    }
}
