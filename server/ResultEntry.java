import java.time.Instant;

public class ResultEntry {
    public final double x;
    public final double y;
    public final double r;
    public final boolean hit;
    public final Instant serverTime;
    public final long execMicros;

    public ResultEntry(double x, double y, double r, boolean hit, Instant serverTime, long execMicros) {
        this.x = x;
        this.y = y;
        this.r = r;
        this.hit = hit;
        this.serverTime = serverTime;
        this.execMicros = execMicros;
    }
}
