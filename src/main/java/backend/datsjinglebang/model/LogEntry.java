package backend.datsjinglebang.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class LogEntry {
    @JsonProperty("message")
    private String message;

    @JsonProperty("time")
    private String time;

    // Getters and Setters
    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }
}