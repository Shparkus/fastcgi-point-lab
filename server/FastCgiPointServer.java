import com.fastcgi.FCGIInterface;
import com.fastcgi.FCGIRequest;

import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * FastCGI сервер на базе fastcgi-lib.jar.
 * - Цикл приема: FCGIInterface.FCGIaccept()
 * - Чтение тела: System.in по CONTENT_LENGTH
 * - Вывод: System.out (писать HTTP-заголовки + тело)
 * - Переменные запроса: FCGIInterface.request.params (Properties)
 *
 * Возвращает JSON с текущим результатом и историей по IP клиента.
 *
 * Запуск как внешнего FastCGI процесса:
 *   java -cp .:fastcgi-lib.jar FastCgiPointServer
 * (порт, сокеты и проксирование — на стороне Apache через mod_fastcgi)
 */
public class FastCgiPointServer {

    public static void main(String[] args) throws Exception {
        // Инициализация FastCGI
        FCGIInterface fcgi = new FCGIInterface();

        // Главный цикл — каждый FCGIaccept() = 1 HTTP-запрос от Apache
        while (fcgi.FCGIaccept() >= 0) {
            long t0 = System.nanoTime();

            Properties env = FCGIInterface.request.params; // REQUEST_METHOD, CONTENT_LENGTH, QUERY_STRING и т.п.
            String method      = env.getProperty("REQUEST_METHOD", "GET");
            String contentType = env.getProperty("CONTENT_TYPE", "");
            String remoteAddr  = env.getProperty("REMOTE_ADDR", "0.0.0.0");
            String queryString = env.getProperty("QUERY_STRING", "");
            int contentLength  = parseIntSafe(env.getProperty("CONTENT_LENGTH", "0"));

            // Получаем тело запроса (POST)
            String body = "";
            if ("POST".equalsIgnoreCase(method) && contentLength > 0) {
                body = readBody(System.in, contentLength);
            }

            // Собираем форму: поддерживаем application/x-www-form-urlencoded и application/json
            // (простенький парсинг json "url-like", как в прошлой версии)
            java.util.Map<String, String> form = new java.util.HashMap<>();
            if ("POST".equalsIgnoreCase(method)) {
                if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                    form = parseJsonUrlLike(body);
                } else {
                    form = parseQueryString(body);
                }
            } else {
                form = parseQueryString(queryString);
            }

            List<String> errors = new ArrayList<>();
            Double x = parseDouble(form.get("x"), "x", errors);
            Double y = parseDouble(form.get("y"), "y", errors);
            Double r = parseDouble(form.get("r"), "r", errors);
            if (r != null && r <= 0) errors.add("r должен быть положительным");
            if (x != null && (x < -5 || x > 5)) errors.add("x должен быть в диапазоне [-5, 5]");
            if (y != null && (y < -5 || y > 5)) errors.add("y должен быть в диапазоне [-5, 5]");
            if (r != null && r > 5) errors.add("r не должен быть больше 5");

            Instant now = Instant.now();

            if (!errors.isEmpty() || x == null || y == null || r == null) {
                String json = "{\"ok\":false,\"errors\":" + toJsonArray(errors) + ",\"time\":\"" + now + "\"}";
                writeHttpJson(json);
                continue;
            }

            boolean hit = hitTest(x, y, r);
            long dtMicros = (System.nanoTime() - t0) / 1_000;

            ResultEntry entry = new ResultEntry(x, y, r, hit, now, dtMicros);
            Store.add(remoteAddr, entry);
            List<ResultEntry> history = Store.list(remoteAddr);

            StringBuilder sb = new StringBuilder(512);
            sb.append("{\"ok\":true,");
            sb.append("\"current\":").append(entryToJson(entry)).append(',');
            sb.append("\"history\":[");
            for (int i = 0; i < history.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(entryToJson(history.get(i)));
            }
            sb.append("]}");

            writeHttpJson(sb.toString());
        }
    }

    // ---- Геометрия области (замените под свой вариант, если отличается) ----
    private static boolean hitTest(double x, double y, double r) {
        // II квадрант: четверть круга
        if (x <= 0 && y >= 0 && (x * x + y * y <= r * r)) return true;
        // I квадрант: прямоугольник r × r/2
        if (x >= 0 && y >= 0 && x <= r && y <= r / 2.0) return true;
        // IV квадрант: треугольник y >= x - r, при x>=0, y<=0
        if (x >= 0 && y <= 0 && y >= x - r) return true;
        return false;
    }

    // ---- Вспомогательные функции ----

    private static String readBody(InputStream in, int len) throws IOException {
        byte[] buf = in.readNBytes(len);
        return new String(buf, StandardCharsets.UTF_8);
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private static Double parseDouble(String s, String name, List<String> errors) {
        if (s == null || s.isBlank()) { errors.add(name + " отсутствует"); return null; }
        try {
            return Double.valueOf(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            errors.add(name + " не является числом");
            return null;
        }
    }

    private static java.util.Map<String, String> parseQueryString(String qs) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        if (qs == null || qs.isEmpty()) return m;
        for (String part : qs.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String k = urlDecode(part.substring(0, eq));
            String v = urlDecode(part.substring(eq + 1));
            m.put(k, v);
        }
        return m;
    }

    // Минималистичный парсер json "url-like" — для {"x":"1","y":"2","r":"3"}
    private static java.util.Map<String, String> parseJsonUrlLike(String json) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        String s = json.trim();
        if (s.startsWith("{") && s.endsWith("}")) {
            s = s.substring(1, s.length() - 1);
            for (String pair : s.split(",")) {
                String[] kv = pair.split(":", 2);
                if (kv.length == 2) {
                    String k = kv[0].trim().replaceAll("^\"|\"$", "");
                    String v = kv[1].trim().replaceAll("^\"|\"$", "");
                    m.put(k, v);
                }
            }
        }
        return m;
    }

    private static String urlDecode(String s) {
        try { return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }

    private static String toJsonArray(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escape(list.get(i))).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String entryToJson(ResultEntry e) {
        return new StringBuilder()
                .append("{\"x\":").append(trim(e.x))
                .append(",\"y\":").append(trim(e.y))
                .append(",\"r\":").append(trim(e.r))
                .append(",\"hit\":").append(e.hit)
                .append(",\"serverTime\":\"").append(e.serverTime).append('"')
                .append(",\"execMicros\":").append(e.execMicros)
                .append('}')
                .toString();
    }

    private static String trim(double v) {
        String s = String.format(Locale.US, "%.6f", v);
        return s.indexOf('.') >= 0 ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void writeHttpJson(String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        // В FastCGI ответе нужен "CGI-совместимый" заголовок + пустая строка, затем тело
        System.out.print("Status: 200 OK\r\n");
        System.out.print("Content-Type: application/json; charset=utf-8\r\n");
        System.out.print("Content-Length: " + body.length + "\r\n");
        System.out.print("Cache-Control: no-store\r\n\r\n");
        System.out.write(body);
        System.out.flush();
    }
}
