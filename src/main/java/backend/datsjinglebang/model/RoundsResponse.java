package backend.datsjinglebang.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class RoundsResponse {
    @JsonProperty("eventId")
    private String eventId;

    @JsonProperty("now")
    private String now;

    @JsonProperty("rounds")
    private List<RoundInfo> rounds;

    // Getters and Setters...

    public static class RoundInfo {
        @JsonProperty("name")
        private String name;

        @JsonProperty("duration")
        private int duration;

        @JsonProperty("startAt")
        private String startAt;

        @JsonProperty("endAt")
        private String endAt;

        @JsonProperty("status")
        private String status;

        @JsonProperty("repeat")
        private int repeat;

        // Getters and Setters...
    }
}