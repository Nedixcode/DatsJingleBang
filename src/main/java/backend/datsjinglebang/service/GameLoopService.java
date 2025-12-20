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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
    private final AtomicLong lastBoosterPurchaseTime = new AtomicLong(0);
    private final AtomicInteger totalBoostersPurchased = new AtomicInteger(0);
    private static final long BOOSTER_PURCHASE_INTERVAL_MS = 20000; // 20 секунд

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
        log.info("Booster purchase interval: {} ms", BOOSTER_PURCHASE_INTERVAL_MS);

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
                    log.info("[{}] └─── Tick #{} completed (total requests: {}, total boosters purchased: {}) ───",
                            tickId, tickNumber, totalRequests.get(), totalBoostersPurchased.get());
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
                .delayElement(Duration.ofMillis(300))
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
                                BoosterState state = boosters.getState();
                                log.info("[{}] │ ✓ GET /booster: available={}, points={}",
                                        tickId, availableCount, state.getPoints());

                                // Подробный лог текущих характеристик
                                logBoosterState(state, tickId);

                                // Проверяем, нужно ли покупать бустеры
                                checkAndPurchaseBooster(boosters, tickId);
                            })
                            .doOnError(e -> log.error("[{}] │ ✗ GET /booster failed: {}", tickId, e.getMessage()))
                            .delayElement(Duration.ofMillis(300))
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
                                            .delayElement(Duration.ofMillis(300));
                                } else {
                                    log.info("[{}] │ No commands to send", tickId);
                                    // Все равно ждем 500ms для сохранения ритма
                                    return Mono.delay(Duration.ofMillis(300)).then();
                                }
                            });
                })
                .then();
    }

    // НОВЫЙ МЕТОД: Логирует текущее состояние бустеров
    private void logBoosterState(BoosterState state, String tickId) {
        log.info("[{}] │ 📊 Current stats: ⚡Speed={}, 💣Bombs={}, 🎯Range={}, 👁️View={}, 🛡️Armor={}, ⏱️Delay={}",
                tickId,
                state.getSpeed(),
                state.getBombs(),
                state.getBombRange(),
                state.getView(),
                state.getArmor(),
                state.getBombDelay());

        if (state.isCanPassBombs() || state.isCanPassObstacles() || state.isCanPassWalls()) {
            log.info("[{}] │ 🚀 Special abilities: PassBombs={}, PassObstacles={}, PassWalls={}",
                    tickId,
                    state.isCanPassBombs() ? "✓" : "✗",
                    state.isCanPassObstacles() ? "✓" : "✗",
                    state.isCanPassWalls() ? "✓" : "✗");
        }
    }

    // НОВЫЙ МЕТОД: Проверяет и покупает бустеры
    private void checkAndPurchaseBooster(BoosterResponse boosters, String tickId) {
        long currentTime = System.currentTimeMillis();
        long lastPurchaseTime = lastBoosterPurchaseTime.get();

        // Проверяем, прошло ли 20 секунд с последней покупки
        if (currentTime - lastPurchaseTime < BOOSTER_PURCHASE_INTERVAL_MS) {
            long secondsSinceLastPurchase = (currentTime - lastPurchaseTime) / 1000;
            log.info("[{}] │ ⏳ Last booster purchase was {} seconds ago (need {} seconds)",
                    tickId, secondsSinceLastPurchase, BOOSTER_PURCHASE_INTERVAL_MS / 1000);
            return;
        }

        if (boosters.getAvailable() == null || boosters.getAvailable().isEmpty()) {
            log.info("[{}] │ 🚫 No boosters available for purchase", tickId);
            lastBoosterPurchaseTime.set(currentTime); // Обновляем время, чтобы не проверять каждый раз
            return;
        }

        int points = boosters.getState().getPoints();
        List<Booster> availableBoosters = boosters.getAvailable();

        // Логируем доступные бустеры
        logAvailableBoosters(availableBoosters, points, tickId);

        // Выбираем лучший бустер для покупки
        Booster bestBooster = null;
        int bestPriority = -1;

        for (Booster booster : availableBoosters) {
            if (booster.getCost() <= points) {
                int priority = getBoosterPriority(booster.getType());
                if (priority > bestPriority) {
                    bestPriority = priority;
                    bestBooster = booster;
                }
            }
        }

        if (bestBooster != null) {
            log.info("[{}] │ 🛒 Attempting to purchase booster: {} (cost: {}, priority: {})",
                    tickId, bestBooster.getType(), bestBooster.getCost(), bestPriority);

            // Покупаем бустер
            PurchaseBoosterRequest purchaseRequest = new PurchaseBoosterRequest(bestBooster.getType());
            Booster finalBestBooster = bestBooster;
            api.purchaseBooster(purchaseRequest)
                    .doOnSubscribe(s -> log.info("[{}] │ Sending POST /booster (request #{})",
                            tickId, totalRequests.incrementAndGet()))
                    .doOnSuccess(response -> {
                        log.info("[{}] │ ✅ POST /booster SUCCESSFUL: purchased {} for {} points",
                                tickId, finalBestBooster.getType(), finalBestBooster.getCost());
                        // Обновляем время последней покупки
                        lastBoosterPurchaseTime.set(currentTime);
                        // Увеличиваем счетчик купленных бустеров
                        totalBoostersPurchased.incrementAndGet();

                        // Логируем общую статистику
                        log.info("[{}] │ 🎉 TOTAL BOOSTERS PURCHASED: {}", tickId, totalBoostersPurchased.get());
                    })
                    .doOnError(e -> {
                        log.error("[{}] │ ❌ POST /booster FAILED: {}", tickId, e.getMessage());
                        // Все равно обновляем время при ошибке, чтобы не зациклиться
                        lastBoosterPurchaseTime.set(currentTime);
                    })
                    .subscribe();
        } else {
            int cheapestCost = getCheapestBoosterCost(availableBoosters);
            log.info("[{}] │ 💰 No affordable boosters (points: {}, cheapest booster cost: {})",
                    tickId, points, cheapestCost);

            // Логируем сколько не хватает
            if (points < cheapestCost) {
                int needed = cheapestCost - points;
                log.info("[{}] │ 📈 Need {} more points to buy cheapest booster", tickId, needed);
            }

            // Обновляем время, чтобы не проверять каждый тик
            lastBoosterPurchaseTime.set(currentTime);
        }
    }

    // НОВЫЙ МЕТОД: Логирует доступные бустеры
    private void logAvailableBoosters(List<Booster> boosters, int points, String tickId) {
        if (boosters == null || boosters.isEmpty()) {
            log.info("[{}] │ 📋 Available boosters: NONE", tickId);
            return;
        }

        log.info("[{}] │ 📋 Available boosters ({} points available):", tickId, points);
        for (Booster booster : boosters) {
            boolean canAfford = booster.getCost() <= points;
            int priority = getBoosterPriority(booster.getType());
            log.info("[{}] │   - {}: {} points (affordable: {}, priority: {})",
                    tickId, booster.getType(), booster.getCost(),
                    canAfford ? "✓" : "✗", priority);
        }
    }

    // Вспомогательный метод: получает стоимость самого дешевого бустера
    private int getCheapestBoosterCost(List<Booster> boosters) {
        if (boosters == null || boosters.isEmpty()) {
            return Integer.MAX_VALUE;
        }

        int minCost = Integer.MAX_VALUE;
        for (Booster booster : boosters) {
            if (booster.getCost() < minCost) {
                minCost = booster.getCost();
            }
        }
        return minCost;
    }

    // НОВЫЙ МЕТОД: Определяет приоритет бустеров
    private int getBoosterPriority(String boosterType) {
        if (boosterType == null) return 1;

        switch (boosterType.toLowerCase()) {
            case "armor":
                return 10; // Самый высокий приоритет - больше бомб
            case "speed":
                return 9;  // Скорость
            case "bombs":
                return 8;  // Радиус взрыва
            case "view":
                return 7;  // Обзор
            case "bomb_range":
                return 6;  // Броня
            case "bomb_delay":
                return 5;  // Задержка бомбы
            case "can_pass_bombs":
                return 4;  // Проход через бомбы
            case "can_pass_obstacles":
                return 3;  // Проход через препятствия
            case "can_pass_walls":
                return 2;  // Проход через стены
            default:
                return 1;
        }
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

    // Метод для получения статистики (можно использовать для мониторинга)
    public void printBoosterStatistics() {
        long currentTime = System.currentTimeMillis();
        long lastPurchaseTime = lastBoosterPurchaseTime.get();
        long secondsSinceLastPurchase = (currentTime - lastPurchaseTime) / 1000;

        log.info("=== BOOSTER STATISTICS ===");
        log.info("Total boosters purchased: {}", totalBoostersPurchased.get());
        log.info("Seconds since last purchase: {}", secondsSinceLastPurchase);
        log.info("Next purchase in: {} seconds",
                Math.max(0, BOOSTER_PURCHASE_INTERVAL_MS/1000 - secondsSinceLastPurchase));
        log.info("Total API requests made: {}", totalRequests.get());
        log.info("=========================");
    }
}