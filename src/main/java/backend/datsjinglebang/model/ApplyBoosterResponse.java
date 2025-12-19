package backend.datsjinglebang.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ApplyBoosterResponse {
    @JsonProperty("booster")
    private int booster;

    // Getters and Setters
    public int getBooster() {
        return booster;
    }

    public void setBooster(int booster) {
        this.booster = booster;
    }
}