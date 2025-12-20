package backend.datsjinglebang.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class PurchaseBoosterRequest {
    @JsonProperty("booster")
    private String booster;

    public PurchaseBoosterRequest() {
    }

    public PurchaseBoosterRequest(String booster) {
        this.booster = booster;
    }

    public String getBooster() {
        return booster;
    }

    public void setBooster(String booster) {
        this.booster = booster;
    }
}