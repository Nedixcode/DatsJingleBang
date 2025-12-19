package backend.datsjinglebang.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class MoveBomber {
    @JsonProperty("id")
    private String id;

    @JsonProperty("path")
    private List<List<Integer>> path;

    @JsonProperty("bombs")
    private List<List<Integer>> bombs;

    public MoveBomber() {}

    public MoveBomber(String id, List<List<Integer>> path, List<List<Integer>> bombs) {
        this.id = id;
        this.path = path;
        this.bombs = bombs;
    }

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public List<List<Integer>> getPath() {
        return path;
    }

    public void setPath(List<List<Integer>> path) {
        this.path = path;
    }

    public List<List<Integer>> getBombs() {
        return bombs;
    }

    public void setBombs(List<List<Integer>> bombs) {
        this.bombs = bombs;
    }
}