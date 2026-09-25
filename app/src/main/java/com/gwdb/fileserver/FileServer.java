package com.gwdb.fileserver;

import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * 纯 Java 实现的轻量 HTTP 文件服务器（基于 ServerSocket，无第三方依赖）。
 * 路由：
 *   GET  /                        -> 文件管理器网页（由 setIndexHtml 注入）
 *   GET  /api/list?path=...      -> JSON 目录列表
 *   GET  /api/download?path=...  -> 下载文件（附件形式）
 *   GET  /api/view?path=...      -> 在线预览（内联 + Range 分段，支持音视频拖拽进度）
 *   POST /api/upload?path=...&name=... -> 上传（请求体为文件原始字节）
 *   POST /api/delete?path=...    -> 删除（文件或递归删除目录）
 *   POST /api/mkdir?path=...&name=... -> 新建目录
 *   POST /api/rename?path=...&name=... -> 重命名
 */
public class FileServer implements Runnable {

    private final File rootCanon;
    private final int port;
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private Thread thread;
    private String indexHtml = null;

    private static final Map<String, String> MIME = new HashMap<>();
    static {
        MIME.put("txt", "text/plain; charset=utf-8");
        MIME.put("html", "text/html; charset=utf-8");
        MIME.put("htm", "text/html; charset=utf-8");
        MIME.put("css", "text/css; charset=utf-8");
        MIME.put("js", "application/javascript; charset=utf-8");
        MIME.put("json", "application/json; charset=utf-8");
        MIME.put("xml", "application/xml; charset=utf-8");
        // 常见纯文本类（供前端在线预览）
        MIME.put("log", "text/plain; charset=utf-8");
        MIME.put("md", "text/plain; charset=utf-8");
        MIME.put("markdown", "text/plain; charset=utf-8");
        MIME.put("csv", "text/plain; charset=utf-8");
        MIME.put("yml", "text/plain; charset=utf-8");
        MIME.put("yaml", "text/plain; charset=utf-8");
        MIME.put("ini", "text/plain; charset=utf-8");
        MIME.put("conf", "text/plain; charset=utf-8");
        MIME.put("properties", "text/plain; charset=utf-8");
        MIME.put("sql", "text/plain; charset=utf-8");
        MIME.put("sh", "text/plain; charset=utf-8");
        MIME.put("bat", "text/plain; charset=utf-8");
        MIME.put("py", "text/plain; charset=utf-8");
        MIME.put("java", "text/plain; charset=utf-8");
        MIME.put("c", "text/plain; charset=utf-8");
        MIME.put("cpp", "text/plain; charset=utf-8");
        MIME.put("h", "text/plain; charset=utf-8");
        MIME.put("ts", "text/plain; charset=utf-8");
        MIME.put("jpg", "image/jpeg");
        MIME.put("jpeg", "image/jpeg");
        MIME.put("png", "image/png");
        MIME.put("gif", "image/gif");
        MIME.put("webp", "image/webp");
        MIME.put("bmp", "image/bmp");
        MIME.put("svg", "image/svg+xml");
        MIME.put("mp3", "audio/mpeg");
        MIME.put("wav", "audio/wav");
        MIME.put("ogg", "audio/ogg");
        MIME.put("m4a", "audio/mp4");
        MIME.put("mp4", "video/mp4");
        MIME.put("webm", "video/webm");
        MIME.put("mov", "video/quicktime");
        MIME.put("avi", "video/x-msvideo");
        MIME.put("mkv", "video/x-matroska");
        MIME.put("3gp", "video/3gpp");
        MIME.put("aac", "audio/aac");
        MIME.put("flac", "audio/flac");
        MIME.put("pdf", "application/pdf");
        MIME.put("zip", "application/zip");
        MIME.put("apk", "application/vnd.android.package-archive");
        MIME.put("doc", "application/msword");
        MIME.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        MIME.put("xls", "application/vnd.ms-excel");
        MIME.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    public FileServer(File root, int port) throws IOException {
        this.rootCanon = root.getCanonicalFile();
        this.port = port;
    }

    public void setIndexHtml(String html) {
        this.indexHtml = html;
    }

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return running;
    }

