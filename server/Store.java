import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Хранит до 50 последних результатов на IP клиента. */
public class Store {
    private static final int MAX_PER_CLIENT = 50;
    private static final Map<String, Deque<ResultEntry>> perClient = new ConcurrentHashMap<>();

    public static void add(String clientIp, ResultEntry entry) {
        perClient.computeIfAbsent(clientIp, k -> new ArrayDeque<>()).addFirst(entry);
        Deque<ResultEntry> dq = perClient.get(clientIp);
        while (dq.size() > MAX_PER_CLIENT) dq.removeLast();
    }

    public static List<ResultEntry> list(String clientIp) {
        Deque<ResultEntry> dq = perClient.get(clientIp);
        if (dq == null) return List.of();
        return new ArrayList<>(dq);
    }
}
