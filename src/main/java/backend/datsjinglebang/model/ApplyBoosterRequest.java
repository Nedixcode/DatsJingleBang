package backend.datsjinglebang.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ApplyBoosterRequest {
    @JsonProperty("booster")
    private String booster;

    // Getters and Setters
    public String getBooster() {
        return booster;
    }

    public void setBooster(String booster) {
        this.booster = booster;
    }
}
