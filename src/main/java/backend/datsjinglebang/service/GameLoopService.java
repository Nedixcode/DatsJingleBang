package backend.datsjinglebang.service;

import backend.datsjinglebang.client.GameApiClient;
import backend.datsjinglebang.model.*;
import backend.datsjinglebang.strategy.StrategyService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class GameLoopService {
    private static final Logger log = LoggerFactory.getLogger(GameLoopService.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final GameApiClient api;
    private final StrategyService strategyService;

    @Value("${game.max-path-length:30}")
    private int maxPathLength;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicInteger tickCounter = new AtomicInteger(0);
    private final AtomicInteger totalRequests = new AtomicInteger(0);

    public GameLoopService(GameApiClient api, StrategyService strategyService) {
        this.api = api;
        this.strategyService = strategyService;
    }

    @PostConstruct
    public void start() {
        if (started.getAndSet(true)) {
            log.error("GameLoopService already started! Skipping...");
            return;
        }

        log.info("Starting game loop with FIXED 500ms delays between requests");

        // Запускаем бесконечный цикл правильно
        startInfiniteLoop();
    }

    private void startInfiniteLoop() {
        processTickWithRetry()
                .repeat()
                .subscribe(
                        null,
                        error -> {
                            log.error("Game loop stopped with error: {}", error.getMessage());
                            // Перезапускаем через 5 секунд при фатальной ошибке
                            Mono.delay(Duration.ofSeconds(5))
                                    .doOnSuccess(v -> startInfiniteLoop())
                                    .subscribe();
                        },
                        () -> log.info("Game loop completed (unexpected)")
                );
    }

    private Mono<Void> processTickWithRetry() {
        int tickNumber = tickCounter.incrementAndGet();
        String tickId = String.format("%s-T%03d",
                LocalDateTime.now().format(TIME_FORMATTER), tickNumber);

        log.info("[{}] ┌─── Starting tick #{} ───", tickId, tickNumber);

        return processTickSequence(tickId)
                .retry(3) // 3 попытки при ошибке
                .onErrorResume(e -> {
                    log.error("[{}] │ ✗ Tick #{} failed after retries: {}",
                            tickId, tickNumber, e.getMessage());
                    // Ждем 1 секунду и продолжаем
                    return Mono.delay(Duration.ofSeconds(1)).then();
                })
                .doFinally(signal -> {
                    log.info("[{}] └─── Tick #{} completed (total requests: {}) ───",
                            tickId, tickNumber, totalRequests.get());
                });
    }

    private Mono<Void> processTickSequence(String tickId) {
        // Шаг 1: Получаем арену
        return api.getArena()
                .doOnSubscribe(s -> log.info("[{}] │ Sending GET /arena (request #{})",
                        tickId, totalRequests.incrementAndGet()))
                .doOnSuccess(arena -> log.info("[{}] │ ✓ GET /arena: code={}, player={}, round={}",
                        tickId, arena.code, arena.player, arena.round))
                .doOnError(e -> log.error("[{}] │ ✗ GET /arena failed: {}", tickId, e.getMessage()))
                .delayElement(Duration.ofMillis(500))
                .flatMap(arena -> {
                    if (arena.code != 0) {
                        log.info("[{}] │ Game not active (code={}), skipping booster/move",
                                tickId, arena.code);
                        return Mono.empty();
                    }

                    // Шаг 2: Получаем бустеры
                    return api.getBoosters()
                            .doOnSubscribe(s -> log.info("[{}] │ Sending GET /booster (request #{})",
                                    tickId, totalRequests.incrementAndGet()))
                            .doOnSuccess(boosters -> {
                                int availableCount = boosters.getAvailable() != null ?
                                        boosters.getAvailable().size() : 0;
                                log.info("[{}] │ ✓ GET /booster: available={}, points={}",
                                        tickId, availableCount, boosters.getState().getPoints());
                            })
                            .doOnError(e -> log.error("[{}] │ ✗ GET /booster failed: {}", tickId, e.getMessage()))
                            .delayElement(Duration.ofMillis(500))
                            .flatMap(boosters -> {
                                // Шаг 3: Генерируем команды
                                MoveRequest moveRequest = strategyService.decideMove(arena, boosters);

                                if (moveRequest != null && !moveRequest.getBombers().isEmpty()) {
                                    validateAndFixCommands(moveRequest);

                                    log.info("[{}] │ Generated commands for {} bombers",
                                            tickId, moveRequest.getBombers().size());

                                    // Шаг 4: Отправляем команды
                                    return api.move(moveRequest)
                                            .doOnSubscribe(s -> log.info("[{}] │ Sending POST /move (request #{}) for {} bombers",
                                                    tickId, totalRequests.incrementAndGet(), moveRequest.getBombers().size()))
                                            .doOnSuccess(v -> log.info("[{}] │ ✓ POST /move successful", tickId))
                                            .doOnError(e -> log.error("[{}] │ ✗ POST /move failed: {}", tickId, e.getMessage()))
                                            .delayElement(Duration.ofMillis(500));
                                } else {
                                    log.info("[{}] │ No commands to send", tickId);
                                    // Все равно ждем 500ms для сохранения ритма
                                    return Mono.delay(Duration.ofMillis(350)).then();
                                }
                            });
                })
                .then();
    }

    private void validateAndFixCommands(MoveRequest request) {
        if (request == null || request.getBombers() == null) return;

        for (var bomber : request.getBombers()) {
            if (bomber == null) continue;

            if (bomber.getPath() != null && bomber.getPath().size() > maxPathLength) {
                bomber.setPath(bomber.getPath().subList(0, maxPathLength));
                log.warn("Truncated path for bomber {} to {} coordinates",
                        bomber.getId(), maxPathLength);
            }
        }
    }
}