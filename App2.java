import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class App2 {
    private static final List<Todo> todos = new ArrayList<>();
    private static int nextId = 1;

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", App2::handle);
        server.start();
        System.out.println("サーバー起動: http://localhost:8080 (終了するには Ctrl+C)");
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        if (path.equals("/add") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String title = parseForm(body).getOrDefault("todo", "").trim();
            if (!title.isEmpty()) {
                synchronized (todos) {
                    todos.add(new Todo(nextId++, title));
                }
            }
            redirect(exchange);
            return;
        }

        if (path.equals("/done") && method.equals("GET")) {
            int id = queryId(exchange);
            synchronized (todos) {
                for (Todo todo : todos) {
                    if (todo.id == id) {
                        todo.done = true;
                        break;
                    }
                }
            }
            redirect(exchange);
            return;
        }

        if (path.equals("/delete") && method.equals("GET")) {
            int id = queryId(exchange);
            synchronized (todos) {
                todos.removeIf(todo -> todo.id == id);
            }
            redirect(exchange);
            return;
        }

        if (path.equals("/") && method.equals("GET")) {
            sendHtml(exchange, renderTodos());
        } else {
            send(exchange, 404, "text/plain; charset=UTF-8", "ページが見つかりません");
        }
    }

    private static String renderTodos() {
        List<Todo> snapshot;
        synchronized (todos) {
            snapshot = new ArrayList<>(todos);
        }

        int remaining = 0;
        for (Todo todo : snapshot) {
            if (!todo.done)
                remaining++;
        }

        StringBuilder html = new StringBuilder("<!DOCTYPE html><html lang='ja'><head><meta charset='UTF-8'>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1'>"
                + "<meta name='theme-color' content='#eaf4fb'><title>冬じたくのTodo</title><style>"
                + ":root{color-scheme:light;--ink:#24394d;--muted:#71869a;--paper:rgba(255,255,255,.9);--line:#dce9f2;--blue:#477da5;--blue-dark:#356687;--pine:#4c806e}"
                + "*{box-sizing:border-box}body{min-height:100vh;margin:0;padding:56px 20px;background:radial-gradient(ellipse at 12% 10%,rgba(255,255,255,.95),transparent 30%),radial-gradient(ellipse at 92% 78%,rgba(198,224,239,.75),transparent 34%),linear-gradient(145deg,#eaf4fb,#dcebf5 55%,#e9f1f3);color:var(--ink);font:16px/1.65 'Yu Gothic',Meiryo,sans-serif}"
                + "body:before{position:fixed;inset:0;z-index:0;pointer-events:none;content:'';opacity:.48;background-image:radial-gradient(2px 2px at 18% 24%,#fff 50%,transparent 100%),radial-gradient(3px 3px at 76% 18%,#fff 50%,transparent 100%),radial-gradient(2px 2px at 62% 71%,#fff 50%,transparent 100%),radial-gradient(3px 3px at 32% 86%,#fff 50%,transparent 100%),radial-gradient(2px 2px at 90% 54%,#fff 50%,transparent 100%);background-size:230px 230px;animation:snow 18s linear infinite}@keyframes snow{to{background-position:0 230px}}"
                + ".wrap{position:relative;z-index:1;max-width:620px;margin:0 auto}.eyebrow{display:flex;align-items:center;gap:9px;margin:0 0 10px;color:var(--blue);font-size:11px;font-weight:700;letter-spacing:.2em;text-transform:uppercase}.eyebrow:before{content:'❄';font-size:17px}"
                + "h1{margin:0;font-family:Georgia,'Yu Mincho',serif;font-size:clamp(34px,7vw,48px);font-weight:500;letter-spacing:.04em}.intro{margin:10px 0 28px;color:var(--muted)}"
                + ".card{padding:28px;background:var(--paper);border:1px solid rgba(255,255,255,.85);border-radius:22px;box-shadow:0 20px 55px rgba(49,87,116,.13);backdrop-filter:blur(8px)}"
                + "form{display:flex;gap:10px;margin-bottom:24px}input{min-width:0;flex:1;padding:13px 15px;border:1px solid var(--line);border-radius:12px;background:#f8fbfd;color:var(--ink);font:inherit;outline:none;transition:border-color .2s,box-shadow .2s}input::placeholder{color:#9aabba}input:focus{border-color:var(--blue);box-shadow:0 0 0 3px rgba(71,125,165,.14)}"
                + "button{padding:12px 19px;border:0;border-radius:12px;background:var(--blue);color:white;font:inherit;font-weight:700;cursor:pointer;box-shadow:0 5px 12px rgba(71,125,165,.2);transition:background .2s,transform .2s}button:hover{background:var(--blue-dark);transform:translateY(-1px)}"
                + ".list-head{display:flex;align-items:center;justify-content:space-between;padding-bottom:12px;border-bottom:1px solid var(--line);color:var(--muted);font-size:13px}.count{color:var(--pine);font-weight:700}ul{margin:0;padding:0;list-style:none}li{display:flex;align-items:center;gap:12px;padding:15px 2px;border-bottom:1px solid #edf2f6}li:last-child{border-bottom:0}"
                + ".mark{display:grid;width:22px;height:22px;flex:none;place-items:center;border:1.5px solid #b8cbd8;border-radius:50%;color:white;font-size:13px}.done .mark{border-color:var(--pine);background:var(--pine)}.title{flex:1;overflow-wrap:anywhere}.done .title{color:#9aabb8;text-decoration:line-through}"
                + "a{color:var(--blue);font-size:13px;text-decoration:none}a:hover{text-decoration:underline}.delete{color:#8a8290}.empty{padding:30px 8px;text-align:center;color:var(--muted)}.empty-mark{display:block;margin-bottom:8px;color:var(--blue);font-size:28px}"
                + "footer{margin-top:18px;color:#8296a7;text-align:center;font-size:12px}@media(max-width:480px){body{padding:32px 14px}.card{padding:20px}form{gap:8px}button{padding:11px 14px}}@media(prefers-reduced-motion:reduce){*,*:before,*:after{animation-duration:.01ms!important;transition-duration:.01ms!important}}"
                + "</style></head><body><main class='wrap'><p class='eyebrow'>A quiet winter, a little progress</p>"
                + "<h1>冬じたくの Todo</h1><p class='intro'>あたたかい飲みものと一緒に、今日やることをひとつずつ。</p><section class='card'>"
                + "<form method='post' action='/add'><input name='todo' placeholder='今日やることを追加…' aria-label='Todo' required><button type='submit'>＋ 追加</button></form>"
                + "<div class='list-head'><span>今日のリスト</span><span class='count'>あと " + remaining + " 件</span></div>");
        if (snapshot.isEmpty()) {
            html.append("<div class='empty'><span class='empty-mark'>✦</span>まずは、やることをひとつ書いてみましょう。</div>");
        } else {
            html.append("<ul>");
            for (Todo todo : snapshot) {
                html.append("<li class='").append(todo.done ? "done" : "")
                        .append("'><span class='mark'>").append(todo.done ? "✓" : "")
                        .append("</span><span class='title'>").append(escapeHtml(todo.title)).append("</span>");
                if (!todo.done) {
                    html.append("<a href='/done?id=").append(todo.id).append("'>完了</a>");
                }
                html.append("<a class='delete' href='/delete?id=").append(todo.id).append("'>削除</a></li>");
            }
            html.append("</ul>");
        }
        html.append("</section><footer>❄ ゆっくりでも、ひとつずつ。 ❄</footer></main></body></html>");
        return html.toString();
    }

    private static Map<String, String> parseForm(String body) {
        return java.util.Arrays.stream(body.split("&"))
                .map(pair -> pair.split("=", 2))
                .filter(pair -> pair.length == 2)
                .collect(Collectors.toMap(
                        pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8),
                        (first, second) -> second));
    }

    private static int queryId(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null)
            return -1;
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && pair[0].equals("id")) {
                try {
                    return Integer.parseInt(URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static void redirect(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Location", "/");
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
    }

    private static void sendHtml(HttpExchange exchange, String html) throws IOException {
        send(exchange, 200, "text/html; charset=UTF-8", html);
    }

    private static void send(HttpExchange exchange, int status, String contentType, String text)
            throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static class Todo {
        final int id;
        final String title;
        boolean done;

        Todo(int id, String title) {
            this.id = id;
            this.title = title;
        }
    }
}