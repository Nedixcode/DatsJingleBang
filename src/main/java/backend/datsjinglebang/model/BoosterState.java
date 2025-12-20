package backend.datsjinglebang.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class BoosterState {
    @JsonProperty("armor")
    private int armor;

    @JsonProperty("bomb_delay")
    private int bombDelay;

    @JsonProperty("bomb_range")
    private int bombRange;

    @JsonProperty("bombers")
    private int bombers;

    @JsonProperty("bombs")
    private int bombs;

    @JsonProperty("can_pass_bombs")
    private boolean canPassBombs;

    @JsonProperty("can_pass_obstacles")
    private boolean canPassObstacles;

    @JsonProperty("can_pass_walls")
    private boolean canPassWalls;

    @JsonProperty("points")
    private int points;

    @JsonProperty("speed")
    private int speed;

    @JsonProperty("view")
    private int view;

    public BoosterState() {
    }

    // Геттеры и сеттеры
    public int getArmor() {
        return armor;
    }

    public void setArmor(int armor) {
        this.armor = armor;
    }

    public int getBombDelay() {
        return bombDelay;
    }

    public void setBombDelay(int bombDelay) {
        this.bombDelay = bombDelay;
    }

    public int getBombRange() {
        return bombRange;
    }

    public void setBombRange(int bombRange) {
        this.bombRange = bombRange;
    }

    public int getBombers() {
        return bombers;
    }

    public void setBombers(int bombers) {
        this.bombers = bombers;
    }

    public int getBombs() {
        return bombs;
    }

    public void setBombs(int bombs) {
        this.bombs = bombs;
    }

    public boolean isCanPassBombs() {
        return canPassBombs;
    }

    public void setCanPassBombs(boolean canPassBombs) {
        this.canPassBombs = canPassBombs;
    }

    public boolean isCanPassObstacles() {
        return canPassObstacles;
    }

    public void setCanPassObstacles(boolean canPassObstacles) {
        this.canPassObstacles = canPassObstacles;
    }

    public boolean isCanPassWalls() {
        return canPassWalls;
    }

    public void setCanPassWalls(boolean canPassWalls) {
        this.canPassWalls = canPassWalls;
    }

    public int getPoints() {
        return points;
    }

    public void setPoints(int points) {
        this.points = points;
    }

    public int getSpeed() {
        return speed;
    }

    public void setSpeed(int speed) {
        this.speed = speed;
    }

    public int getView() {
        return view;
    }

    public void setView(int view) {
        this.view = view;
    }
}