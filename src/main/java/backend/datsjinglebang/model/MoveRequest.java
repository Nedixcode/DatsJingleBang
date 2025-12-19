package backend.datsjinglebang.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class MoveRequest {
    @JsonProperty("bombers")
    private List<MoveBomber> bombers;

    public MoveRequest() {}

    public MoveRequest(List<MoveBomber> bombers) {
        this.bombers = bombers;
    }

    // Getters and Setters
    public List<MoveBomber> getBombers() {
        return bombers;
    }

    public void setBombers(List<MoveBomber> bombers) {
        this.bombers = bombers;
    }
}