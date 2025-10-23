package ru.web;

import com.fastcgi.FCGIInterface;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;


public final class Main {

    // История в памяти процесса (пока он жив). Для ЛР этого достаточно.
    private static final Deque<Map<String, Object>> HISTORY = new ArrayDeque<>();
    // Ограничим, чтобы не разрасталось без меры:
    private static final int HISTORY_LIMIT = 128;

    private Main() {}

    public static void main(String[] args) {
        // Инициализация FastCGI петли
        new FCGIInterface(); // важно создать объект, чтобы библиотека перехватила stdin/stdout/env

        while (FCGIInterface.FCGIaccept() >= 0) {
            long t0 = System.nanoTime();

            try {
                // Вытаскиваем CGI-переменные окружения, которые прокинет веб-сервер
                String method = getenv("REQUEST_METHOD", "");
                String contentType = getenv("CONTENT_TYPE", "");
                String path = getenv("REQUEST_URI", "");
                String queryString = getenv("QUERY_STRING", "");
                int contentLength = parseIntSafe(getenv("CONTENT_LENGTH", "0"), 0);

                // Нас интересует ровно один endpoint: /calculate (POST)
                if (!"POST".equalsIgnoreCase(method)) {
                    writeJson(405, jsonError("Only POST is allowed for this endpoint"));
                    continue;
                }

                // Читаем тело запроса целиком
                byte[] body = readBody(System.in, contentLength);
                String form = new String(body, StandardCharsets.UTF_8);

                // Разбираем application/x-www-form-urlencoded (если прислали JSON — можно дополнить при желании)
                Map<String, String> params = parseFormUrlEncoded(form);

                // Валидация + разбор
                int x = parseX(params.get("x"));
                float y = parseY(params.get("y"));
                float r = parseR(params.get("r"));

                // Подсчёт
                boolean hit = calculate(x, y, r);

                long elapsedMs = Math.max(0, (System.nanoTime() - t0) / 1_000_000L);

                // Формируем запись результата
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("x", x);
                point.put("y", y);
                point.put("r", r);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("now", isoNowUtc());
                result.put("elapsedMs", elapsedMs);
                result.put("hit", hit);
                result.put("point", point);

                // Добавляем в историю
                synchronized (HISTORY) {
                    HISTORY.addFirst(result);
                    while (HISTORY.size() > HISTORY_LIMIT) {
                        HISTORY.removeLast();
                    }
                }

                // Кладём историю в ответ
                List<Map<String, Object>> historySnapshot;
                synchronized (HISTORY) {
                    historySnapshot = new ArrayList<>(HISTORY);
                }
                result.put("history", historySnapshot);

                // Отдаём JSON 200
                writeJson(200, toJson(result));

            } catch (ValidationException ve) {
                writeJson(422, jsonError(ve.getMessage()));
            } catch (Throwable t) {
                // Любая непредвиденная ошибка — 500
                writeJson(500, jsonError("Internal Server Error: " + t.getClass().getSimpleName() + ": " + safeMsg(t.getMessage())));
            }
        }
    }

    // === Геометрия области ===

    // Обновлённая функция расчёта из нашего обсуждения:
    // 1) Прямоугольник в Q2: x ∈ [-R/2, 0], y ∈ [0, R]
    // 2) Четверть круга в Q3: x ≤ 0, y ≤ 0, x^2 + y^2 ≤ R^2
    // 3) Треугольник в Q4: 0 ≤ x ≤ R, y ≤ 0, y ≥ x/2 - R/2
    private static boolean calculate(int x, float y, float r) {
        boolean rectQ2 = (x <= 0) && (y >= 0) && (x >= -r / 2.0f) && (y <= r);
        boolean circleQ3 = (x <= 0) && (y <= 0) && ((x * x + y * y) <= r * r);
        boolean triQ4 = (x >= 0) && (x <= r) && (y <= 0) && (y >= (x / 2.0f - r / 2.0f));
        return rectQ2 || circleQ3 || triQ4;
    }

    // === Валидация входных параметров ===

    private static int parseX(String raw) throws ValidationException {
        if (raw == null) throw new ValidationException("Parameter 'x' is required");
        int x;
        try {
            x = Integer.parseInt(raw.trim());
        } catch (NumberFormatException nfe) {
            throw new ValidationException("Parameter 'x' must be an integer");
        }
        // Разрешённые значения: -5..3 (как в UI радиокнопки)
        if (x < -5 || x > 3) {
            throw new ValidationException("Parameter 'x' must be in {-5,-4,-3,-2,-1,0,1,2,3}");
        }
        return x;
    }

