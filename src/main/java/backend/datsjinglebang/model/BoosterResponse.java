package backend.datsjinglebang.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class BoosterResponse {
    @JsonProperty("available")
    private List<Booster> available;

    @JsonProperty("state")
    private BoosterState state;

    public BoosterResponse() {
    }

    public BoosterResponse(List<Booster> available, BoosterState state) {
        this.available = available;
        this.state = state;
    }

    public List<Booster> getAvailable() {
        return available;
    }

    public void setAvailable(List<Booster> available) {
        this.available = available;
    }

    public BoosterState getState() {
        return state;
    }

    public void setState(BoosterState state) {
        this.state = state;
    }
}