    public void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"));
        running = true;
        thread = new Thread(this, "FileServer");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        try {
            if (thread != null) thread.join(2000);
        } catch (InterruptedException ignored) {
        }
    }

    @Override
    public void run() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                new Thread(new ClientHandler(socket), "ClientHandler").start();
            } catch (IOException e) {
                if (running) e.printStackTrace();
            }
        }
    }

    private class ClientHandler implements Runnable {
        private final Socket socket;

        ClientHandler(Socket s) {
            this.socket = s;
        }

        @Override
        public void run() {
            try {
                handle();
            } catch (Exception ignored) {
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }

        private void handle() throws IOException {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // 1) 逐字节读取请求头，直到 \r\n\r\n
            ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
            int b;
            int state = 0; // 0 期待\r, 1 期待\n, 2 期待\r, 3 期待\n
            while (true) {
                b = in.read();
                if (b == -1) return;
                headerBuf.write(b);
                if (state == 0 && b == '\r') state = 1;
                else if (state == 1 && b == '\n') state = 2;
                else if (state == 2 && b == '\r') state = 3;
                else if (state == 3 && b == '\n') break;
                else state = 0;
            }
            String headerStr = headerBuf.toString("ISO-8859-1");
            String[] headerLines = headerStr.split("\r\n");
            if (headerLines.length == 0) return;

            String[] reqParts = headerLines[0].split(" ");
            if (reqParts.length < 2) return;
            String method = reqParts[0];
            String rawPath = reqParts[1];

            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < headerLines.length; i++) {
                int idx = headerLines[i].indexOf(':');
                if (idx > 0) {
                    headers.put(headerLines[i].substring(0, idx).trim().toLowerCase(),
                            headerLines[i].substring(idx + 1).trim());
                }
            }

            // 2) 读取请求体
            byte[] body = new byte[0];
            String cl = headers.get("content-length");
            if (cl != null) {
                int len = Integer.parseInt(cl);
                if (len > 0) {
                    body = new byte[len];
                    int read = 0;
                    while (read < len) {
                        int n = in.read(body, read, len - read);
                        if (n == -1) break;
                        read += n;
                    }
                }
            }

            // 3) 解析路径与查询参数
            String path;
            String query = "";
            int qIdx = rawPath.indexOf('?');
            if (qIdx >= 0) {
                path = rawPath.substring(0, qIdx);
                query = rawPath.substring(qIdx + 1);
            } else {
                path = rawPath;
            }
            path = URLDecoder.decode(path, "UTF-8");
            Map<String, String> params = parseQuery(query);

            try {
                route(method, path, params, headers, body, out);
            } catch (SecurityException e) {
                send(out, 403, "text/plain; charset=utf-8", "Forbidden".getBytes(StandardCharsets.UTF_8), null);
            } catch (Exception e) {
                String msg = "Error: " + (e.getMessage() == null ? e.toString() : e.getMessage());
                send(out, 500, "text/plain; charset=utf-8", msg.getBytes(StandardCharsets.UTF_8), null);
            }
        }

        private void route(String method, String path, Map<String, String> params, Map<String, String> headers, byte[] body, OutputStream out) throws IOException {
            if (path.equals("/") || path.isEmpty()) {
                if ("GET".equals(method)) {
                    String html = indexHtml != null ? indexHtml : "<h1>服务器初始化中...</h1>";
                    send(out, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8), null);
                } else {
                    send(out, 405, "text/plain; charset=utf-8", "Method Not Allowed".getBytes(StandardCharsets.UTF_8), null);
                }
                return;
            }
            switch (path) {
                case "/api/list":
                    doList(params, out);
                    return;
                case "/api/download":
                    doDownload(params, out);
                    return;
                case "/api/view":
                    doView(params, headers, out);
                    return;
                case "/api/upload":
                    doUpload(method, params, body, out);
                    return;
                case "/api/delete":
                    doDelete(params, out);
                    return;
                case "/api/mkdir":
                    doMkdir(params, out);
                    return;
                case "/api/rename":
                    doRename(params, out);
                    return;
                default:
                    send(out, 404, "text/plain; charset=utf-8", "Not Found".getBytes(StandardCharsets.UTF_8), null);
            }
        }

        private void doList(Map<String, String> params, OutputStream out) throws IOException {
            String rel = params.getOrDefault("path", "/");
            File dir = resolvePath(rel);
            if (!dir.isDirectory()) {
                sendJsonError(out, 400, "not a directory");
                return;
            }
            List<Entry> sorted = listEntries(dir);
            sorted.sort((a, b) -> {
                if (a.dir != b.dir) return a.dir ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            });

            StringBuilder items = new StringBuilder("[");
            for (int i = 0; i < sorted.size(); i++) {
                Entry e = sorted.get(i);
                if (i > 0) items.append(',');
                items.append("{\"name\":").append(jsonString(e.name))
                        .append(",\"type\":\"").append(e.dir ? "dir" : "file").append("\"")
                        .append(",\"size\":").append(e.dir ? 0 : e.size)
                        .append(",\"modified\":").append(e.mtime)
                        .append("}");
            }
            items.append("]");
            String json = "{\"path\":" + jsonString(rel) + ",\"items\":" + items + "}";
            send(out, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8), null);
        }

        private void doDownload(Map<String, String> params, OutputStream out) throws IOException {
            String rel = params.getOrDefault("path", "/");
            File f = resolvePath(rel);
            if (!f.isFile()) {
                send(out, 404, "text/plain; charset=utf-8", "Not Found".getBytes(StandardCharsets.UTF_8), null);
                return;
            }
            String mime = guessMime(f.getName());
            String name = f.getName();
            boolean ascii = name.matches("\\A[\\x20-\\x7E]+");
            StringBuilder disp = new StringBuilder("attachment");
            if (ascii) {
                disp.append("; filename=\"").append(name).append("\"");
            } else {
                disp.append("; filename=\"file\"");
            }
            disp.append("; filename*=UTF-8''").append(urlEncode(name));
            sendFile(out, 200, mime, f, "Content-Disposition: " + disp + "\r\n");
        }

        /** 在线预览：内联展示 + 支持 Range 分段请求（视频拖进度条、音频 Seek 必需）。 */
        private void doView(Map<String, String> params, Map<String, String> headers, OutputStream out) throws IOException {
            String rel = params.getOrDefault("path", "/");
            File f = resolvePath(rel);
            if (!f.isFile()) {
                send(out, 404, "text/plain; charset=utf-8", "Not Found".getBytes(StandardCharsets.UTF_8), null);
                return;
            }
            long total = f.length();
            String mime = guessMime(f.getName());
            long start = 0, end = total - 1;
            boolean partial = false;

            String range = headers.get("range");
            if (range != null && range.startsWith("bytes=")) {
                String v = range.substring(6).trim();
                int dash = v.indexOf('-');
                if (dash < 0) { send(out, 416, "text/plain; charset=utf-8", "Bad Range".getBytes(StandardCharsets.UTF_8), null); return; }
                String s0 = v.substring(0, dash).trim();
                String e0 = v.substring(dash + 1).trim();
                try {
                    if (s0.isEmpty() && !e0.isEmpty()) {
                        // 后缀形式 bytes=-500：取最后 500 字节
                        long n = Long.parseLong(e0);
                        if (n <= 0) throw new NumberFormatException();
                        start = Math.max(0, total - n);
                    } else {
                        start = Long.parseLong(s0);
                        if (!e0.isEmpty()) end = Math.min(total - 1, Long.parseLong(e0));
                    }
                } catch (NumberFormatException ignored) {
                }
                if (start > end || start >= total) {
                    send(out, 416, "text/plain; charset=utf-8", "Range Not Satisfiable".getBytes(StandardCharsets.UTF_8),
                            "Content-Range: bytes */" + total + "\r\n");
                    return;
                }
                partial = true;
            }

            long contentLen = end - start + 1;
            StringBuilder h = new StringBuilder();
            h.append("HTTP/1.1 ").append(partial ? "206 Partial Content" : "200 OK").append("\r\n");
            h.append("Content-Type: ").append(mime).append("\r\n");
            h.append("Content-Length: ").append(contentLen).append("\r\n");
            h.append("Content-Disposition: inline\r\n");
            h.append("Accept-Ranges: bytes\r\n");
            if (partial) h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(total).append("\r\n");
            h.append("Connection: close\r\n\r\n");
            out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));

            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                raf.seek(start);
                byte[] buf = new byte[16384];
                long remaining = contentLen;
                while (remaining > 0) {
                    int n = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (n <= 0) break;
                    out.write(buf, 0, n);
                    remaining -= n;
                }
            }
            out.flush();
        }

        private void doUpload(String method, Map<String, String> params, byte[] body, OutputStream out) throws IOException {
            if (!"POST".equals(method)) {
                send(out, 405, "text/plain; charset=utf-8", "Method Not Allowed".getBytes(StandardCharsets.UTF_8), null);
                return;
            }
            String dirRel = params.getOrDefault("path", "/");
            String name = params.get("name");
            if (name == null || name.isEmpty()) {
                sendJsonError(out, 400, "missing name");
                return;
            }
            File dir = resolvePath(dirRel);
            if (!dir.isDirectory()) {
                sendJsonError(out, 400, "not a directory");
                return;
            }
            String safe = sanitizeName(name);
            File target = new File(dir, safe);
            try (FileOutputStream fos = new FileOutputStream(target)) {
                fos.write(body);
            }
            String json = "{\"ok\":true,\"name\":" + jsonString(safe) + ",\"size\":" + body.length + "}";
            send(out, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8), null);
        }

        private void doDelete(Map<String, String> params, OutputStream out) throws IOException {
            String rel = params.getOrDefault("path", "/");
            File f = resolvePath(rel);
            if (f.getPath().equals(rootCanon.getPath())) {
                sendJsonError(out, 403, "cannot delete root");
                return;
            }
            if (!f.exists()) {
                sendJsonError(out, 404, "not found");
                return;
            }
            boolean ok = deleteRecursively(f);
            send(out, 200, "application/json; charset=utf-8",
                    ("{\"ok\":" + ok + "}").getBytes(StandardCharsets.UTF_8), null);
        }

        private void doMkdir(Map<String, String> params, OutputStream out) throws IOException {
            File dir = resolvePath(params.getOrDefault("path", "/"));
            String name = sanitizeName(params.get("name"));
            File target = new File(dir, name);
            boolean ok = target.mkdirs();
            send(out, 200, "application/json; charset=utf-8",
                    ("{\"ok\":" + ok + ",\"name\":" + jsonString(name) + "}").getBytes(StandardCharsets.UTF_8), null);
        }

        private void doRename(Map<String, String> params, OutputStream out) throws IOException {
            File f = resolvePath(params.get("path"));
            String nn = sanitizeName(params.get("name"));
            File target = new File(f.getParentFile(), nn);
            boolean ok = f.renameTo(target);
            send(out, 200, "application/json; charset=utf-8",
                    ("{\"ok\":" + ok + ",\"name\":" + jsonString(nn) + "}").getBytes(StandardCharsets.UTF_8), null);
        }

        // ---- 工具方法 ----

        /** 目录条目快照 */
        private static class Entry {
            final String name; final boolean dir; final long size; final long mtime;
            Entry(String n, boolean d, long s, long m) { name = n; dir = d; size = s; mtime = m; }
        }

        /**
         * 快速列目录：listFiles() 本身只做一次 readdir（无 stat），
         * 每个条目再用一次 Os.lstat 同时取类型/大小/修改时间。
         * 大目录（≥128 项）使用线程池并行 stat：FUSE 元数据操作是跨进程 IPC 等待，
         * 并行可成倍提速。stat 失败时逐条回退 File API。
         */
        /** 共享 stat 线程池：IO 等待型，线程数随核心数浮动；空闲 60s 后回收 */
        private static final ExecutorService STAT_POOL;
        static {
            int cores = Math.max(6, Runtime.getRuntime().availableProcessors() * 2);
            ThreadPoolExecutor ex = new ThreadPoolExecutor(
                    cores, cores, 60, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(),
                    r -> { Thread t = new Thread(r, "StatWorker"); t.setDaemon(true); return t; });
            ex.allowCoreThreadTimeOut(true);
            STAT_POOL = ex;
        }

        private static Entry statEntry(File f) {
            try {
                StructStat st = Os.lstat(f.getAbsolutePath());
                boolean isDir = (st.st_mode & OsConstants.S_IFMT) == OsConstants.S_IFDIR;
                // 目录不显示大小/时间，文件取 stat 结果
                return new Entry(f.getName(), isDir,
                        isDir ? 0 : st.st_size,
                        isDir ? 0 : st.st_mtime * 1000L);
            } catch (Throwable e) {
                boolean isDir = f.isDirectory();
                return new Entry(f.getName(), isDir,
                        isDir ? 0 : f.length(), isDir ? 0 : f.lastModified());
            }
        }

        private static List<Entry> listEntries(File dir) {
            List<Entry> out = new ArrayList<>();
            File[] files = dir.listFiles();
            if (files == null) return out;
            final int n = files.length;
            if (n >= 128) {
                // 大目录：并行 stat，按索引写回保持确定顺序
                final Entry[] arr = new Entry[n];
                List<Future<?>> futures = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    final int idx = i;
                    final File f = files[i];
                    futures.add(STAT_POOL.submit(() -> arr[idx] = statEntry(f)));
                }
                for (Future<?> fu : futures) {
                    try { fu.get(); } catch (Throwable ignored) {}
                }
                for (Entry e : arr) if (e != null) out.add(e);
            } else {
                for (File f : files) out.add(statEntry(f));
            }
            return out;
        }

        private File resolvePath(String rel) throws SecurityException, IOException {
            String r = rel;
            while (r.startsWith("/")) r = r.substring(1);
            File f;
            if (r.isEmpty()) {
                f = rootCanon;
            } else {
                // 注意：new File(parent, "/abs") 在 Unix 下会忽略 parent，因此必须先去掉前导 /
                f = new File(rootCanon, r).getCanonicalFile();
            }
            String fp = f.getPath();
            if (!fp.equals(rootCanon.getPath()) && !fp.startsWith(rootCanon.getPath() + File.separator)) {
                throw new SecurityException("path traversal denied");
            }
            return f;
        }

        private boolean deleteRecursively(File f) {
            if (f.isDirectory()) {
                File[] children = f.listFiles();
                if (children != null) {
                    for (File c : children) deleteRecursively(c);
                }
            }
            return f.delete();
        }

        private static String sanitizeName(String n) {
            if (n == null) return "unnamed";
            String s = n.trim();
            s = s.replaceAll("[/\\\\]", "_");
            s = s.replaceAll("[\\x00-\\x1f\\x7f]", "");
            if (s.isEmpty()) s = "unnamed";
            return s;
        }

        private static String guessMime(String name) {
            int i = name.lastIndexOf('.');
            if (i < 0) return "application/octet-stream";
            String ext = name.substring(i + 1).toLowerCase(Locale.ROOT);
            return MIME.getOrDefault(ext, "application/octet-stream");
        }

        private static String urlEncode(String s) {
            try {
                return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
            } catch (UnsupportedEncodingException e) {
                return s;
            }
        }

        private void send(OutputStream out, int code, String contentType, byte[] body, String extraHeaders) throws IOException {
            StringBuilder h = new StringBuilder();
            h.append("HTTP/1.1 ").append(code).append(' ').append(reasonFor(code)).append("\r\n");
            h.append("Content-Type: ").append(contentType).append("\r\n");
            h.append("Content-Length: ").append(body.length).append("\r\n");
            if (extraHeaders != null) h.append(extraHeaders);
            h.append("Connection: close\r\n");
            h.append("\r\n");
            out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
        }

        private void sendFile(OutputStream out, int code, String mime, File f, String extraHeaders) throws IOException {
            StringBuilder h = new StringBuilder();
            h.append("HTTP/1.1 ").append(code).append(' ').append(reasonFor(code)).append("\r\n");
            h.append("Content-Type: ").append(mime).append("\r\n");
            h.append("Content-Length: ").append(f.length()).append("\r\n");
            if (extraHeaders != null) h.append(extraHeaders);
            h.append("Connection: close\r\n\r\n");
            out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = fis.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            out.flush();
        }

        private void sendJsonError(OutputStream out, int code, String msg) throws IOException {
            String json = "{\"ok\":false,\"error\":" + jsonString(msg) + "}";
            send(out, code, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8), null);
        }

        private static String reasonFor(int code) {
            switch (code) {
                case 200: return "OK";
                case 400: return "Bad Request";
                case 403: return "Forbidden";
                case 404: return "Not Found";
                case 405: return "Method Not Allowed";
                case 416: return "Range Not Satisfiable";
                case 500: return "Internal Server Error";
                default: return "OK";
            }
        }

        private static String jsonString(String s) {
            StringBuilder b = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': b.append("\\\""); break;
                    case '\\': b.append("\\\\"); break;
                    case '\n': b.append("\\n"); break;
                    case '\r': b.append("\\r"); break;
                    case '\t': b.append("\\t"); break;
                    default:
                        if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                        else b.append(c);
                }
            }
            b.append("\"");
            return b.toString();
        }

        private static Map<String, String> parseQuery(String query) {
            Map<String, String> map = new HashMap<>();
            if (query == null || query.isEmpty()) return map;
            for (String pair : query.split("&")) {
                int idx = pair.indexOf('=');
                try {
                    if (idx < 0) {
                        map.put(URLDecoder.decode(pair, "UTF-8"), "");
                    } else {
                        map.put(URLDecoder.decode(pair.substring(0, idx), "UTF-8"),
                                URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
                    }
                } catch (UnsupportedEncodingException ignored) {
                }
            }
            return map;
        }
    }
}