    private static float parseY(String raw) throws ValidationException {
        if (raw == null) throw new ValidationException("Parameter 'y' is required");
        float y;
        try {
            y = Float.parseFloat(raw.replace(',', '.').trim());
        } catch (NumberFormatException nfe) {
            throw new ValidationException("Parameter 'y' must be a real number");
        }
        if (y < -5.0f || y > 3.0f) {
            throw new ValidationException("Parameter 'y' must be in range [-5, 3]");
        }
        return y;
    }

    private static float parseR(String raw) throws ValidationException {
        if (raw == null) throw new ValidationException("Parameter 'r' is required");
        float r;
        try {
            r = Float.parseFloat(raw.replace(',', '.').trim());
        } catch (NumberFormatException nfe) {
            throw new ValidationException("Parameter 'r' must be a real number");
        }
        // Разрешённые значения из UI: 1, 1.5, 2, 2.5, 3
        final float[] allowed = {1f, 1.5f, 2f, 2.5f, 3f};
        boolean ok = false;
        for (float v : allowed) {
            if (Math.abs(r - v) < 1e-6) {
                ok = true;
                break;
            }
        }
        if (!ok) {
            throw new ValidationException("Parameter 'r' must be one of {1, 1.5, 2, 2.5, 3}");
        }
        return r;
    }

    // === Утилиты CGI/FastCGI ===

    private static String getenv(String name, String def) {
        String v = System.getenv(name);
        return v != null ? v : def;
    }

    private static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static byte[] readBody(InputStream in, int len) throws IOException, ValidationException {
        if (len < 0) throw new ValidationException("Invalid CONTENT_LENGTH");
        byte[] buf = new byte[len];
        int off = 0;
        BufferedInputStream bin = new BufferedInputStream(in);
        while (off < len) {
            int r = bin.read(buf, off, len - off);
            if (r < 0) break;
            off += r;
        }
        if (off != len) {
            throw new ValidationException("Unexpected end of request body");
        }
        return buf;
    }

    private static Map<String, String> parseFormUrlEncoded(String form) {
        Map<String, String> map = new LinkedHashMap<>();
        if (form == null || form.isEmpty()) return map;
        String[] pairs = form.split("&");
        for (String p : pairs) {
            int eq = p.indexOf('=');
            if (eq < 0) {
                String k = urlDecode(p);
                map.put(k, "");
            } else {
                String k = urlDecode(p.substring(0, eq));
                String v = urlDecode(p.substring(eq + 1));
                map.put(k, v);
            }
        }
        return map;
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }

    private static String isoNowUtc() {
        return ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static String safeMsg(String s) {
        return s == null ? "" : s;
    }

    // === JSON без внешних зависимостей ===

    private static void writeJson(int statusCode, String json) {
        PrintStream out = System.out;
        String statusLine = switch (statusCode) {
            case 200 -> "200 OK";
            case 405 -> "405 Method Not Allowed";
            case 415 -> "415 Unsupported Media Type";
            case 422 -> "422 Unprocessable Entity";
            default -> "500 Internal Server Error";
        };

        out.print("Status: " + statusLine + "\r\n");
        out.print("Content-Type: application/json; charset=UTF-8\r\n");
        out.print("Cache-Control: no-store\r\n");
        out.print("\r\n");
        out.print(json);
        out.flush();
    }

    private static String jsonError(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("now", isoNowUtc());
        m.put("error", msg);
        return toJson(m);
    }

    // Простейший JSON-сериализатор для Map/List/строк/чисел/boolean/null
    @SuppressWarnings("unchecked")
    private static String toJson(Object v) {
        if (v == null) return "null";
        if (v instanceof String s) return quote(s);
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        if (v instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append(quote(String.valueOf(e.getKey())));
                sb.append(":");
                sb.append(toJson(e.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        if (v instanceof Iterable<?> it) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            boolean first = true;
            for (Object o : it) {
                if (!first) sb.append(",");
                first = false;
                sb.append(toJson(o));
            }
            sb.append("]");
            return sb.toString();
        }
        // fallback
        return quote(String.valueOf(v));
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder();
        sb.append("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append("\"");
        return sb.toString();
    }
}
