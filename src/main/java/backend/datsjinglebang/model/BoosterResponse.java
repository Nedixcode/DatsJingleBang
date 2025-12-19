package backend.datsjinglebang.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

public class BoosterResponse {
    @JsonProperty("state")
    private PlayerState state;

    @JsonProperty("available")
    private List<AvailableBooster> available;

    // Getters and Setters
    public PlayerState getState() {
        return state;
    }

    public void setState(PlayerState state) {
        this.state = state;
    }

    public List<AvailableBooster> getAvailable() {
        return available;
    }

    public void setAvailable(List<AvailableBooster> available) {
        this.available = available;
    }

    public static class PlayerState {
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

        @Setter
        @Getter
        @JsonProperty("points")
        private int points;

        @JsonProperty("speed")
        private int speed;

        @JsonProperty("view")
        private int view;

        // Getters and Setters...
    }

    public static class AvailableBooster {
        @JsonProperty("type")
        private String type;

        @JsonProperty("cost")
        private int cost;

        // Getters and Setters...
    }
}