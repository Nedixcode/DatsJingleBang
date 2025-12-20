package backend.datsjinglebang.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Booster {
    @JsonProperty("cost")
    private int cost;

    @JsonProperty("type")
    private String type;

    public Booster() {
    }

    public Booster(int cost, String type) {
        this.cost = cost;
        this.type = type;
    }

    public int getCost() {
        return cost;
    }

    public void setCost(int cost) {
        this.cost = cost;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}