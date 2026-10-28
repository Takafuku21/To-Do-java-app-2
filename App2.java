import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class App2 {
    private static final List<Todo> todos = new ArrayList<>();
    private static final Path DATA_FILE = Path.of("todos.tsv");
    private static int nextId = 1;

    public static void main(String[] args) throws IOException {
        loadTodos();
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", App2::handle);
        server.start();
        System.out.println("サーバー起動: http://localhost:8080 (終了するには Ctrl+C)");
        System.out.println("Todoデータ保存先: " + DATA_FILE.toAbsolutePath().normalize());
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        if (path.equals("/add") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> form = parseForm(body);
            String title = form.getOrDefault("todo", "").trim();
            String dueDateValue = form.getOrDefault("dueDate", "").trim();
            LocalDate dueDate = null;
            if (!dueDateValue.isEmpty()) {
                try {
                    dueDate = LocalDate.parse(dueDateValue);
                } catch (DateTimeParseException exception) {
                    send(exchange, 400, "text/plain; charset=UTF-8", "期限の日付が正しくありません");
                    return;
                }
            }
            if (!title.isEmpty()) {
                synchronized (todos) {
                    Todo todo = new Todo(nextId++, title, dueDate);
                    todos.add(todo);
                    try {
                        saveTodos();
                    } catch (IOException exception) {
                        todos.remove(todo);
                        nextId--;
                        sendPersistenceError(exchange, exception);
                        return;
                    }
                }
            }
            redirect(exchange, normalizeFilter(form.getOrDefault("filter", "all")),
                    form.getOrDefault("q", ""));
            return;
        }

        if (path.equals("/done") && method.equals("GET")) {
            int id = queryId(exchange);
            synchronized (todos) {
                for (Todo todo : todos) {
                    if (todo.id == id) {
                        if (!todo.done) {
                            todo.done = true;
                            try {
                                saveTodos();
                            } catch (IOException exception) {
                                todo.done = false;
                                sendPersistenceError(exchange, exception);
                                return;
                            }
                        }
                        break;
                    }
                }
            }
            redirect(exchange, requestFilter(exchange), requestSearch(exchange));
            return;
        }

        if (path.equals("/delete") && method.equals("GET")) {
            int id = queryId(exchange);
            synchronized (todos) {
                for (int i = 0; i < todos.size(); i++) {
                    if (todos.get(i).id == id) {
                        Todo removed = todos.remove(i);
                        try {
                            saveTodos();
                        } catch (IOException exception) {
                            todos.add(i, removed);
                            sendPersistenceError(exchange, exception);
                            return;
                        }
                        break;
                    }
                }
            }
            redirect(exchange, requestFilter(exchange), requestSearch(exchange));
            return;
        }

        if (path.equals("/clear-completed") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> form = parseForm(body);
            String filter = normalizeFilter(form.getOrDefault("filter", "all"));
            String search = form.getOrDefault("q", "");
            try {
                clearCompletedTodos();
            } catch (IOException exception) {
                sendPersistenceError(exchange, exception);
                return;
            }
            redirect(exchange, filter, search);
            return;
        }

        if (path.equals("/") && method.equals("GET")) {
            sendHtml(exchange, renderTodos(requestFilter(exchange), requestSearch(exchange)));
        } else {
            send(exchange, 404, "text/plain; charset=UTF-8", "ページが見つかりません");
        }
    }

    private static void loadTodos() throws IOException {
        if (!Files.exists(DATA_FILE)) {
            return;
        }

        List<String> lines = Files.readAllLines(DATA_FILE, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).equals("TODO_APP_DATA_V1")) {
            throw new IOException("Todoデータファイルの形式が正しくありません: " + DATA_FILE);
        }

        List<Todo> loadedTodos = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        int maxId = 0;
        for (int i = 1; i < lines.size(); i++) {
            String[] fields = lines.get(i).split("\t", -1);
            if (fields.length != 4) {
                throw new IOException("Todoデータファイルの" + (i + 1) + "行目が正しくありません");
            }

            try {
                int id = Integer.parseInt(fields[0]);
                if (id <= 0 || !ids.add(id) || !(fields[1].equals("true") || fields[1].equals("false"))) {
                    throw new IllegalArgumentException("不正なIDまたは完了状態");
                }
                LocalDate dueDate = fields[2].isEmpty() ? null : LocalDate.parse(fields[2]);
                String title = new String(Base64.getDecoder().decode(fields[3]), StandardCharsets.UTF_8);
                if (title.isEmpty()) {
                    throw new IllegalArgumentException("Todoの内容が空です");
                }

                Todo todo = new Todo(id, title, dueDate);
                todo.done = Boolean.parseBoolean(fields[1]);
                loadedTodos.add(todo);
                maxId = Math.max(maxId, id);
            } catch (IllegalArgumentException | DateTimeParseException exception) {
                throw new IOException("Todoデータファイルの" + (i + 1) + "行目を読み込めません", exception);
            }
        }
        if (maxId == Integer.MAX_VALUE) {
            throw new IOException("Todo IDの上限に達しています");
        }

        synchronized (todos) {
            todos.clear();
            todos.addAll(loadedTodos);
            nextId = maxId + 1;
        }
    }

    private static void saveTodos() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("TODO_APP_DATA_V1");
        for (Todo todo : todos) {
            String dueDate = todo.dueDate == null ? "" : todo.dueDate.toString();
            String title = Base64.getEncoder().encodeToString(todo.title.getBytes(StandardCharsets.UTF_8));
            lines.add(todo.id + "\t" + todo.done + "\t" + dueDate + "\t" + title);
        }

        Path tempFile = DATA_FILE.resolveSibling(DATA_FILE.getFileName() + ".tmp");
        try {
            Files.write(tempFile, lines, StandardCharsets.UTF_8);
            try {
                Files.move(tempFile, DATA_FILE, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(tempFile, DATA_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private static void clearCompletedTodos() throws IOException {
        synchronized (todos) {
            List<Todo> previousTodos = new ArrayList<>(todos);
            boolean removed = todos.removeIf(todo -> todo.done);
            if (!removed) {
                return;
            }

            try {
                saveTodos();
            } catch (IOException exception) {
                todos.clear();
                todos.addAll(previousTodos);
                throw exception;
            }
        }
    }

    private static void sendPersistenceError(HttpExchange exchange, IOException exception) throws IOException {
        System.err.println("Todoデータの保存に失敗しました: " + exception.getMessage());
        send(exchange, 500, "text/plain; charset=UTF-8", "Todoを保存できませんでした。データ保存先を確認してください。");
    }

    private static String renderTodos(String filter, String search) {
        List<Todo> snapshot;
        synchronized (todos) {
            snapshot = new ArrayList<>(todos);
        }

        String normalizedSearch = search.trim().toLowerCase(Locale.ROOT);
        String encodedSearch = URLEncoder.encode(search, StandardCharsets.UTF_8);
        int remaining = 0;
        int completedCount = 0;
        List<Todo> visibleTodos = new ArrayList<>();
        for (Todo todo : snapshot) {
            if (!todo.done) {
                remaining++;
            } else {
                completedCount++;
            }
            if (filter.equals("all")
                    || (filter.equals("active") && !todo.done)
                    || (filter.equals("completed") && todo.done)) {
                if (normalizedSearch.isEmpty()
                        || todo.title.toLowerCase(Locale.ROOT).contains(normalizedSearch)) {
                    visibleTodos.add(todo);
                }
            }
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
                + "form{display:flex;flex-wrap:wrap;align-items:end;gap:10px;margin-bottom:24px}input{min-width:0;flex:1 1 220px;padding:13px 15px;border:1px solid var(--line);border-radius:12px;background:#f8fbfd;color:var(--ink);font:inherit;outline:none;transition:border-color .2s,box-shadow .2s}input::placeholder{color:#9aabba}input:focus{border-color:var(--blue);box-shadow:0 0 0 3px rgba(71,125,165,.14)}.due-field{display:flex;flex:none;flex-direction:column;gap:3px;color:var(--muted);font-size:12px}.due-field input{flex:none;width:165px;height:54px;padding:13px 11px;font-size:13px}"
                + "button{padding:12px 19px;border:0;border-radius:12px;background:var(--blue);color:white;font:inherit;font-weight:700;cursor:pointer;box-shadow:0 5px 12px rgba(71,125,165,.2);transition:background .2s,transform .2s}button:hover{background:var(--blue-dark);transform:translateY(-1px)}"
                + ".clear-completed-form{display:flex;justify-content:flex-end;margin:8px 0 0}.clear-completed-button{padding:7px 12px;border:1px solid var(--line);background:transparent;color:#8a8290;font-size:12px;box-shadow:none}.clear-completed-button:hover{background:#f7eeee;color:#a55353}"
                + ".list-head{display:flex;align-items:center;justify-content:space-between;padding-bottom:12px;border-bottom:1px solid var(--line);color:var(--muted);font-size:13px}.count{color:var(--pine);font-weight:700}ul{margin:0;padding:0;list-style:none}li{display:flex;align-items:center;gap:12px;padding:15px 2px;border-bottom:1px solid #edf2f6}li:last-child{border-bottom:0}"
                + ".search-form{margin:0 0 12px}.search-form input{flex:1 1 220px}.clear-search{font-size:12px}"
                + ".filters{display:flex;gap:8px;margin:14px 0 4px}.filter{padding:6px 12px;border:1px solid var(--line);border-radius:999px;color:var(--muted);font-size:13px}.filter.selected{border-color:var(--blue);background:#edf5fa;color:var(--blue-dark);font-weight:700}"
                + ".mark{display:grid;width:22px;height:22px;flex:none;place-items:center;border:1.5px solid #b8cbd8;border-radius:50%;color:white;font-size:13px}.done .mark{border-color:var(--pine);background:var(--pine)}.todo-info{min-width:0;flex:1}.title{display:block;overflow-wrap:anywhere}.done .title{color:#9aabb8;text-decoration:line-through}.deadline{display:block;color:var(--muted);font-size:12px}.deadline.today{color:#ad792e;font-weight:700}.deadline.overdue{color:#c45f58;font-weight:700}"
                + "a{color:var(--blue);font-size:13px;text-decoration:none}a:hover{text-decoration:underline}.delete{color:#8a8290}.empty{padding:30px 8px;text-align:center;color:var(--muted)}.empty-mark{display:block;margin-bottom:8px;color:var(--blue);font-size:28px}"
                + ".snowfall{position:fixed;inset:0;z-index:0;overflow:hidden;pointer-events:none}.flake{position:absolute;top:-24px;left:var(--left);color:#fff;text-shadow:0 1px 5px rgba(87,130,157,.45);font-size:var(--size);opacity:.82;animation:fall var(--speed) linear var(--delay) infinite}@keyframes fall{to{transform:translate3d(38px,110vh,0) rotate(360deg)}}"
                + ".snow-scene{position:relative;height:112px;margin-top:8px;overflow:hidden}.snowman-track{position:absolute;inset:0 auto 0 0;width:78px;animation:walk 12s ease-in-out infinite alternate}@keyframes walk{to{transform:translateX(calc(min(620px,100vw - 40px) - 78px))}}"
                + ".snowman{position:absolute;bottom:0;left:0;width:78px;height:104px;animation:bob 1.1s ease-in-out infinite alternate;transform-origin:bottom center}@keyframes bob{to{transform:translateY(-4px) rotate(3deg)}}"
                + ".snow-body{position:absolute;bottom:0;left:10px;width:58px;height:58px;border:1px solid #d5e5ee;border-radius:50%;background:radial-gradient(circle at 35% 26%,#fff 0 14%,#f5fbff 55%,#dcebf3 100%);box-shadow:inset -5px -5px 0 rgba(155,190,209,.14)}"
                + ".snow-head{position:absolute;bottom:47px;left:17px;width:44px;height:44px;border:1px solid #d5e5ee;border-radius:50%;background:radial-gradient(circle at 34% 43%,#344b5d 0 2px,transparent 2.5px),radial-gradient(circle at 67% 43%,#344b5d 0 2px,transparent 2.5px),radial-gradient(circle at 36% 52%,#efaaa0 0 2px,transparent 2.5px),radial-gradient(circle at 65% 52%,#efaaa0 0 2px,transparent 2.5px),linear-gradient(145deg,#fff,#eaf5fa);box-shadow:inset -4px -4px 0 rgba(155,190,209,.12)}"
                + ".snow-head:after{position:absolute;top:19px;left:27px;width:0;height:0;border-top:4px solid transparent;border-bottom:4px solid transparent;border-left:10px solid #e8a45e;content:''}"
                + ".snow-scarf{position:absolute;z-index:2;bottom:47px;left:16px;width:47px;height:9px;border-radius:5px;background:#d66b63;transform:rotate(-5deg)}.snow-scarf:after{position:absolute;top:5px;right:5px;width:8px;height:17px;border-radius:0 0 5px 5px;background:#d66b63;content:''}"
                + ".snow-hat{position:absolute;z-index:2;bottom:87px;left:20px;width:38px;height:7px;border-radius:4px;background:#477da5}.snow-hat:before{position:absolute;bottom:5px;left:7px;width:24px;height:17px;border-radius:8px 8px 2px 2px;background:#477da5;content:''}.snow-hat:after{position:absolute;z-index:1;top:-7px;left:15px;width:8px;height:8px;border-radius:50%;background:#d66b63;content:''}"
                + ".snow-arm{position:absolute;z-index:-1;bottom:35px;width:24px;height:3px;border-radius:3px;background:#9b7659;transform-origin:right center}.snow-arm.left{left:-8px;transform:rotate(-25deg)}.snow-arm.right{right:-8px;transform-origin:left center;transform:rotate(25deg)}"
                + "footer{margin-top:2px;color:#8296a7;text-align:center;font-size:12px}@media(max-width:480px){body{padding:32px 14px}.card{padding:20px}form{gap:8px}button{padding:11px 14px}}@media(prefers-reduced-motion:reduce){*,*:before,*:after{animation-duration:.01ms!important;transition-duration:.01ms!important}.snowfall{display:none}.snowman-track,.snowman{animation:none!important}}"
                + "</style></head><body><div class='snowfall' aria-hidden='true'>"
                + "<span class='flake' style='--left:7%;--size:13px;--speed:12s;--delay:-3s'>❄</span><span class='flake' style='--left:16%;--size:9px;--speed:16s;--delay:-11s'>❅</span>"
                + "<span class='flake' style='--left:25%;--size:15px;--speed:14s;--delay:-6s'>❄</span><span class='flake' style='--left:34%;--size:10px;--speed:18s;--delay:-14s'>❅</span>"
                + "<span class='flake' style='--left:43%;--size:12px;--speed:13s;--delay:-8s'>❄</span><span class='flake' style='--left:52%;--size:8px;--speed:17s;--delay:-2s'>❅</span>"
                + "<span class='flake' style='--left:61%;--size:14px;--speed:15s;--delay:-12s'>❄</span><span class='flake' style='--left:70%;--size:10px;--speed:12s;--delay:-5s'>❅</span>"
                + "<span class='flake' style='--left:79%;--size:13px;--speed:19s;--delay:-16s'>❄</span><span class='flake' style='--left:88%;--size:9px;--speed:14s;--delay:-9s'>❅</span>"
                + "<span class='flake' style='--left:96%;--size:12px;--speed:16s;--delay:-4s'>❄</span></div><main class='wrap'><p class='eyebrow'>A quiet winter, a little progress</p>"
                + "<h1>冬じたくの Todo</h1><p class='intro'>あたたかい飲みものと一緒に、今日やることをひとつずつ。</p><section class='card'>"
                + "<form method='post' action='/add'><input type='hidden' name='filter' value='" + filter
                + "'><input type='hidden' name='q' value='" + escapeHtml(search)
                + "'><input name='todo' placeholder='今日やることを追加…' aria-label='Todo' required>"
                + "<label class='due-field'>期限<input type='date' name='dueDate' aria-label='期限'></label><button type='submit'>＋ 追加</button></form>"
                + "<form class='search-form' method='get' action='/'><input type='hidden' name='filter' value='"
                + filter + "'><input type='search' name='q' value='" + escapeHtml(search)
                + "' placeholder='キーワードで検索…' aria-label='Todoを検索'>"
                + "<button type='submit'>検索</button>"
                + (search.isEmpty() ? "" : "<a class='clear-search' href='/?filter=" + filter
                        + "'>クリア</a>") + "</form>"
                + "<div class='list-head'><span>今日のリスト</span><span class='count'>あと " + remaining + " 件</span></div>"
                + "<nav class='filters' aria-label='Todoの表示'><a class='filter"
                + (filter.equals("active") ? " selected" : "") + "' href='/?filter=active&amp;q="
                + encodedSearch + "'"
                + (filter.equals("active") ? " aria-current='page'" : "") + ">未完了だけ</a><a class='filter"
                + (filter.equals("completed") ? " selected" : "") + "' href='/?filter=completed&amp;q="
                + encodedSearch + "'"
                + (filter.equals("completed") ? " aria-current='page'" : "") + ">完了だけ</a><a class='filter"
                + (filter.equals("all") ? " selected" : "") + "' href='/?filter=all&amp;q="
                + encodedSearch + "'"
                + (filter.equals("all") ? " aria-current='page'" : "") + ">全部</a></nav>");
        if (completedCount > 0) {
            html.append("<form class='clear-completed-form' method='post' action='/clear-completed' "
                    + "onsubmit=\"return confirm('完了済みのTodoをすべて削除しますか？')\">")
                    .append("<input type='hidden' name='filter' value='").append(filter)
                    .append("'><input type='hidden' name='q' value='").append(escapeHtml(search))
                    .append("'><button class='clear-completed-button' type='submit'>完了済みを一括削除</button></form>");
        }
        if (visibleTodos.isEmpty()) {
            String emptyMessage = snapshot.isEmpty()
                    ? "まずは、やることをひとつ書いてみましょう。"
                    : "この条件にあてはまるTodoはありません。";
            html.append("<div class='empty'><span class='empty-mark'>✦</span>")
                    .append(emptyMessage).append("</div>");
        } else {
            html.append("<ul>");
            for (Todo todo : visibleTodos) {
                String deadlineClass = "";
                String deadlineLabel = "";
                if (todo.dueDate != null) {
                    LocalDate today = LocalDate.now();
                    if (!todo.done && todo.dueDate.isBefore(today)) {
                        deadlineClass = " overdue";
                        deadlineLabel = "期限切れ";
                    } else if (!todo.done && todo.dueDate.equals(today)) {
                        deadlineClass = " today";
                        deadlineLabel = "今日まで";
                    } else {
                        deadlineLabel = "期限";
                    }
                }
                html.append("<li class='").append(todo.done ? "done" : "")
                        .append("'><span class='mark'>").append(todo.done ? "✓" : "")
                        .append("</span><span class='todo-info'><span class='title'>")
                        .append(escapeHtml(todo.title)).append("</span>");
                if (todo.dueDate != null) {
                    html.append("<span class='deadline").append(deadlineClass).append("'>")
                            .append(deadlineLabel).append(": ")
                            .append(todo.dueDate.format(DateTimeFormatter.ofPattern("yyyy年M月d日")))
                            .append("</span>");
                }
                html.append("</span>");
                if (!todo.done) {
                    html.append("<a href='/done?id=").append(todo.id).append("&amp;filter=")
                            .append(filter).append("&amp;q=").append(encodedSearch).append("'>完了</a>");
                }
                html.append("<a class='delete' href='/delete?id=").append(todo.id)
                        .append("&amp;filter=").append(filter).append("&amp;q=")
                        .append(encodedSearch).append("'>削除</a></li>");
            }
            html.append("</ul>");
        }
        html.append("</section><div class='snow-scene' aria-hidden='true'><div class='snowman-track'><div class='snowman'>"
                + "<span class='snow-arm left'></span><span class='snow-arm right'></span><span class='snow-body'></span>"
                + "<span class='snow-head'></span><span class='snow-scarf'></span><span class='snow-hat'></span>"
                + "</div></div></div><footer>❄ ゆっくりでも、ひとつずつ。 ❄</footer></main></body></html>");
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

    private static String requestFilter(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        return normalizeFilter(query == null ? "all" : parseForm(query).getOrDefault("filter", "all"));
    }

    private static String requestSearch(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        return query == null ? "" : parseForm(query).getOrDefault("q", "").trim();
    }

    private static String normalizeFilter(String filter) {
        if (filter.equals("active") || filter.equals("completed")) {
            return filter;
        }
        return "all";
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static void redirect(HttpExchange exchange, String filter, String search) throws IOException {
        exchange.getResponseHeaders().set("Location", "/?filter=" + filter + "&q="
                + URLEncoder.encode(search, StandardCharsets.UTF_8));
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
        final LocalDate dueDate;
        boolean done;

        Todo(int id, String title, LocalDate dueDate) {
            this.id = id;
            this.title = title;
            this.dueDate = dueDate;
        }
    }
}