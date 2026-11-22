import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WikiServer {
    static final Map<String, String> pages = new ConcurrentHashMap<>();
    static final String DATA_FILE = "wiki_data.txt";
    static final String LOGO_FILE = "logo.png";
    static final String IMG_DIR = "images";
    static final String ADMIN_PASS = "sans1234";

    public static void main(String[] args) throws IOException {
        Files.createDirectories(Paths.get(IMG_DIR));
        load();
        if (pages.isEmpty()) {
            pages.put("トップページ", "Undertale Scratch AU Wikiへようこそ！\nScratchのURLやiPhoneの写真から画像を貼れるぜ！");
            save();
        }
        HttpServer s = HttpServer.create(new InetSocketAddress(8080), 0);
        s.createContext("/", WikiServer::handle);
        s.createContext("/logo.png", WikiServer::logo);
        s.createContext("/images/", WikiServer::serveImg);
        s.start();
        System.out.println("Wikiサーバー起動完了！");
    }

    static void logo(HttpExchange ex) throws IOException {
        Path p = Paths.get(LOGO_FILE);
        if (Files.exists(p)) {
            byte[] b = Files.readAllBytes(p);
            ex.getResponseHeaders().set("Content-Type", "image/png");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        } else { ex.sendResponseHeaders(404, -1); }
    }

    static void serveImg(HttpExchange ex) throws IOException {
        String name = ex.getRequestURI().getPath().replace("/images/", "");
        if (name.contains("..") || name.contains("/")) { ex.sendResponseHeaders(403, -1); return; }
        Path p = Paths.get(IMG_DIR, name);
        if (Files.exists(p)) {
            byte[] b = Files.readAllBytes(p);
            ex.getResponseHeaders().set("Content-Type", "image/png");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        } else { ex.sendResponseHeaders(404, -1); }
    }

    static void load() {
        try {
            for (String l : Files.readAllLines(Paths.get(DATA_FILE))) {
                List<String> p = Arrays.asList(l.split(":", 2));
                if (p.size() == 2) {
                    String k = new String(Base64.getDecoder().decode(p.get(0)), StandardCharsets.UTF_8);
                    String v = new String(Base64.getDecoder().decode(p.get(1)), StandardCharsets.UTF_8);
                    pages.put(k, v);
                }
            }
        } catch (Exception ignored) {}
    }

    static synchronized void save() {
        try {
            StringBuilder sb = new StringBuilder();
            for (var e : pages.entrySet()) {
                String k = Base64.getEncoder().encodeToString(e.getKey().getBytes(StandardCharsets.UTF_8));
                String v = Base64.getEncoder().encodeToString(e.getValue().getBytes(StandardCharsets.UTF_8));
                sb.append(k).append(":").append(v).append("\n");
            }
            Files.write(Paths.get(DATA_FILE), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    static void handle(HttpExchange ex) throws IOException {
        String ck = ex.getRequestHeaders().getFirst("Cookie");
        boolean adm = ck != null && ck.contains("wiki_admin=" + ADMIN_PASS);
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            String f = new BufferedReader(new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)).readLine();
            Map<String, String> pm = new HashMap<>();
            if (f != null) {
                for (String s : f.split("&")) {
                    List<String> kv = Arrays.asList(s.split("=", 2));
                    if (kv.size() == 2) {
                        pm.put(URLDecoder.decode(kv.get(0), StandardCharsets.UTF_8), URLDecoder.decode(kv.get(1), StandardCharsets.UTF_8));
                    }
                }
            }
            String act = pm.getOrDefault("action", "");
            if ("login".equals(act) && ADMIN_PASS.equals(pm.get("password"))) {
                ex.getResponseHeaders().add("Set-Cookie", "wiki_admin=" + ADMIN_PASS + "; Path=/");
            }
            if ("logout".equals(act)) {
                ex.getResponseHeaders().add("Set-Cookie", "wiki_admin=; Path=/; Max-Age=0");
            }
            if (adm && "upload_logo".equals(act)) {
                String d = pm.getOrDefault("logoData", "");
                if (d.contains(",")) d = d.substring(d.indexOf(",") + 1);
                if (!d.isEmpty()) Files.write(Paths.get(LOGO_FILE), Base64.getDecoder().decode(d));
            }
            if (adm && "upload_img".equals(act)) {
                String d = pm.getOrDefault("imgData", "");
                if (d.contains(",")) d = d.substring(d.indexOf(",") + 1);
                String fname = "img_" + System.currentTimeMillis() + ".png";
                if (!d.isEmpty()) Files.write(Paths.get(IMG_DIR, fname), Base64.getDecoder().decode(d));
                byte[] res = fname.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, res.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(res); }
                return;
            }
            if (adm && "save".equals(act) && !pm.getOrDefault("title", "").trim().isEmpty()) {
                pages.put(pm.get("title").trim(), pm.getOrDefault("content", ""));
                save();
            }
            if (adm && "delete".equals(act) && !"トップページ".equals(pm.get("target"))) {
                pages.remove(pm.get("target"));
                save();
            }
            ex.getResponseHeaders().add("Location", "/");
            ex.sendResponseHeaders(302, -1);
            return;
        }
        String cur = "トップページ";
        boolean ed = false;
        if (ex.getRequestURI().getQuery() != null) {
            for (String q : ex.getRequestURI().getQuery().split("&")) {
                if (q.startsWith("page=")) cur = URLDecoder.decode(q.substring(5), StandardCharsets.UTF_8);
                if ("edit=1".equals(q)) ed = true;
            }
        }
        String body = pages.getOrDefault(cur, "ページが存在しません");
        boolean hl = Files.exists(Paths.get(LOGO_FILE));
        StringBuilder h = new StringBuilder("<!DOCTYPE html><html lang='ja'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>");
        if (hl) h.append("<link rel='icon' href='/logo.png'><link rel='apple-touch-icon' href='/logo.png'>");
        h.append("<title>Undertale Scratch AU Wiki</title><style>");
        h.append("body{font-family:sans-serif;margin:15px;background:#000;color:#eee;}");
        h.append(".card{background:#141414;padding:15px;border:2px solid #fff;margin-bottom:15px;}");
        h.append("input,textarea{width:100%;box-sizing:border-box;margin-bottom:10px;padding:10px;background:#222;color:#fff;border:1px solid #777;}");
        h.append("button{background:#ff4757;color:#fff;border:none;padding:10px;font-weight:bold;width:100%;margin-bottom:6px;cursor:pointer;}");
        h.append("a{color:#ffdd59;text-decoration:none;margin-right:10px;}");
        h.append("img{max-width:100%;display:block;margin:10px auto;border-radius:4px;}");
        h.append("iframe{width:100%;height:300px;border:none;margin:10px 0;}");
        h.append("</style></head><body>");

        if (hl) {
            h.append("<a href='/'><img src='/logo.png?t=").append(System.currentTimeMillis()).append("' style='max-height:110px;'></a>");
        } else {
            h.append("<h2>❤️ Undertale Scratch AU Wiki</h2>");
        }

        h.append("<div class='card'><strong>📚 AU一覧:</strong><br><br>");
        for (String t : pages.keySet()) {
            h.append("<a href='/?page=").append(URLEncoder.encode(t, StandardCharsets.UTF_8)).append("'>★ ").append(esc(t)).append("</a> ");
        }
        h.append("</div>");

        h.append("<div class='card'><h3>").append(esc(cur)).append("</h3><div>").append(fmt(body)).append("</div>");
        if (adm && pages.containsKey(cur)) {
            h.append("<hr><a href='/?page=").append(URLEncoder.encode(cur, StandardCharsets.UTF_8)).append("&edit=1' style='display:block;width:100%;'><button type='button' style='background:#ffa502;color:#000;'>✏️ 編集する</button></a>");
            if (!"トップページ".equals(cur)) {
                h.append("<form method='POST'><input type='hidden' name='action' value='delete'><input type='hidden' name='target' value='").append(esc(cur)).append("'><button style='background:#57606f;'>🗑️ 削除する</button></form>");
            }
        }
        h.append("</div>");

        if (adm) {
            h.append("<div class='card'><h4>").append(ed ? "✏️ 編集中" : "➕ 新規記事").append("</h4><form method='POST'><input type='hidden' name='action' value='save'>");
            h.append("<input name='title' value='").append(esc(ed ? cur : "")).append("' placeholder='AU名' required>");
            h.append("<textarea id='ct' name='content' rows='6' placeholder='本文' required>").append(esc(ed ? body : "")).append("</textarea>");
            h.append("<div style='background:#222;padding:10px;margin-bottom:10px;border:1px dashed #777;'><strong>📷 記事に写真を挿入:</strong><input type='file' id='ip' accept='image/*' style='margin-top:6px;'><button type='button' onclick='insImg()' style='background:#2ed573;color:#000;'>選んだ写真を本文に追加</button></div>");
            h.append("<button>セーブ</button></form></div>");

            h.append("<div class='card'><h4>🖼️ ロゴ・アイコン変更</h4><input type='file' id='lp' accept='image/*'><form id='lf' method='POST'><input type='hidden' name='action' value='upload_logo'><input type='hidden' name='logoData' id='ld'><button type='button' onclick='up()' style='background:#1e90ff;'>ロゴに設定</button></form></div>");

