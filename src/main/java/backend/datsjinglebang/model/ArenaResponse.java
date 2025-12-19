package backend.datsjinglebang.model;

import java.util.List;

public class ArenaResponse {
    public Arena arena;
    public List<Bomber> bombers;
    public List<Enemy> enemies;
    public List<Mob> mobs;
    public List<String> errors;
    public int raw_score;
    public String player;
    public String round;
    public int code;
    public int[] map_size;
}

