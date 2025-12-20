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

    // Убраны поля для агрессивного минирования
    private final Map<String, Integer> lastBombTick = new HashMap<>(); // Тик последней установки бомбы

    // Новые поля для отслеживания убегания от мин
    private final Map<String, Integer> escapeTicks = new HashMap<>(); // Сколько тиков убегаем
    private final Map<String, int[]> escapeFromPos = new HashMap<>(); // Откуда убегаем
    private final Map<String, int[]> escapeDirection = new HashMap<>(); // Направление убегания

    // Константы
    private static final int BOMB_RADIUS = 1;      // Радиус взрыва бомбы (только соседние клетки)
    private static final int BOMBER_VISION = 5;    // Радиус обзора бомбера (5 клеток)

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

            // Проверяем, нужно ли продолжать убегать от бомбы
            if (shouldContinueEscaping(bomber.id, arena)) {
                lastAction.put(bomber.id, "ESCAPE");
                MoveBomber escapeCommand = continueBombEscape(bomber, arena);
                if (escapeCommand != null) {
                    commands.add(escapeCommand);
                    logBomberAction(bomber, escapeCommand);
                }
                continue;
            }

            // Проверяем, не находимся ли мы в опасной зоне взрыва
            if (isInDangerZone(bomber.pos, arena)) {
                lastAction.put(bomber.id, "DANGER_ESCAPE");
                MoveBomber dangerEscape = escapeFromDanger(bomber, arena);
                if (dangerEscape != null) {
                    commands.add(dangerEscape);
                    logBomberAction(bomber, dangerEscape);
                }
                continue;
            }

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

    private void initializeBombers(ArenaResponse arena) {
        for (Bomber bomber : arena.bombers) {
            if (!bomberGroup.containsKey(bomber.id)) {
                bomberGroup.put(bomber.id, groupCounter % 3);
                groupCounter++;

                preferredDirection.put(bomber.id, random.nextInt(4));
                lastBombTick.put(bomber.id, 0);
                escapeTicks.put(bomber.id, 0);

                log.info("🎯 Bomber {} assigned to group {}, direction {}",
                        bomber.id, bomberGroup.get(bomber.id),
                        preferredDirection.get(bomber.id));
            }
        }
    }

    private MoveBomber createSmartBombCommand(Bomber bomber, ArenaResponse arena) {
        int[] currentPos = bomber.pos;

        // Проверяем, не находимся ли мы на бомбе или рядом с ней
        if (isOnBomb(currentPos, arena) || isNextToBomb(currentPos, arena)) {
            lastAction.put(bomber.id, "ESCAPE_BOMB");
            return escapeFromBombImmediately(bomber, arena);
        }

        // Проверяем, не слишком ли мы близко к другим бомберам
        if (isTooCloseToOtherBombers(bomber, arena)) {
            lastAction.put(bomber.id, "SPREAD");
            return spreadOut(bomber, arena);
        }

        // ПОВЫШЕННЫЙ ПРИОРИТЕТ: Проверяем возможность поставить стратегическую бомбу
        if (shouldPlantStrategicBomb(bomber, arena)) {
            lastAction.put(bomber.id, "STRATEGIC_BOMB");
            lastBombTick.put(bomber.id, tickCounter);
            return plantBombAndEscapeSafely(bomber, arena);
        }

        // Если только что поставили бомбу - продолжаем убегать
        if ("STRATEGIC_BOMB".equals(lastAction.get(bomber.id))) {
            lastAction.put(bomber.id, "ESCAPE");
            return continueEscaping(bomber, arena);
        }

        // Ищем ЦЕЛЬ ДЛЯ МИНИРОВАНИЯ (стратегическую позицию)
        int[] bombTarget = findStrategicBombPlacement(bomber, arena);

        if (bombTarget != null) {
            lastAction.put(bomber.id, "MOVE_TO_BOMB");
            return moveToBombPlacement(bomber, bombTarget, arena);
        }

        // Ищем ближайшую цель ДЛЯ ЭТОГО БОМБЕРА в радиусе обзора
        int[] target = findIndividualTarget(bomber, arena);

        if (target != null) {
            lastAction.put(bomber.id, "MOVE");
            return moveToTarget(bomber, target, arena);
        }

        // Если нет целей в радиусе обзора - патрулируем в своей зоне, ища стратегические места для мин
        lastAction.put(bomber.id, "PATROL");
        return patrolAndSearchForBombSpots(bomber, arena);
    }

    // НОВЫЙ МЕТОД: Проверяет, нужно ли продолжать убегать
    private boolean shouldContinueEscaping(String bomberId, ArenaResponse arena) {
        Integer escapeTicksCount = escapeTicks.get(bomberId);
        if (escapeTicksCount == null || escapeTicksCount <= 0) {
            return false;
        }

        // Уменьшаем счетчик убегания
        escapeTicks.put(bomberId, escapeTicksCount - 1);

        // Если еще не убежали достаточно далеко, продолжаем
        int[] escapeFrom = escapeFromPos.get(bomberId);
        if (escapeFrom == null) {
            escapeTicks.put(bomberId, 0);
            return false;
        }

        Bomber bomber = findBomberById(bomberId, arena);
        if (bomber == null) {
            escapeTicks.put(bomberId, 0);
            return false;
        }

        // Проверяем расстояние от точки, откуда убегаем
        int distance = Math.abs(bomber.pos[0] - escapeFrom[0]) + Math.abs(bomber.pos[1] - escapeFrom[1]);

        // Если убежали на 4+ клетки, можно прекращать
        if (distance >= 4) {
            escapeTicks.put(bomberId, 0);
            return false;
        }

        // Если все еще в опасной зоне, продолжаем
        if (isInDangerZone(bomber.pos, arena)) {
            return true;
        }

        // Убегаем минимум 3 тика
        return escapeTicksCount > 0;
    }

    // НОВЫЙ МЕТОД: Убегание от опасной зоны
    private MoveBomber escapeFromDanger(Bomber bomber, ArenaResponse arena) {
        int[] currentPos = bomber.pos;
        List<List<Integer>> path = new ArrayList<>();
        path.add(Arrays.asList(currentPos[0], currentPos[1]));

        log.info("🚨 Bomber {} in DANGER ZONE! Escaping immediately!", bomber.id);

        // Ищем самое безопасное направление (подальше от бомб)
        int[] safeDirection = findSafestEscapeDirection(currentPos, arena);
        if (safeDirection != null) {
            int newX = currentPos[0] + safeDirection[0];
            int newY = currentPos[1] + safeDirection[1];

            if (isValidCell(newX, newY, arena) && !isObstacle(newX, newY, arena)) {
                path.add(Arrays.asList(newX, newY));

                // Пробуем убежать на 2 клетки
                int nextX = newX + safeDirection[0];
                int nextY = newY + safeDirection[1];
                if (isValidCell(nextX, nextY, arena) && !isObstacle(nextX, nextY, arena)) {
                    path.add(Arrays.asList(nextX, nextY));
                }
            }
        }

        // Если не нашли безопасного направления, идем в любую сторону
        if (path.size() == 1) {
            int[][] directions = {{1,0},{-1,0},{0,1},{0,-1}};
            for (int[] dir : directions) {
                int newX = currentPos[0] + dir[0];
                int newY = currentPos[1] + dir[1];
                if (isValidCell(newX, newY, arena) && !isObstacle(newX, newY, arena)) {
                    path.add(Arrays.asList(newX, newY));
                    break;
                }
            }
        }

        return new MoveBomber(bomber.id, path, new ArrayList<>());
    }

    // НОВЫЙ МЕТОД: Находит самое безопасное направление для убегания
    private int[] findSafestEscapeDirection(int[] from, ArenaResponse arena) {
        int[][] directions = {{1,0},{-1,0},{0,1},{0,-1}};
        int[] safestDir = null;
        int maxSafetyScore = -1000;

        for (int[] dir : directions) {
            int safetyScore = 0;

            // Проверяем 3 клетки в этом направлении
            for (int i = 1; i <= 3; i++) {
                int checkX = from[0] + dir[0] * i;
                int checkY = from[1] + dir[1] * i;

                if (!isValidCell(checkX, checkY, arena)) {
                    safetyScore -= 50; // Вне карты - плохо
                    break;
                }

                if (isObstacle(checkX, checkY, arena)) {
                    safetyScore -= 30; // Препятствие - плохо
                    break;
                }

                if (isOnBomb(new int[]{checkX, checkY}, arena)) {
                    safetyScore -= 100; // Бомба - очень плохо
                }

                if (isNextToBomb(new int[]{checkX, checkY}, arena)) {
                    safetyScore -= 50; // Рядом с бомбой - плохо
                }

                // Бонус за расстояние от бомб
                int bombDistance = getMinDistanceToBomb(checkX, checkY, arena);
                safetyScore += bombDistance * 10;

                // Бонус за открытое пространство
                if (!isObstacle(checkX, checkY, arena)) {
                    safetyScore += 5;
                }
            }

            if (safetyScore > maxSafetyScore) {
                maxSafetyScore = safetyScore;
                safestDir = dir;
            }
        }

        return safestDir;
    }

    // НОВЫЙ МЕТОД: Получает минимальное расстояние до бомбы
    private int getMinDistanceToBomb(int x, int y, ArenaResponse arena) {
        if (arena.arena == null || arena.arena.bombs == null) {
            return 10; // Нет бомб - безопасно
        }

        int minDistance = Integer.MAX_VALUE;
        for (Bomb bomb : arena.arena.bombs) {
            if (bomb.pos == null || bomb.pos.length < 2) continue;

            int distance = Math.abs(bomb.pos[0] - x) + Math.abs(bomb.pos[1] - y);
            if (distance < minDistance) {
                minDistance = distance;
            }
        }

        return minDistance == Integer.MAX_VALUE ? 10 : minDistance;
    }

    // НОВЫЙ МЕТОД: Проверяет, рядом ли с позицией есть бомба
    private boolean isNextToBomb(int[] pos, ArenaResponse arena) {
        if (arena.arena == null || arena.arena.bombs == null) return false;

        for (Bomb bomb : arena.arena.bombs) {
            if (bomb.pos == null || bomb.pos.length < 2) continue;

            int distance = Math.abs(bomb.pos[0] - pos[0]) + Math.abs(bomb.pos[1] - pos[1]);
            if (distance <= 1) { // Бомба в соседней клетке
                return true;
            }
        }
        return false;
    }

    // ИСПРАВЛЕННЫЙ МЕТОД: Проверяет, находимся ли в опасной зоне (в радиусе взрыва бомбы по кресту)
    private boolean isInDangerZone(int[] pos, ArenaResponse arena) {
        if (arena.arena == null || arena.arena.bombs == null) return false;

        for (Bomb bomb : arena.arena.bombs) {
            if (bomb.pos == null || bomb.pos.length < 2) continue;

            // Проверяем только по кресту (вертикаль и горизонталь)
            if (bomb.pos[0] == pos[0]) { // Одинаковая X координата - вертикальная линия
                int minY = Math.min(bomb.pos[1], pos[1]);
                int maxY = Math.max(bomb.pos[1], pos[1]);
                boolean clearPath = true;

                // Проверяем клетки между бомбой и позицией
                for (int y = minY + 1; y < maxY; y++) {
                    if (isWall(bomb.pos[0], y, arena) || isObstacle(bomb.pos[0], y, arena)) {
                        clearPath = false;
                        break;
                    }
                }

                if (clearPath && Math.abs(bomb.pos[1] - pos[1]) <= BOMB_RADIUS) {
                    return true;
                }
            }

            if (bomb.pos[1] == pos[1]) { // Одинаковая Y координата - горизонтальная линия
                int minX = Math.min(bomb.pos[0], pos[0]);
                int maxX = Math.max(bomb.pos[0], pos[0]);
                boolean clearPath = true;

                // Проверяем клетки между бомбой и позицией
                for (int x = minX + 1; x < maxX; x++) {
                    if (isWall(x, bomb.pos[1], arena) || isObstacle(x, bomb.pos[1], arena)) {
                        clearPath = false;
                        break;
                    }
                }

                if (clearPath && Math.abs(bomb.pos[0] - pos[0]) <= BOMB_RADIUS) {
                    return true;
                }
            }
        }
        return false;
    }

    // НОВЫЙ МЕТОД: Немедленно убегает от бомбы
    private MoveBomber escapeFromBombImmediately(Bomber bomber, ArenaResponse arena) {
        int[] currentPos = bomber.pos;
        List<List<Integer>> path = new ArrayList<>();
        path.add(Arrays.asList(currentPos[0], currentPos[1]));

        log.warn("💥 Bomber {} ON or NEXT TO BOMB! Emergency escape!", bomber.id);

        // Ищем любое направление для побега
        int[][] directions = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int[] dir : directions) {
            int newX = currentPos[0] + dir[0];
            int newY = currentPos[1] + dir[1];

            if (isValidCell(newX, newY, arena) && !isObstacle(newX, newY, arena)) {
                path.add(Arrays.asList(newX, newY));

                // Пробуем убежать на 2 клетки
                int nextX = newX + dir[0];
                int nextY = newY + dir[1];
                if (isValidCell(nextX, nextY, arena) && !isObstacle(nextX, nextY, arena)) {
                    path.add(Arrays.asList(nextX, nextY));
                }
                break;
            }
        }

        return new MoveBomber(bomber.id, path, new ArrayList<>());
    }

    // НОВЫЙ МЕТОД: Ставит бомбу и безопасно убегает
    private MoveBomber plantBombAndEscapeSafely(Bomber bomber, ArenaResponse arena) {
        int[] currentPos = bomber.pos;

        // Проверяем, есть ли безопасный путь для отступления
        List<List<Integer>> escapePath = findSafeEscapePathFromBomb(currentPos, arena);

        if (escapePath.size() <= 1) {
            // Нет безопасного пути - не ставим бомбу
            log.warn("⚠️ Bomber {}: No safe escape path, skipping bomb", bomber.id);
            lastAction.put(bomber.id, "ABORT_BOMB");
            return patrolInZone(bomber, arena);
        }

        // Ставим бомбу
        List<List<Integer>> bombs = new ArrayList<>();
        bombs.add(Arrays.asList(currentPos[0], currentPos[1]));
        bombCooldown.put(bomber.id, 6);

        // Запоминаем, что мы убегаем от бомбы
        escapeTicks.put(bomber.id, 4); // Убегаем 4 тика
        escapeFromPos.put(bomber.id, currentPos);

        // Определяем направление убегания (первый шаг пути)
        if (escapePath.size() > 1) {
            List<Integer> firstStep = escapePath.get(1);
            int dx = firstStep.get(0) - currentPos[0];
            int dy = firstStep.get(1) - currentPos[1];
            escapeDirection.put(bomber.id, new int[]{dx, dy});
        }

        log.info("💣💣💣 Bomber {} PLANTING STRATEGIC BOMB at ({},{}) - ESCAPING SAFELY!",
                bomber.id, currentPos[0], currentPos[1]);

        return new MoveBomber(bomber.id, escapePath, bombs);
    }

    // НОВЫЙ МЕТОД: Продолжает убегать от бомбы
    private MoveBomber continueBombEscape(Bomber bomber, ArenaResponse arena) {
        int[] currentPos = bomber.pos;
        List<List<Integer>> path = new ArrayList<>();
        path.add(Arrays.asList(currentPos[0], currentPos[1]));

        // Продолжаем двигаться в том же направлении
        int[] escapeDir = escapeDirection.get(bomber.id);
        if (escapeDir != null) {
            int newX = currentPos[0] + escapeDir[0];
            int newY = currentPos[1] + escapeDir[1];

            if (isValidCell(newX, newY, arena) && !isObstacle(newX, newY, arena)) {
                path.add(Arrays.asList(newX, newY));

                // Если можем, идем еще на одну клетку
                int nextX = newX + escapeDir[0];
                int nextY = newY + escapeDir[1];
                if (isValidCell(nextX, nextY, arena) && !isObstacle(nextX, nextY, arena)) {
                    path.add(Arrays.asList(nextX, nextY));
                }
            } else {
                // Если не можем идти в том же направлении, ищем новое
                return findAlternativeEscapePath(bomber, arena);
            }
        } else {
            // Если нет направления, ищем новый путь
            return findAlternativeEscapePath(bomber, arena);
        }

        return new MoveBomber(bomber.id, path, new ArrayList<>());
    }

    // НОВЫЙ МЕТОД: Находит альтернативный путь для убегания
    private MoveBomber findAlternativeEscapePath(Bomber bomber, ArenaResponse arena) {
        int[] currentPos = bomber.pos;
        List<List<Integer>> path = new ArrayList<>();
        path.add(Arrays.asList(currentPos[0], currentPos[1]));

        // Ищем любое безопасное направление
        int[][] directions = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int[] dir : directions) {
            int newX = currentPos[0] + dir[0];
            int newY = currentPos[1] + dir[1];

            if (isValidCell(newX, newY, arena) && !isObstacle(newX, newY, arena)) {
                path.add(Arrays.asList(newX, newY));
                break;
            }
        }

        return new MoveBomber(bomber.id, path, new ArrayList<>());
    }

    // НОВЫЙ МЕТОД: Находит безопасный путь для отступления от бомбы
    private List<List<Integer>> findSafeEscapePathFromBomb(int[] from, ArenaResponse arena) {
        List<List<Integer>> path = new ArrayList<>();
        path.add(Arrays.asList(from[0], from[1]));

        int[][] directions = {{1,0},{-1,0},{0,1},{0,-1}};

        // Оцениваем каждое направление по безопасности
        List<int[]> safeDirections = new ArrayList<>();

        for (int[] dir : directions) {
            boolean isSafe = true;
            List<int[]> escapeCells = new ArrayList<>();

            // Проверяем 3 клетки в этом направлении
            for (int i = 1; i <= 3; i++) {
                int checkX = from[0] + dir[0] * i;
                int checkY = from[1] + dir[1] * i;

                if (!isValidCell(checkX, checkY, arena) || isObstacle(checkX, checkY, arena)) {
                    isSafe = false;
                    break;
                }

                // Проверяем, нет ли здесь других бомб
                if (isOnBomb(new int[]{checkX, checkY}, arena)) {
                    isSafe = false;
                    break;
                }

                escapeCells.add(new int[]{checkX, checkY});
            }

            if (isSafe && !escapeCells.isEmpty()) {
                // Оцениваем безопасность направления
                int safetyScore = 0;
                for (int[] cell : escapeCells) {
                    // Бонус за расстояние от начальной точки
                    int distance = Math.abs(cell[0] - from[0]) + Math.abs(cell[1] - from[1]);
                    safetyScore += distance * 10;

                    // Бонус за отсутствие других бомб поблизости
                    if (!isNextToBomb(cell, arena)) {
                        safetyScore += 20;
                    }
                }
                safeDirections.add(new int[]{dir[0], dir[1], safetyScore});
            }
        }

        if (!safeDirections.isEmpty()) {
            // Выбираем самое безопасное направление
            safeDirections.sort((a, b) -> Integer.compare(b[2], a[2]));
            int[] bestDir = safeDirections.get(0);

            // Добавляем 3 клетки в пути
            for (int i = 1; i <= 3; i++) {
                int newX = from[0] + bestDir[0] * i;
                int newY = from[1] + bestDir[1] * i;
                path.add(Arrays.asList(newX, newY));
            }
            return path;
        }

        // Если нет полностью безопасного пути, ищем на 2 клетки
        for (int[] dir : directions) {
            int step1X = from[0] + dir[0];
            int step1Y = from[1] + dir[1];
            int step2X = step1X + dir[0];
            int step2Y = step1Y + dir[1];

            if (isValidCell(step1X, step1Y, arena) && !isObstacle(step1X, step1Y, arena) &&
                    isValidCell(step2X, step2Y, arena) && !isObstacle(step2X, step2Y, arena) &&
                    !isOnBomb(new int[]{step1X, step1Y}, arena) && !isOnBomb(new int[]{step2X, step2Y}, arena)) {
                path.add(Arrays.asList(step1X, step1Y));
                path.add(Arrays.asList(step2X, step2Y));
                return path;
            }
        }

        // Если нет на 2 клетки, ищем на 1 клетку
        for (int[] dir : directions) {
            int newX = from[0] + dir[0];
            int newY = from[1] + dir[1];

            if (isValidCell(newX, newY, arena) && !isObstacle(newX, newY, arena) &&
                    !isOnBomb(new int[]{newX, newY}, arena)) {
                path.add(Arrays.asList(newX, newY));
                return path;
            }
        }

        log.warn("⚠️ No safe escape path found from bomb!");
        return path; // Остаемся на месте
    }

    // НОВЫЙ МЕТОД: Находит бомбера по ID
    private Bomber findBomberById(String bomberId, ArenaResponse arena) {
        if (arena.bombers == null) return null;

        for (Bomber bomber : arena.bombers) {
            if (bomber.id.equals(bomberId)) {
                return bomber;
            }
        }
        return null;
    }

    // ИСПРАВЛЕННЫЙ МЕТОД: Проверяет, нужно ли ставить стратегическую бомбу
    private boolean shouldPlantStrategicBomb(Bomber bomber, ArenaResponse arena) {
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

        // Дополнительная проверка: не рядом ли с другой бомбой
        if (isNextToBomb(currentPos, arena)) {
            return false;
        }

        // ПРОВЕРЯЕМ ТОЛЬКО СТРАТЕГИЧЕСКИ ВЫГОДНЫЕ ПОЗИЦИИ:

        // 1. Может ли бомба разрушить несколько стен за раз
        if (canDestroyMultipleWalls(currentPos, arena)) {
            log.info("🎯 Bomber {}: Can destroy multiple walls!", bomber.id);
            return true;
        }

        // 2. Бомба у стены и враг в радиусе взрыва
        if (isWallWithEnemyInRange(currentPos, arena)) {
            log.info("🎯 Bomber {}: Wall with enemy in range!", bomber.id);
            return true;
        }

        // 3. Прямо рядом с врагом
        if (isDirectlyNextToEnemy(currentPos, arena)) {
            log.info("🎯 Bomber {}: Enemy right next to us!", bomber.id);
            return true;
        }

        return false;
    }

    // НОВЫЙ МЕТОД: Проверяет, может ли бомба разрушить несколько стен
    private boolean canDestroyMultipleWalls(int[] pos, ArenaResponse arena) {
        int wallCount = 0;

        // Проверяем только соседние клетки по горизонтали и вертикали
        int[][] directions = {{1,0},{-1,0},{0,1},{0,-1}};

        for (int[] dir : directions) {
            int checkX = pos[0] + dir[0];
            int checkY = pos[1] + dir[1];

            if (isWall(checkX, checkY, arena)) {
                wallCount++;
            }
        }

        // Если рядом 2 или более стен - стратегическая позиция
        return wallCount >= 2;
    }

    // ИСПРАВЛЕННЫЙ МЕТОД: Проверяет, есть ли стена и враг в радиусе взрыва
    private boolean isWallWithEnemyInRange(int[] pos, ArenaResponse arena) {
        // Проверяем, есть ли хотя бы одна стена рядом
        boolean hasWall = false;
        int[][] directions = {{1,0},{-1,0},{0,1},{0,-1}};

        for (int[] dir : directions) {
            int checkX = pos[0] + dir[0];
            int checkY = pos[1] + dir[1];

            if (isWall(checkX, checkY, arena)) {
                hasWall = true;
                break;
            }
        }

        if (!hasWall) return false;

        // Проверяем, есть ли враг в радиусе взрыва
        if (arena.enemies != null) {
            for (Enemy enemy : arena.enemies) {
                if (enemy.pos == null) continue;

                // Проверяем только по кресту (горизонталь/вертикаль)
                if (enemy.pos[0] == pos[0]) { // Одинаковая X - вертикальная линия
                    int distance = Math.abs(enemy.pos[1] - pos[1]);
                    if (distance <= BOMB_RADIUS && distance > 0) {
                        int minY = Math.min(enemy.pos[1], pos[1]);
                        int maxY = Math.max(enemy.pos[1], pos[1]);
                        boolean clearPath = true;

                        for (int y = minY + 1; y < maxY; y++) {
                            if (isWall(pos[0], y, arena) || isObstacle(pos[0], y, arena)) {
                                clearPath = false;
                                break;
                            }
                        }

                        if (clearPath) {
                            return true;
                        }
                    }
                }

                if (enemy.pos[1] == pos[1]) { // Одинаковая Y - горизонтальная линия
                    int distance = Math.abs(enemy.pos[0] - pos[0]);
                    if (distance <= BOMB_RADIUS && distance > 0) {
                        int minX = Math.min(enemy.pos[0], pos[0]);
                        int maxX = Math.max(enemy.pos[0], pos[0]);
                        boolean clearPath = true;

                        for (int x = minX + 1; x < maxX; x++) {
                            if (isWall(x, pos[1], arena) || isObstacle(x, pos[1], arena)) {
                                clearPath = false;
                                break;
                            }
                        }

                        if (clearPath) {
                            return true;
                        }
                    }
                }
            }
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

    // ИСПРАВЛЕННЫЙ МЕТОД: Находит стратегическое место для установки бомбы
    private int[] findStrategicBombPlacement(Bomber bomber, ArenaResponse arena) {
        int[] currentPos = bomber.pos;

        // 1. Ищем позиции, где можно разрушить несколько стен
        int[] multiWallSpot = findMultiWallBombSpot(bomber, arena);
        if (multiWallSpot != null) {
            return multiWallSpot;
        }

        // 2. Ищем врагов рядом со стенами
        int[] enemyNearWall = findEnemyNearWall(currentPos, arena);
        if (enemyNearWall != null) {
            return enemyNearWall;
        }

        // 3. Ищем просто стену для разрушения
        int[] wallSpot = findWallForDestruction(bomber, arena);
        if (wallSpot != null) {
            return wallSpot;
        }

        return null;
    }

    // НОВЫЙ МЕТОД: Ищет место для бомбы, которая разрушит несколько стен
    private int[] findMultiWallBombSpot(Bomber bomber, ArenaResponse arena) {
        int[] currentPos = bomber.pos;

        // Ищем клетки в радиусе обзора (5 клеток)
        for (int radius = 1; radius <= BOMBER_VISION; radius++) {
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

                        // Проверяем, сколько стен можно разрушить с этой позиции
                        int wallCount = 0;
                        int[][] directions = {{1,0},{-1,0},{0,1},{0,-1}};

                        for (int[] dir : directions) {
                            int wallX = checkX + dir[0];
                            int wallY = checkY + dir[1];

                            if (isWall(wallX, wallY, arena)) {
                                wallCount++;
                            }
                        }

                        // Если можно разрушить 2 или более стен - стратегическая позиция
                        if (wallCount >= 2) {
                            log.info("Found multi-wall bomb spot at ({},{}) with {} walls",
                                    checkX, checkY, wallCount);
                            return new int[]{checkX, checkY};
                        }
                    }
                }
            }
        }

        return null;
    }

    // НОВЫЙ МЕТОД: Ищет просто стену для разрушения
    private int[] findWallForDestruction(Bomber bomber, ArenaResponse arena) {
        int[] currentPos = bomber.pos;

        // Ищем ближайшую стену в радиусе обзора
        int[] nearestWall = null;
        int minDistance = Integer.MAX_VALUE;

        if (arena.arena != null && arena.arena.obstacles != null) {
            for (List<Integer> wall : arena.arena.obstacles) {
                if (wall.size() < 2) continue;

                int wallX = wall.get(0);
                int wallY = wall.get(1);

                int distance = Math.abs(wallX - currentPos[0]) + Math.abs(wallY - currentPos[1]);

                // Проверяем только стены в радиусе обзора
                if (distance <= BOMBER_VISION && distance < minDistance) {
                    // Проверяем, есть ли путь к стене
                    if (hasPathToWall(currentPos, new int[]{wallX, wallY}, arena)) {
                        minDistance = distance;
                        nearestWall = new int[]{wallX, wallY};
                    }
                }
            }
        }

        return nearestWall;
    }

    // ИСПРАВЛЕННЫЙ МЕТОД: Находит врага рядом со стеной
    private int[] findEnemyNearWall(int[] from, ArenaResponse arena) {
        if (arena.enemies == null) return null;

        for (Enemy enemy : arena.enemies) {
            if (enemy.pos == null) continue;

            int distance = Math.abs(enemy.pos[0] - from[0]) + Math.abs(enemy.pos[1] - from[1]);

            // Проверяем только врагов в радиусе ОБЗОРА
            if (distance > BOMBER_VISION) continue;

            // Проверяем, есть ли стены рядом с врагом
            if (isNextToAnyWall(enemy.pos, arena)) {
                // Ищем позицию для бомбы, которая достанет врага и стену
                int[][] bombPositions = {
                        {enemy.pos[0] + 1, enemy.pos[1]},
                        {enemy.pos[0] - 1, enemy.pos[1]},
                        {enemy.pos[0], enemy.pos[1] + 1},
                        {enemy.pos[0], enemy.pos[1] - 1}
                };

                for (int[] bombPos : bombPositions) {
                    if (isValidCell(bombPos[0], bombPos[1], arena) &&
                            !isObstacle(bombPos[0], bombPos[1], arena) &&
                            !isOnBomb(new int[]{bombPos[0], bombPos[1]}, arena)) {

                        // Проверяем, что бомба достанет и врага и стену
                        boolean hitsEnemy = false;
                        boolean hitsWall = false;

                        // Проверяем соседние клетки по кресту от позиции бомбы
                        int[][] directions = {{1,0},{-1,0},{0,1},{0,-1}};
                        for (int[] dir : directions) {
                            int checkX = bombPos[0] + dir[0];
                            int checkY = bombPos[1] + dir[1];

                            if (!isValidCell(checkX, checkY, arena)) continue;

                            // Проверяем, есть ли враг в соседней клетке
                            if (checkX == enemy.pos[0] && checkY == enemy.pos[1]) {
                                hitsEnemy = true;
                            }

                            // Проверяем, есть ли стена в соседней клетке
                            if (isWall(checkX, checkY, arena)) {
                                hitsWall = true;
                            }
                        }

                        if (hitsEnemy && hitsWall) {
                            return bombPos;
                        }
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

    // НОВЫЙ МЕТОД: Патрулирует и ищет стратегические места для мин
    private MoveBomber patrolAndSearchForBombSpots(Bomber bomber, ArenaResponse arena) {
        int[] currentPos = bomber.pos;

        // Сначала проверяем, нельзя ли прямо здесь поставить стратегическую бомбу
        if (shouldPlantStrategicBomb(bomber, arena)) {
            lastAction.put(bomber.id, "STRATEGIC_BOMB");
            lastBombTick.put(bomber.id, tickCounter);
            return plantBombAndEscapeSafely(bomber, arena);
        }

        // Ищем стратегическую позицию для бомбы
        int[] strategicSpot = findStrategicBombPlacement(bomber, arena);
        if (strategicSpot != null) {
            log.debug("Bomber {} patrolling to strategic spot at ({},{})",
                    bomber.id, strategicSpot[0], strategicSpot[1]);
            return moveToTarget(bomber, strategicSpot, arena);
        }

        // Если стратегических позиций нет, ищем просто стену
        int[] wallSpot = findWallForDestruction(bomber, arena);
        if (wallSpot != null) {
            log.debug("Bomber {} patrolling to wall at ({},{})",
                    bomber.id, wallSpot[0], wallSpot[1]);
            return moveToTarget(bomber, wallSpot, arena);
        }

        // Если стен нет, используем обычное патрулирование
        return patrolInZone(bomber, arena);
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

    // ИСПРАВЛЕННЫЙ МЕТОД: Ищет цель для бомбера в радиусе ОБЗОРА (5 клеток)
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

        // 1. Ищем врагов в своей зоне в радиусе ОБЗОРА
        if (arena.enemies != null) {
            for (Enemy enemy : arena.enemies) {
                if (enemy.pos == null) continue;

                // Проверяем, в радиусе ли обзора
                int dist = Math.abs(enemy.pos[0] - currentPos[0]) +
                        Math.abs(enemy.pos[1] - currentPos[1]);

                if (dist > BOMBER_VISION) continue; // Враг вне радиуса обзора

                if (enemy.pos[0] >= zoneStartX && enemy.pos[0] < zoneEndX &&
                        enemy.pos[1] >= zoneStartY && enemy.pos[1] < zoneEndY) {

                    int score = 150 - dist * 5;
                    score += 50; // Бонус за врага в своей зоне

                    if (score > bestScore) {
                        bestScore = score;
                        bestTarget = enemy.pos;
                    }
                }
            }
        }

        // 2. Если в своей зоне нет врагов, ищем стены для разрушения
        if (bestTarget == null) {
            bestTarget = findBestWallTargetInVision(bomber, arena);
        }

        // 3. Если нашли цель, проверяем, не преследует ли ее другой бомбер
        if (bestTarget != null && isTargetBeingPursued(bestTarget, bomber.id, arena)) {
            log.debug("Target at ({},{}) already pursued, finding alternative",
                    bestTarget[0], bestTarget[1]);

            return findAlternativeTargetInVision(bomber, arena, bestTarget);
        }

        return bestTarget;
    }

    // НОВЫЙ МЕТОД: Ищет лучшую стену для разрушения в радиусе обзора
    private int[] findBestWallTargetInVision(Bomber bomber, ArenaResponse arena) {
        int[] currentPos = bomber.pos;
        int[] bestTarget = null;
        int bestScore = -1;

        if (arena.arena != null && arena.arena.obstacles != null) {
            for (List<Integer> wall : arena.arena.obstacles) {
                if (wall.size() < 2) continue;

                int wallX = wall.get(0);
                int wallY = wall.get(1);

                int dist = Math.abs(wallX - currentPos[0]) + Math.abs(wallY - currentPos[1]);

                // Проверяем только стены в радиусе обзора
                if (dist <= BOMBER_VISION) {
                    int score = 100 - dist * 10;

                    // Бонус за стены, рядом с которыми могут быть враги
                    if (isEnemyNearWall(new int[]{wallX, wallY}, arena)) {
                        score += 50;
                    }

                    if (score > bestScore) {
                        bestScore = score;
                        bestTarget = new int[]{wallX, wallY};
                    }
                }
            }
        }

        if (bestTarget != null) {
            log.debug("Found wall target in vision at ({},{}) with score {}",
                    bestTarget[0], bestTarget[1], bestScore);
        }

        return bestTarget;
    }

    // НОВЫЙ МЕТОД: Проверяет, есть ли враг рядом со стеной
    private boolean isEnemyNearWall(int[] wallPos, ArenaResponse arena) {
        if (arena.enemies == null) return false;

        for (Enemy enemy : arena.enemies) {
            if (enemy.pos == null) continue;

            int dist = Math.abs(enemy.pos[0] - wallPos[0]) + Math.abs(enemy.pos[1] - wallPos[1]);
            if (dist <= 2) { // Враг в 2 клетках от стены
                return true;
            }
        }

        return false;
    }

    // НОВЫЙ МЕТОД: Ищет альтернативную цель в радиусе обзора
    private int[] findAlternativeTargetInVision(Bomber bomber, ArenaResponse arena, int[] avoidTarget) {
        int[] currentPos = bomber.pos;
        int[] bestTarget = null;
        int bestScore = -1;

        int preferredDir = preferredDirection.get(bomber.id);
        int[][] directionOffsets = {{1,0},{-1,0},{0,1},{0,-1}};
        int[] mainDir = directionOffsets[preferredDir % 4];

        if (arena.enemies != null) {
            for (Enemy enemy : arena.enemies) {
                if (enemy.pos == null) continue;

                // Пропускаем целевую цель
                if (avoidTarget != null &&
                        enemy.pos[0] == avoidTarget[0] && enemy.pos[1] == avoidTarget[1]) {
                    continue;
                }

                int dist = Math.abs(enemy.pos[0] - currentPos[0]) +
                        Math.abs(enemy.pos[1] - currentPos[1]);

                // Проверяем только врагов в радиусе обзора
                if (dist <= BOMBER_VISION) {
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

            // Для радиуса 1 нельзя достать врага через 2 клетки
            if (dist == 2 && (enemy.pos[0] == pos[0] || enemy.pos[1] == pos[1])) {
                // Проверяем, что между нами нет стены и нет препятствий
                if (isClearPathForBomb(pos, enemy.pos, arena)) {
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

            // Для радиуса 1 стена через 1 клетку по прямой тоже считается
            int checkX2 = pos[0] + dir[0] * 2;
            int checkY2 = pos[1] + dir[1] * 2;
            if (isWall(checkX2, checkY2, arena) && !isObstacle(checkX, checkY, arena)) {
                log.info("✅ Wall 2 cells away at ({},{})", checkX2, checkY2);
                return true;
            }
        }

        return false;
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
            log.info("🔥 Bomber {} (group {}): PLANTED STRATEGIC BOMB and moving {} cells (Escape ticks: {})",
                    bomber.id, bomberGroup.get(bomber.id),
                    command.getPath().size() - 1,
                    escapeTicks.getOrDefault(bomber.id, 0));
        } else {
            String action = lastAction.getOrDefault(bomber.id, "UNKNOWN");
            if (action.equals("ESCAPE") || action.equals("DANGER_ESCAPE") || action.equals("ESCAPE_BOMB")) {
                log.info("🏃‍♂️ Bomber {} (group {}): ESCAPING {} cells (action: {})",
                        bomber.id, bomberGroup.get(bomber.id),
                        command.getPath().size() - 1, action);
            } else {
                log.debug("Bomber {} (group {}): Moving {} cells (action: {})",
                        bomber.id, bomberGroup.get(bomber.id),
                        command.getPath().size() - 1, action);
            }
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
        lastBombTick.keySet().removeIf(id -> !aliveBomberIds.contains(id));
        escapeTicks.keySet().removeIf(id -> !aliveBomberIds.contains(id));
        escapeFromPos.keySet().removeIf(id -> !aliveBomberIds.contains(id));
        escapeDirection.keySet().removeIf(id -> !aliveBomberIds.contains(id));
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

    // Проверяет путь для бомбы (можно через 1 клетку, только по кресту)
    private boolean isClearPathForBomb(int[] from, int[] to, ArenaResponse arena) {
        // Проверяем, что точки на одной линии (вертикальной или горизонтальной)
        if (from[0] != to[0] && from[1] != to[1]) {
            return false; // Не на одной линии - бомба не достанет
        }

        int dist = Math.abs(to[0] - from[0]) + Math.abs(to[1] - from[1]);

        // Для радиуса 1 можно достать только соседние клетки
        if (dist != 1) return false;

        // Для соседних клеток проверяем только препятствия
        if (isObstacle(to[0], to[1], arena)) {
            return false;
        }

        return true;
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

    // Проверяет линию между точками (грубая проверка)
    private boolean isClearPathForMovement(int[] from, int[] to, ArenaResponse arena) {
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

    // НОВЫЙ МЕТОД: Проверяет, рядом ли любая стена
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
}