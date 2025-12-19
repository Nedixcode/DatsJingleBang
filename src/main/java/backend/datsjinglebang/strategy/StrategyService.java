package backend.datsjinglebang.strategy;

import backend.datsjinglebang.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class StrategyService {
    private static final Logger log = LoggerFactory.getLogger(StrategyService.class);

    private int tickCounter = 0;
    private final Random random = new Random();
    private final Map<String, Integer> bombCooldown = new HashMap<>();
    private final Map<String, String> lastAction = new HashMap<>();

    // Новые поля для управления расхождением
    private final Map<String, Integer> bomberGroup = new HashMap<>();
    private final Map<String, Integer> preferredDirection = new HashMap<>();
    private final Map<String, int[]> lastTarget = new HashMap<>();
    private int groupCounter = 0;

    // Новые поля для агрессивного минирования
    private final Map<String, Integer> aggressionLevel = new HashMap<>(); // 0-100, чем выше, тем агрессивнее
    private final Map<String, Integer> successfulBombs = new HashMap<>(); // Счетчик успешных бомб
    private final Map<String, Integer> lastBombTick = new HashMap<>(); // Тик последней установки бомбы

    public MoveRequest decideMove(ArenaResponse arena, BoosterResponse boosters) {
        tickCounter++;

        if (tickCounter % 2 != 0) {
            return null;
        }

        log.debug("=== Tick {} ===", tickCounter);

        List<MoveBomber> commands = new ArrayList<>();

        // Инициализация групп и направлений для новых бомберов
        initializeBombers(arena);

        // Очистка данных о мертвых бомберах
        cleanupDeadBombers(arena);

        for (Bomber bomber : arena.bombers) {
            if (!bomber.alive || !bomber.can_move) {
                continue;
            }

            updateCooldown(bomber.id);
            updateAggressionLevel(bomber.id, arena); // Обновляем уровень агрессии

            MoveBomber command = createSmartBombCommand(bomber, arena);
            if (command != null) {
                commands.add(command);
                logBomberAction(bomber, command);
            }
        }

        if (commands.isEmpty()) {
            return null;
        }

        return new MoveRequest(commands);
    }

    private void updateCooldown(String bomberId) {
        if (bombCooldown.containsKey(bomberId)) {
            int cooldown = bombCooldown.get(bomberId);
            if (cooldown > 0) {
                bombCooldown.put(bomberId, cooldown - 1);
                log.debug("Bomber {} cooldown: {}", bomberId, cooldown - 1);
            } else {
                bombCooldown.put(bomberId, 0);
            }
        } else {
            bombCooldown.put(bomberId, 0);
        }
    }

    private void updateAggressionLevel(String bomberId, ArenaResponse arena) {
        // Базовая агрессия 50, увеличивается при успешных бомбах
        if (!aggressionLevel.containsKey(bomberId)) {
            aggressionLevel.put(bomberId, 50);
            successfulBombs.put(bomberId, 0);
            lastBombTick.put(bomberId, 0);
        }

        // Увеличиваем агрессию если давно не ставили бомбы
        int ticksSinceLastBomb = tickCounter - lastBombTick.getOrDefault(bomberId, 0);
        if (ticksSinceLastBomb > 20) {
            aggressionLevel.put(bomberId, Math.min(100, aggressionLevel.get(bomberId) + 5));
        }
    }

    private void initializeBombers(ArenaResponse arena) {
        for (Bomber bomber : arena.bombers) {
            if (!bomberGroup.containsKey(bomber.id)) {
                bomberGroup.put(bomber.id, groupCounter % 3);
                groupCounter++;

                preferredDirection.put(bomber.id, random.nextInt(4));

                // Начальный уровень агрессии в зависимости от группы
                int baseAggression = 40 + (groupCounter % 3) * 20; // 40, 60, 80
                aggressionLevel.put(bomber.id, baseAggression);
                successfulBombs.put(bomber.id, 0);
                lastBombTick.put(bomber.id, 0);

                log.info("🎯 Bomber {} assigned to group {}, direction {}, aggression {}",
                        bomber.id, bomberGroup.get(bomber.id),
                        preferredDirection.get(bomber.id), aggressionLevel.get(bomber.id));
            }
        }
    }

    private MoveBomber createSmartBombCommand(Bomber bomber, ArenaResponse arena) {
        int[] currentPos = bomber.pos;

        // ПОВЫШЕННЫЙ ПРИОРИТЕТ: Проверяем возможность поставить бомбу у стены или рядом с врагом
        if (shouldPlantBombAggressively(bomber, arena)) {
            lastAction.put(bomber.id, "BOMB");
            lastBombTick.put(bomber.id, tickCounter);
            return plantBombAndEscape(bomber, arena);
        }

        // Проверяем, не слишком ли мы близко к другим бомберам
        if (isTooCloseToOtherBombers(bomber, arena)) {
            lastAction.put(bomber.id, "SPREAD");
            return spreadOut(bomber, arena);
        }

        // Проверяем, можем ли поставить бомбу СЕЙЧАС (оригинальная логика)
        if (canPlaceBombAtTarget(bomber, currentPos, arena)) {
            lastAction.put(bomber.id, "BOMB");
            lastBombTick.put(bomber.id, tickCounter);
            return plantBombAndEscape(bomber, arena);
        }

        // Если только что поставили бомбу - продолжаем убегать
        if ("BOMB".equals(lastAction.get(bomber.id))) {
            lastAction.put(bomber.id, "ESCAPE");
            return continueEscaping(bomber, arena);
        }

        // Ищем ЦЕЛЬ ДЛЯ МИНИРОВАНИЯ (а не просто цель для движения)
        int[] bombTarget = findBombPlacementTarget(bomber, arena);

        if (bombTarget != null) {
            lastAction.put(bomber.id, "MOVE_TO_BOMB");
            return moveToBombPlacement(bomber, bombTarget, arena);
        }

        // Ищем ближайшую цель ДЛЯ ЭТОГО БОМБЕРА
        int[] target = findIndividualTarget(bomber, arena);

        if (target != null) {
            lastAction.put(bomber.id, "MOVE");
            return moveToTarget(bomber, target, arena);
        }

        // Если нет целей - патрулируем в своей зоне, ища места для мин
        lastAction.put(bomber.id, "PATROL");
        return patrolAndSearchForBombSpots(bomber, arena);
    }

    // НОВЫЙ МЕТОД: Проверяет, нужно ли агрессивно ставить бомбу
    private boolean shouldPlantBombAggressively(Bomber bomber, ArenaResponse arena) {
        int[] currentPos = bomber.pos;

        // Базовые проверки
        if (bombCooldown.containsKey(bomber.id) && bombCooldown.get(bomber.id) > 0) {
            return false;
        }
        if (bomber.bombs_available <= 0) {
            return false;
        }
        if (isOnBomb(currentPos, arena)) {
            return false;
        }

        int aggression = aggressionLevel.getOrDefault(bomber.id, 50);

        // Проверяем особо выгодные позиции для бомб

        // 1. Прямо рядом с врагом (самый высокий приоритет)
        if (isDirectlyNextToEnemy(currentPos, arena)) {
            log.info("🎯 Bomber {}: Enemy right next to us!", bomber.id);
            return true;
        }

        // 2. В углу или у нескольких стен (много целей для взрыва)
        if (isInCornerOrNearMultipleWalls(currentPos, arena)) {
            log.info("🎯 Bomber {}: In corner/near multiple walls!", bomber.id);
            return true;
        }

        // 3. Рядом с разрушаемой стеной и врагом в радиусе
        if (isNextToWallAndEnemyInRange(currentPos, arena)) {
            log.info("🎯 Bomber {}: Wall + enemy in range!", bomber.id);
            return true;
        }

        // 4. Агрессивная установка бомбы при высоком уровне агрессии
        if (aggression > 70 && isNearAnyWallOrEnemy(currentPos, arena)) {
            log.info("🎯 Bomber {}: Aggressive bomb placement (aggression: {})", bomber.id, aggression);
            return true;
        }

        // 5. Если давно не ставили бомбы, ставим у любой ближайшей стены
        int ticksSinceLastBomb = tickCounter - lastBombTick.getOrDefault(bomber.id, 0);
        if (ticksSinceLastBomb > 30 && isNextToAnyWall(currentPos, arena)) {
            log.info("🎯 Bomber {}: Long time no bomb, placing near wall!", bomber.id);
            return true;
        }

        return false;
    }

    // НОВЫЙ МЕТОД: Проверяет, стоит ли бомбер прямо рядом с врагом
    private boolean isDirectlyNextToEnemy(int[] pos, ArenaResponse arena) {
        if (arena.enemies == null) return false;

        for (Enemy enemy : arena.enemies) {
            if (enemy.pos == null) continue;

            int dist = Math.abs(enemy.pos[0] - pos[0]) + Math.abs(enemy.pos[1] - pos[1]);
            if (dist == 1) { // Соседняя клетка
                return true;
            }
        }
        return false;
    }

    // НОВЫЙ МЕТОД: Проверяет, находится ли бомбер в углу или рядом с несколькими стенами
    private boolean isInCornerOrNearMultipleWalls(int[] pos, ArenaResponse arena) {
        int wallCount = 0;
        int[][] directions = {{1,0},{-1,0},{0,1},{0,-1}};

        for (int[] dir : directions) {
            int checkX = pos[0] + dir[0];
            int checkY = pos[1] + dir[1];

            if (isWall(checkX, checkY, arena)) {
                wallCount++;
            }
        }

        // Если рядом 2 или более стен - хорошее место для бомбы
        if (wallCount >= 2) {
            return true;
        }

        // Проверяем углы карты
        if ((pos[0] <= 2 && pos[1] <= 2) || // Левый верхний угол
                (pos[0] <= 2 && pos[1] >= arena.map_size[1] - 3) || // Левый нижний угол
                (pos[0] >= arena.map_size[0] - 3 && pos[1] <= 2) || // Правый верхний угол
                (pos[0] >= arena.map_size[0] - 3 && pos[1] >= arena.map_size[1] - 3)) { // Правый нижний угол
            return true;
        }

        return false;
    }

    // НОВЫЙ МЕТОД: Проверяет, есть ли рядом стена и враг в радиусе взрыва
    private boolean isNextToWallAndEnemyInRange(int[] pos, ArenaResponse arena) {
        if (!isNextToAnyWall(pos, arena)) return false;
        if (arena.enemies == null) return false;

        for (Enemy enemy : arena.enemies) {
            if (enemy.pos == null) continue;

            int dist = Math.abs(enemy.pos[0] - pos[0]) + Math.abs(enemy.pos[1] - pos[1]);
            // Враг в радиусе 3 клеток (радиус взрыва бомбы)
            if (dist <= 3) {
                // Проверяем, нет ли стены между нами
                if (isClearPathForBomb(pos, enemy.pos, arena)) {
                    return true;
                }
            }
        }

        return false;
    }

    // НОВЫЙ МЕТОД: Проверяет, рядом ли любая стена или враг
    private boolean isNearAnyWallOrEnemy(int[] pos, ArenaResponse arena) {
        return isNextToAnyWall(pos, arena) || isNextToEnemy(pos, arena);
    }

    // НОВЫЙ МЕТОД: Проверяет, рядом ли любая стена (разрушаемая)
    private boolean isNextToAnyWall(int[] pos, ArenaResponse arena) {
        if (arena.arena == null || arena.arena.obstacles == null) return false;

        int[][] directions = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int[] dir : directions) {
            int checkX = pos[0] + dir[0];
            int checkY = pos[1] + dir[1];

            if (isWall(checkX, checkY, arena)) {
                return true;
            }
        }
        return false;
    }

    // НОВЫЙ МЕТОД: Находит лучшее место для установки бомбы
    private int[] findBombPlacementTarget(Bomber bomber, ArenaResponse arena) {
        int[] currentPos = bomber.pos;
        int aggression = aggressionLevel.getOrDefault(bomber.id, 50);

        // При высоком уровне агрессии ищем любые стены поблизости
        if (aggression > 60) {
            // Ищем ближайшую стену для минирования
            int[] nearestWall = findNearestWall(currentPos, arena, 10); // Радиус поиска 10 клеток
            if (nearestWall != null) {
                log.debug("Bomber {} targeting wall at ({},{}) for bombing",
                        bomber.id, nearestWall[0], nearestWall[1]);
                return nearestWall;
            }
        }

        // Ищем позиции, где можно поставить бомбу чтобы достать несколько целей
        int[] multiTargetSpot = findMultiTargetBombSpot(bomber, arena);
        if (multiTargetSpot != null) {
            return multiTargetSpot;
        }

        // Ищем врагов рядом со стенами
        int[] enemyNearWall = findEnemyNearWall(currentPos, arena);
        if (enemyNearWall != null) {
            return enemyNearWall;
        }

        return null;
    }

    // НОВЫЙ МЕТОД: Находит ближайшую стену
    private int[] findNearestWall(int[] from, ArenaResponse arena, int maxRadius) {
        if (arena.arena == null || arena.arena.obstacles == null) return null;

        int[] bestWall = null;
        int bestDistance = Integer.MAX_VALUE;

        for (List<Integer> wall : arena.arena.obstacles) {
            if (wall.size() < 2) continue;

            int wallX = wall.get(0);
            int wallY = wall.get(1);

            int distance = Math.abs(wallX - from[0]) + Math.abs(wallY - from[1]);

            if (distance <= maxRadius && distance < bestDistance) {
                // Проверяем, есть ли путь к стене
                if (hasPathToWall(from, new int[]{wallX, wallY}, arena)) {
                    bestDistance = distance;
                    bestWall = new int[]{wallX, wallY};
                }
            }
        }

        return bestWall;
    }

    // НОВЫЙ МЕТОД: Проверяет, есть ли путь к стене
    private boolean hasPathToWall(int[] from, int[] to, ArenaResponse arena) {
        // Простая проверка - можно ли дойти по прямой (без препятствий)
        if (isClearPathForMovement(from, to, arena)) {
            return true;
        }

        // Если нельзя по прямой, проверяем соседние клетки
        int[][] directions = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int[] dir : directions) {
            int checkX = to[0] + dir[0];
            int checkY = to[1] + dir[1];

            if (isValidCell(checkX, checkY, arena) &&
                    !isObstacle(checkX, checkY, arena) &&
                    isClearPathForMovement(from, new int[]{checkX, checkY}, arena)) {
                return true;
            }
        }

        return false;
    }

    // НОВЫЙ МЕТОД: Находит позицию для бомбы, которая достанет несколько целей
    private int[] findMultiTargetBombSpot(Bomber bomber, ArenaResponse arena) {
        int[] currentPos = bomber.pos;

        // Ищем клетки в радиусе 5, где можно поставить бомбу
        for (int radius = 1; radius <= 5; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    if (Math.abs(dx) + Math.abs(dy) <= radius) {
                        int checkX = currentPos[0] + dx;
                        int checkY = currentPos[1] + dy;

                        if (!isValidCell(checkX, checkY, arena) ||
                                isObstacle(checkX, checkY, arena) ||
                                isOnBomb(new int[]{checkX, checkY}, arena)) {
                            continue;
                        }

                        // Оцениваем позицию для бомбы
                        int score = calculateBombSpotScore(checkX, checkY, arena);

                        // Если хорошая позиция (много целей)
                        if (score >= 3) { // Минимум 3 цели (стены или враги)
                            log.debug("Found multi-target bomb spot at ({},{}) with score {}",
                                    checkX, checkY, score);
                            return new int[]{checkX, checkY};
                        }
                    }
                }
            }
        }

        return null;
    }

    // НОВЫЙ МЕТОД: Оценивает позицию для установки бомбы
    private int calculateBombSpotScore(int x, int y, ArenaResponse arena) {
        int score = 0;
        int bombRadius = 3; // Предполагаемый радиус взрыва

        // Проверяем все клетки в радиусе взрыва
        for (int dx = -bombRadius; dx <= bombRadius; dx++) {
            for (int dy = -bombRadius; dy <= bombRadius; dy++) {
                if (Math.abs(dx) + Math.abs(dy) <= bombRadius) {
                    int checkX = x + dx;
                    int checkY = y + dy;

                    if (!isValidCell(checkX, checkY, arena)) continue;

                    // Стены в радиусе взрыва
                    if (isWall(checkX, checkY, arena)) {
                        score += 2; // Стены дают больше очков
                    }

                    // Враги в радиусе взрыва
                    if (arena.enemies != null) {
                        for (Enemy enemy : arena.enemies) {
                            if (enemy.pos != null &&
                                    enemy.pos[0] == checkX && enemy.pos[1] == checkY) {
                                score += 3; // Враги дают еще больше очков
                                break;
                            }
                        }
                    }
                }
            }
        }

        return score;
    }

    // НОВЫЙ МЕТОД: Находит врага рядом со стеной
    private int[] findEnemyNearWall(int[] from, ArenaResponse arena) {
        if (arena.enemies == null) return null;

        for (Enemy enemy : arena.enemies) {
            if (enemy.pos == null) continue;

            // Проверяем, есть ли стены рядом с врагом
            if (isNextToAnyWall(enemy.pos, arena)) {
                // Возвращаем позицию рядом с врагом (для установки бомбы)
                int[][] directions = {{1,0},{-1,0},{0,1},{0,-1}};
                for (int[] dir : directions) {
                    int bombX = enemy.pos[0] + dir[0];
                    int bombY = enemy.pos[1] + dir[1];

                    if (isValidCell(bombX, bombY, arena) &&
                            !isObstacle(bombX, bombY, arena) &&
                            !isOnBomb(new int[]{bombX, bombY}, arena)) {
                        return new int[]{bombX, bombY};
                    }
                }
            }
        }

        return null;
    }

    // НОВЫЙ МЕТОД: Движется к месту установки бомбы
    private MoveBomber moveToBombPlacement(Bomber bomber, int[] target, ArenaResponse arena) {
        lastTarget.put(bomber.id, target);

        int[] currentPos = bomber.pos;
        List<List<Integer>> path = new ArrayList<>();
        path.add(Arrays.asList(currentPos[0], currentPos[1]));

        log.debug("Bomber {} moving to bomb placement at ({},{})",
                bomber.id, target[0], target[1]);

        // Используем обычную логику движения к цели
        return moveToTarget(bomber, target, arena);
    }

    // НОВЫЙ МЕТОД: Патрулирует и ищет места для мин
    private MoveBomber patrolAndSearchForBombSpots(Bomber bomber, ArenaResponse arena) {
        int[] currentPos = bomber.pos;

        // Сначала проверяем, нельзя ли прямо здесь поставить бомбу
        if (isNextToAnyWall(currentPos, arena) && canPlaceBombAtTarget(bomber, currentPos, arena)) {
            lastAction.put(bomber.id, "BOMB");
            lastBombTick.put(bomber.id, tickCounter);
            return plantBombAndEscape(bomber, arena);
        }

        // Ищем ближайшую стену для патрулирования
        int[] nearestWall = findNearestWall(currentPos, arena, 15);
        if (nearestWall != null) {
            log.debug("Bomber {} patrolling to wall at ({},{})",
                    bomber.id, nearestWall[0], nearestWall[1]);
            return moveToTarget(bomber, nearestWall, arena);
        }

        // Если стен нет, используем обычное патрулирование
        return patrolInZone(bomber, arena);
    }

    // НОВЫЙ МЕТОД: Проверяет путь для движения (упрощенная версия)
    private boolean isClearPathForMovement(int[] from, int[] to, ArenaResponse arena) {
        // Проверяем линию между точками (грубая проверка)
        int steps = Math.max(Math.abs(to[0] - from[0]), Math.abs(to[1] - from[1]));

        for (int i = 1; i <= steps; i++) {
            float t = (float) i / steps;
            int checkX = Math.round(from[0] + (to[0] - from[0]) * t);
            int checkY = Math.round(from[1] + (to[1] - from[1]) * t);

            if (!isValidCell(checkX, checkY, arena) || isObstacle(checkX, checkY, arena)) {
                return false;
            }
        }

        return true;
    }

    // НОВЫЙ МЕТОД: Проверяет путь для бомбы (можно через 1 клетку)
    private boolean isClearPathForBomb(int[] from, int[] to, ArenaResponse arena) {
        int dist = Math.abs(to[0] - from[0]) + Math.abs(to[1] - from[1]);

        if (dist <= 1) return true;

        // Для расстояния 2 проверяем среднюю клетку
        if (dist == 2) {
            int midX = (from[0] + to[0]) / 2;
            int midY = (from[1] + to[1]) / 2;
            return !isWall(midX, midY, arena) && !isObstacle(midX, midY, arena);
        }

        // Для большего расстояния - упрощенная проверка
        return isClearPathForMovement(from, to, arena);
    }

    private boolean isTooCloseToOtherBombers(Bomber currentBomber, ArenaResponse arena) {
        if (arena.bombers == null || arena.bombers.size() <= 1) {
            return false;
        }

        int minDistance = 3;
        int tooCloseCount = 0;

        for (Bomber other : arena.bombers) {
            if (!other.alive || other.id.equals(currentBomber.id)) {
                continue;
            }

            int distance = Math.abs(other.pos[0] - currentBomber.pos[0]) +
                    Math.abs(other.pos[1] - currentBomber.pos[1]);

            if (distance < minDistance) {
                tooCloseCount++;
                if (tooCloseCount >= 2) {
                    log.debug("🚷 Bomber {} too close to others (distance: {})",
                            currentBomber.id, distance);
                    return true;
                }
            }
        }

        return false;
    }

    private MoveBomber spreadOut(Bomber bomber, ArenaResponse arena) {
        int[] currentPos = bomber.pos;
        List<List<Integer>> path = new ArrayList<>();
        path.add(Arrays.asList(currentPos[0], currentPos[1]));

        List<int[]> bestDirections = new ArrayList<>();
        int[][] directions = {{1,0},{-1,0},{0,1},{0,-1}};

        for (int[] dir : directions) {
            int newX = currentPos[0] + dir[0];
            int newY = currentPos[1] + dir[1];

            if (!isValidCell(newX, newY, arena) || isObstacle(newX, newY, arena)) {
                continue;
            }

            int averageDistance = calculateAverageDistanceToOthers(newX, newY, bomber, arena);

            if (averageDistance > 4) {
                bestDirections.add(new int[]{newX, newY, averageDistance});
            }
        }

        if (!bestDirections.isEmpty()) {
            bestDirections.sort((a, b) -> Integer.compare(b[2], a[2]));
            int[] bestDir = bestDirections.get(0);
            path.add(Arrays.asList(bestDir[0], bestDir[1]));

            int nextX = bestDir[0] + (bestDir[0] - currentPos[0]);
            int nextY = bestDir[1] + (bestDir[1] - currentPos[1]);
            if (isValidCell(nextX, nextY, arena) && !isObstacle(nextX, nextY, arena)) {
                path.add(Arrays.asList(nextX, nextY));
            }
        } else {
            return patrolInZone(bomber, arena);
        }

        log.debug("📈 Bomber {} spreading out from others", bomber.id);
        return new MoveBomber(bomber.id, path, new ArrayList<>());
    }

    private int calculateAverageDistanceToOthers(int x, int y, Bomber currentBomber, ArenaResponse arena) {
        int totalDistance = 0;
        int count = 0;

        for (Bomber other : arena.bombers) {
            if (!other.alive || other.id.equals(currentBomber.id)) {
                continue;
            }

            int distance = Math.abs(other.pos[0] - x) + Math.abs(other.pos[1] - y);
            totalDistance += distance;
            count++;
        }

        return count > 0 ? totalDistance / count : 0;
    }

    private int[] findIndividualTarget(Bomber bomber, ArenaResponse arena) {
        int[] currentPos = bomber.pos;
        int groupId = bomberGroup.get(bomber.id);

        int mapWidth = arena.map_size[0];
        int mapHeight = arena.map_size[1];

        int zoneStartX, zoneEndX, zoneStartY, zoneEndY;

        switch (groupId % 3) {
            case 0:
                zoneStartX = 0;
                zoneEndX = mapWidth / 3;
                zoneStartY = 0;
                zoneEndY = mapHeight;
                break;
            case 1:
                zoneStartX = mapWidth / 3;
                zoneEndX = 2 * mapWidth / 3;
                zoneStartY = 0;
                zoneEndY = mapHeight;
                break;
            case 2:
                zoneStartX = 2 * mapWidth / 3;
                zoneEndX = mapWidth;
                zoneStartY = 0;
                zoneEndY = mapHeight;
                break;
            default:
                zoneStartX = 0;
                zoneEndX = mapWidth;
                zoneStartY = 0;
                zoneEndY = mapHeight;
        }

        int[] bestTarget = null;
        int bestScore = -1;

        // 1. Ищем врагов в своей зоне
        if (arena.enemies != null) {
            for (Enemy enemy : arena.enemies) {
                if (enemy.pos == null) continue;

                if (enemy.pos[0] >= zoneStartX && enemy.pos[0] < zoneEndX &&
                        enemy.pos[1] >= zoneStartY && enemy.pos[1] < zoneEndY) {

                    int dist = Math.abs(enemy.pos[0] - currentPos[0]) +
                            Math.abs(enemy.pos[1] - currentPos[1]);

                    if (dist <= 8) {
                        int score = 150 - dist * 5;
                        score += 50;

                        if (score > bestScore) {
                            bestScore = score;
                            bestTarget = enemy.pos;
                        }
                    }
                }
            }
        }

        // 2. Если в своей зоне нет врагов, ищем в других зонах
        if (bestTarget == null) {
            bestTarget = findBestBombTarget(bomber, arena);
        }

        // 3. Если нашли цель, проверяем, не преследует ли ее другой бомбер
        if (bestTarget != null && isTargetBeingPursued(bestTarget, bomber.id, arena)) {
            log.debug("Target at ({},{}) already pursued, finding alternative",
                    bestTarget[0], bestTarget[1]);

            return findAlternativeTarget(bomber, arena, bestTarget);
        }

        return bestTarget;
    }

    private boolean isTargetBeingPursued(int[] target, String bomberId, ArenaResponse arena) {
        if (target == null) return false;

        for (Bomber other : arena.bombers) {
            if (!other.alive || other.id.equals(bomberId)) {
                continue;
            }

            int[] otherTarget = lastTarget.get(other.id);
            if (otherTarget != null) {
                int distToTarget = Math.abs(otherTarget[0] - target[0]) +
                        Math.abs(otherTarget[1] - target[1]);
                if (distToTarget < 3) {
                    return true;
                }
            }
        }

        return false;
    }

    private int[] findAlternativeTarget(Bomber bomber, ArenaResponse arena, int[] avoidTarget) {
        int[] currentPos = bomber.pos;
        int[] bestTarget = null;
        int bestScore = -1;

        int preferredDir = preferredDirection.get(bomber.id);
        int[][] directionOffsets = {{1,0},{-1,0},{0,1},{0,-1}};
        int[] mainDir = directionOffsets[preferredDir % 4];

        if (arena.enemies != null) {
            for (Enemy enemy : arena.enemies) {
                if (enemy.pos == null) continue;

                if (avoidTarget != null &&
                        enemy.pos[0] == avoidTarget[0] && enemy.pos[1] == avoidTarget[1]) {
                    continue;
                }

                int dist = Math.abs(enemy.pos[0] - currentPos[0]) +
                        Math.abs(enemy.pos[1] - currentPos[1]);

                if (dist <= 10) {
                    int dirBonus = 0;
                    int dx = Integer.compare(enemy.pos[0], currentPos[0]);
                    int dy = Integer.compare(enemy.pos[1], currentPos[1]);

                    if ((dx == mainDir[0] && dy == mainDir[1]) ||
                            (dx == -mainDir[0] && dy == -mainDir[1])) {
                        dirBonus = 30;
                    }

                    int score = 100 - dist * 3 + dirBonus;

                    if (score > bestScore) {
                        bestScore = score;
                        bestTarget = enemy.pos;
                    }
                }
            }
        }

        return bestTarget;
    }

    private MoveBomber moveToTarget(Bomber bomber, int[] target, ArenaResponse arena) {
        lastTarget.put(bomber.id, target);

        int[] currentPos = bomber.pos;
        List<List<Integer>> path = new ArrayList<>();
        path.add(Arrays.asList(currentPos[0], currentPos[1]));

        if (isTargetBeingPursued(target, bomber.id, arena)) {
            return moveToTargetWithOffset(bomber, target, arena);
        }

        int dx = Integer.compare(target[0], currentPos[0]);
        int dy = Integer.compare(target[1], currentPos[1]);

        if (dx != 0) {
            int newX = currentPos[0] + dx;
            if (isValidCell(newX, currentPos[1], arena) &&
                    !isObstacle(newX, currentPos[1], arena) &&
                    !isTooCloseToCell(new int[]{newX, currentPos[1]}, bomber, arena)) {
                path.add(Arrays.asList(newX, currentPos[1]));
            }
        } else if (dy != 0) {
            int newY = currentPos[1] + dy;
            if (isValidCell(currentPos[0], newY, arena) &&
                    !isObstacle(currentPos[0], newY, arena) &&
                    !isTooCloseToCell(new int[]{currentPos[0], newY}, bomber, arena)) {
                path.add(Arrays.asList(currentPos[0], newY));
            }
        }

        if (path.size() == 1) {
            int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
            List<int[]> possibleMoves = new ArrayList<>();

            for (int[] dir : dirs) {
                int newX = currentPos[0] + dir[0];
                int newY = currentPos[1] + dir[1];
                if (isValidCell(newX, newY, arena) &&
                        !isObstacle(newX, newY, arena) &&
                        !isTooCloseToCell(new int[]{newX, newY}, bomber, arena)) {

                    int newDist = Math.abs(target[0] - newX) + Math.abs(target[1] - newY);
                    int currentDist = Math.abs(target[0] - currentPos[0]) +
                            Math.abs(target[1] - currentPos[1]);

                    if (newDist < currentDist) {
                        possibleMoves.add(0, new int[]{newX, newY, newDist});
                    } else {
                        possibleMoves.add(new int[]{newX, newY, newDist});
                    }
                }
            }

            if (!possibleMoves.isEmpty()) {
                possibleMoves.sort((a, b) -> Integer.compare(a[2], b[2]));
                int[] bestMove = possibleMoves.get(0);
                path.add(Arrays.asList(bestMove[0], bestMove[1]));
            }
        }

        return new MoveBomber(bomber.id, path, new ArrayList<>());
    }

    private MoveBomber moveToTargetWithOffset(Bomber bomber, int[] target, ArenaResponse arena) {
        int[] currentPos = bomber.pos;
        List<List<Integer>> path = new ArrayList<>();
        path.add(Arrays.asList(currentPos[0], currentPos[1]));

        int preferredDir = preferredDirection.get(bomber.id);

        int[][] dirs;
        switch (preferredDir % 4) {
            case 0:
                dirs = new int[][]{{1,0},{0,1},{0,-1},{-1,0}};
                break;
            case 1:
                dirs = new int[][]{{-1,0},{0,1},{0,-1},{1,0}};
                break;
            case 2:
                dirs = new int[][]{{0,1},{1,0},{-1,0},{0,-1}};
                break;
            case 3:
                dirs = new int[][]{{0,-1},{1,0},{-1,0},{0,1}};
                break;
            default:
                dirs = new int[][]{{1,0},{-1,0},{0,1},{0,-1}};
        }

        for (int[] dir : dirs) {
            int newX = currentPos[0] + dir[0];
            int newY = currentPos[1] + dir[1];

            if (isValidCell(newX, newY, arena) &&
                    !isObstacle(newX, newY, arena) &&
                    !isTooCloseToCell(new int[]{newX, newY}, bomber, arena)) {

                path.add(Arrays.asList(newX, newY));

                int nextX = newX + dir[0];
                int nextY = newY + dir[1];
                if (isValidCell(nextX, nextY, arena) &&
                        !isObstacle(nextX, nextY, arena)) {
                    path.add(Arrays.asList(nextX, nextY));
                }

                break;
            }
        }

        log.debug("Bomber {} taking offset route to avoid others", bomber.id);
        return new MoveBomber(bomber.id, path, new ArrayList<>());
    }

    private MoveBomber patrolInZone(Bomber bomber, ArenaResponse arena) {
        int[] currentPos = bomber.pos;
        List<List<Integer>> path = new ArrayList<>();
        path.add(Arrays.asList(currentPos[0], currentPos[1]));

        int preferredDir = preferredDirection.get(bomber.id);
        int groupId = bomberGroup.get(bomber.id);

        int[][] dirs;
        switch (groupId % 4) {
            case 0:
                dirs = new int[][]{{1,0},{0,1},{0,-1},{-1,0}};
                break;
            case 1:
                dirs = new int[][]{{-1,0},{0,-1},{0,1},{1,0}};
                break;
            case 2:
                dirs = new int[][]{{0,1},{1,0},{-1,0},{0,-1}};
                break;
            default:
                dirs = new int[][]{{0,-1},{-1,0},{1,0},{0,1}};
        }

        for (int[] dir : dirs) {
            int newX = currentPos[0] + dir[0];
            int newY = currentPos[1] + dir[1];

            if (isValidCell(newX, newY, arena) &&
                    !isObstacle(newX, newY, arena) &&
                    !isOnBomb(new int[]{newX, newY}, arena) &&
                    !isTooCloseToCell(new int[]{newX, newY}, bomber, arena)) {

                path.add(Arrays.asList(newX, newY));
                break;
            }
        }

        if (path.size() == 1) {
            return safePatrol(bomber, arena);
        }

        return new MoveBomber(bomber.id, path, new ArrayList<>());
    }

    private boolean isTooCloseToCell(int[] cell, Bomber currentBomber, ArenaResponse arena) {
        for (Bomber other : arena.bombers) {
            if (!other.alive || other.id.equals(currentBomber.id)) {
                continue;
            }

            int distance = Math.abs(other.pos[0] - cell[0]) +
                    Math.abs(other.pos[1] - cell[1]);

            if (distance < 2) {
                return true;
            }
        }

        return false;
    }

    private boolean canPlaceBombAtTarget(Bomber bomber, int[] currentPos, ArenaResponse arena) {
        if (bombCooldown.containsKey(bomber.id) && bombCooldown.get(bomber.id) > 0) {
            return false;
        }
        if (bomber.bombs_available <= 0) {
            return false;
        }
        if (isOnBomb(currentPos, arena)) {
            return false;
        }
        return isNextToEnemy(currentPos, arena) || isNextToWall(currentPos, arena);
    }

    private MoveBomber plantBombAndEscape(Bomber bomber, ArenaResponse arena) {
        int[] currentPos = bomber.pos;
        List<List<Integer>> bombs = new ArrayList<>();
        bombs.add(Arrays.asList(currentPos[0], currentPos[1]));
        bombCooldown.put(bomber.id, 6);

        // Увеличиваем счетчик успешных бомб и агрессию
        int currentBombs = successfulBombs.getOrDefault(bomber.id, 0);
        successfulBombs.put(bomber.id, currentBombs + 1);

        int currentAggression = aggressionLevel.getOrDefault(bomber.id, 50);
        aggressionLevel.put(bomber.id, Math.min(100, currentAggression + 10));

        log.info("💣💣💣 Bomber {} PLANTING BOMB at ({},{}) - ESCAPING! (Aggression: {}, Total bombs: {})",
                bomber.id, currentPos[0], currentPos[1],
                aggressionLevel.get(bomber.id), successfulBombs.get(bomber.id));

        List<List<Integer>> escapePath = findSafeEscapePath(currentPos, arena);
        return new MoveBomber(bomber.id, escapePath, bombs);
    }

    // ===== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ =====

    private int[] findBestBombTarget(Bomber bomber, ArenaResponse arena) {
        int[] currentPos = bomber.pos;
        int[] bestTarget = null;
        int bestScore = -1;

        if (arena.enemies != null) {
            for (Enemy enemy : arena.enemies) {
                if (enemy.pos == null) continue;

                int dist = Math.abs(enemy.pos[0] - currentPos[0]) +
                        Math.abs(enemy.pos[1] - currentPos[1]);

                if (dist <= 5) {
                    int score = 100 - dist * 10;
                    if (score > bestScore) {
                        bestScore = score;
                        bestTarget = enemy.pos;
                    }
                }
            }
        }

        if (bestTarget == null && arena.arena != null && arena.arena.obstacles != null) {
            for (List<Integer> wall : arena.arena.obstacles) {
                if (wall.size() < 2) continue;

                int dist = Math.abs(wall.get(0) - currentPos[0]) +
                        Math.abs(wall.get(1) - currentPos[1]);

                if (dist <= 7) {
                    int score = 50 - dist * 5;
                    if (score > bestScore) {
                        bestScore = score;
                        bestTarget = new int[]{wall.get(0), wall.get(1)};
                    }
                }
            }
        }

        if (bestTarget != null) {
            log.debug("Found target at ({},{}) with score {}",
                    bestTarget[0], bestTarget[1], bestScore);
        }

        return bestTarget;
    }

    private MoveBomber safePatrol(Bomber bomber, ArenaResponse arena) {
        int[] currentPos = bomber.pos;
        List<List<Integer>> path = new ArrayList<>();
        path.add(Arrays.asList(currentPos[0], currentPos[1]));

        int[][] directions = {{1,0},{-1,0},{0,1},{0,-1}};
        List<int[]> safeDirections = new ArrayList<>();

        for (int[] dir : directions) {
            int newX = currentPos[0] + dir[0];
            int newY = currentPos[1] + dir[1];

            if (isValidCell(newX, newY, arena) &&
                    !isObstacle(newX, newY, arena) &&
                    !isOnBomb(new int[]{newX, newY}, arena)) {
                safeDirections.add(new int[]{newX, newY});
            }
        }

        if (!safeDirections.isEmpty()) {
            int[] chosenDir = safeDirections.get(random.nextInt(safeDirections.size()));
            path.add(Arrays.asList(chosenDir[0], chosenDir[1]));
        }

        return new MoveBomber(bomber.id, path, new ArrayList<>());
    }

    private boolean isNextToEnemy(int[] pos, ArenaResponse arena) {
        if (arena.enemies == null) return false;

        for (Enemy enemy : arena.enemies) {
            if (enemy.pos == null) continue;

            int dist = Math.abs(enemy.pos[0] - pos[0]) + Math.abs(enemy.pos[1] - pos[1]);
            if (dist == 1) {
                log.info("✅ Enemy RIGHT NEXT to us at ({},{})", enemy.pos[0], enemy.pos[1]);
                return true;
            }

            if (dist == 2) {
                int midX = (enemy.pos[0] + pos[0]) / 2;
                int midY = (enemy.pos[1] + pos[1]) / 2;
                if (!isWall(midX, midY, arena)) {
                    log.info("✅ Enemy 2 cells away at ({},{})", enemy.pos[0], enemy.pos[1]);
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isNextToWall(int[] pos, ArenaResponse arena) {
        if (arena.arena == null || arena.arena.obstacles == null) return false;

        int[][] directions = {{1,0},{-1,0},{0,1},{0,-1}};

        for (int[] dir : directions) {
            int checkX = pos[0] + dir[0];
            int checkY = pos[1] + dir[1];

            if (isWall(checkX, checkY, arena)) {
                log.info("✅ Wall RIGHT NEXT to us at ({},{})", checkX, checkY);
                return true;
            }

            int checkX2 = pos[0] + dir[0] * 2;
            int checkY2 = pos[1] + dir[1] * 2;
            if (isWall(checkX2, checkY2, arena) && !isObstacle(checkX, checkY, arena)) {
                log.info("✅ Wall 2 cells away at ({},{})", checkX2, checkY2);
                return true;
            }
        }

        return false;
    }

    private List<List<Integer>> findSafeEscapePath(int[] from, ArenaResponse arena) {
        List<List<Integer>> path = new ArrayList<>();
        path.add(Arrays.asList(from[0], from[1]));

        int[][] directions = {{1,0},{-1,0},{0,1},{0,-1}};

        for (int[] dir : directions) {
            boolean canEscape = true;
            List<int[]> escapeCells = new ArrayList<>();

            for (int i = 1; i <= 3; i++) {
                int checkX = from[0] + dir[0] * i;
                int checkY = from[1] + dir[1] * i;

                if (!isValidCell(checkX, checkY, arena) || isObstacle(checkX, checkY, arena)) {
                    canEscape = false;
                    break;
                }
                escapeCells.add(new int[]{checkX, checkY});
            }

            if (canEscape && !escapeCells.isEmpty()) {
                for (int[] cell : escapeCells) {
                    path.add(Arrays.asList(cell[0], cell[1]));
                }
                return path;
            }
        }

        for (int[] dir : directions) {
            int step1X = from[0] + dir[0];
            int step1Y = from[1] + dir[1];
            int step2X = step1X + dir[0];
            int step2Y = step1Y + dir[1];

            if (isValidCell(step1X, step1Y, arena) && !isObstacle(step1X, step1Y, arena) &&
                    isValidCell(step2X, step2Y, arena) && !isObstacle(step2X, step2Y, arena)) {
                path.add(Arrays.asList(step1X, step1Y));
                path.add(Arrays.asList(step2X, step2Y));
                return path;
            }
        }

        for (int[] dir : directions) {
            int newX = from[0] + dir[0];
            int newY = from[1] + dir[1];

            if (isValidCell(newX, newY, arena) && !isObstacle(newX, newY, arena)) {
                path.add(Arrays.asList(newX, newY));
                return path;
            }
        }

        log.warn("⚠️ No escape path found!");
        return path;
    }

    private MoveBomber continueEscaping(Bomber bomber, ArenaResponse arena) {
        int[] currentPos = bomber.pos;
        List<List<Integer>> path = new ArrayList<>();
        path.add(Arrays.asList(currentPos[0], currentPos[1]));

        log.debug("Bomber {} continuing escape", bomber.id);

        int[][] directions = {{1,0},{-1,0},{0,1},{0,-1}};

        for (int[] dir : directions) {
            int newX = currentPos[0] + dir[0];
            int newY = currentPos[1] + dir[1];

            if (isValidCell(newX, newY, arena) && !isObstacle(newX, newY, arena)) {
                path.add(Arrays.asList(newX, newY));
                break;
            }
        }

        if (!isNextToEnemy(currentPos, arena) && !isNextToWall(currentPos, arena)) {
            lastAction.put(bomber.id, "PATROL");
        }

        return new MoveBomber(bomber.id, path, new ArrayList<>());
    }

    private boolean isWall(int x, int y, ArenaResponse arena) {
        if (arena.arena == null || arena.arena.obstacles == null) return false;

        for (List<Integer> wall : arena.arena.obstacles) {
            if (wall.size() >= 2 && wall.get(0) == x && wall.get(1) == y) {
                return true;
            }
        }

        return false;
    }

    private boolean isOnBomb(int[] pos, ArenaResponse arena) {
        if (arena.arena == null || arena.arena.bombs == null) return false;

        for (Bomb bomb : arena.arena.bombs) {
            if (bomb.pos == null || bomb.pos.length < 2) continue;
            if (bomb.pos[0] == pos[0] && bomb.pos[1] == pos[1]) {
                return true;
            }
        }
        return false;
    }

    private boolean isObstacle(int x, int y, ArenaResponse arena) {
        if (arena.arena == null) return false;

        if (arena.arena.walls != null) {
            for (List<Integer> wall : arena.arena.walls) {
                if (wall.size() >= 2 && wall.get(0) == x && wall.get(1) == y) {
                    return true;
                }
            }
        }

        if (arena.arena.obstacles != null) {
            for (List<Integer> obstacle : arena.arena.obstacles) {
                if (obstacle.size() >= 2 && obstacle.get(0) == x && obstacle.get(1) == y) {
                    return true;
                }
            }
        }

        if (arena.bombers != null) {
            for (Bomber other : arena.bombers) {
                if (other.alive && other.pos[0] == x && other.pos[1] == y) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isValidCell(int x, int y, ArenaResponse arena) {
        return x >= 0 && x < arena.map_size[0] && y >= 0 && y < arena.map_size[1];
    }

    private void logBomberAction(Bomber bomber, MoveBomber command) {
        if (command.getBombs() != null && !command.getBombs().isEmpty()) {
            log.info("🔥 Bomber {} (group {}): PLANTED BOMB and moving {} cells (Aggression: {})",
                    bomber.id, bomberGroup.get(bomber.id),
                    command.getPath().size() - 1, aggressionLevel.get(bomber.id));
        } else {
            log.debug("Bomber {} (group {}): Moving {} cells (action: {}, Aggression: {})",
                    bomber.id, bomberGroup.get(bomber.id),
                    command.getPath().size() - 1, lastAction.get(bomber.id),
                    aggressionLevel.get(bomber.id));
        }
    }

    // ДОПОЛНЕНИЕ: Метод для очистки устаревших данных о мертвых бомберах
    private void cleanupDeadBombers(ArenaResponse arena) {
        Set<String> aliveBomberIds = new HashSet<>();
        for (Bomber bomber : arena.bombers) {
            if (bomber.alive) {
                aliveBomberIds.add(bomber.id);
            }
        }

        bombCooldown.keySet().removeIf(id -> !aliveBomberIds.contains(id));
        lastAction.keySet().removeIf(id -> !aliveBomberIds.contains(id));
        bomberGroup.keySet().removeIf(id -> !aliveBomberIds.contains(id));
        preferredDirection.keySet().removeIf(id -> !aliveBomberIds.contains(id));
        lastTarget.keySet().removeIf(id -> !aliveBomberIds.contains(id));
        aggressionLevel.keySet().removeIf(id -> !aliveBomberIds.contains(id));
        successfulBombs.keySet().removeIf(id -> !aliveBomberIds.contains(id));
        lastBombTick.keySet().removeIf(id -> !aliveBomberIds.contains(id));
    }

    // ДОПОЛНЕНИЕ: Метод для обновления предпочтительного направления
    private void updatePreferredDirection(String bomberId, int[] currentPos, int[] targetPos) {
        if (targetPos == null) return;

        int dx = Integer.compare(targetPos[0], currentPos[0]);
        int dy = Integer.compare(targetPos[1], currentPos[1]);

        int newDirection = 0;
        if (dx > 0) newDirection = 0;
        else if (dx < 0) newDirection = 1;
        else if (dy > 0) newDirection = 2;
        else if (dy < 0) newDirection = 3;

        preferredDirection.put(bomberId, newDirection);
    }

    // ДОПОЛНЕНИЕ: Метод для проверки, свободна ли клетка от других бомберов
    private boolean isCellFreeFromOtherBombers(int x, int y, String currentBomberId, ArenaResponse arena) {
        if (arena.bombers == null) return true;

        for (Bomber other : arena.bombers) {
            if (!other.alive || other.id.equals(currentBomberId)) {
                continue;
            }

            if (other.pos[0] == x && other.pos[1] == y) {
                return false;
            }
        }

        return true;
    }

    // ДОПОЛНЕНИЕ: Метод для поиска ближайшего безопасного места
    private int[] findNearestSafeSpot(int[] from, ArenaResponse arena) {
        int[][] directions = {{1,0},{-1,0},{0,1},{0,-1}};

        for (int[] dir : directions) {
            int newX = from[0] + dir[0];
            int newY = from[1] + dir[1];

            if (isValidCell(newX, newY, arena) &&
                    !isObstacle(newX, newY, arena) &&
                    !isOnBomb(new int[]{newX, newY}, arena)) {
                return new int[]{newX, newY};
            }
        }

        for (int radius = 2; radius <= 5; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    if (Math.abs(dx) + Math.abs(dy) == radius) {
                        int newX = from[0] + dx;
                        int newY = from[1] + dy;

                        if (isValidCell(newX, newY, arena) &&
                                !isObstacle(newX, newY, arena) &&
                                !isOnBomb(new int[]{newX, newY}, arena)) {
                            return new int[]{newX, newY};
                        }
                    }
                }
            }
        }

        return null;
    }
}