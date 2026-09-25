package com.gwdb.fileserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.BindException;
import java.nio.charset.StandardCharsets;

/**
 * 前台服务：持有 HTTP 服务器线程，保证 App 退到后台后服务器仍可用。
 * 同时负责从 assets 读取网页并注入服务器。
 */
public class ServerService extends Service {

    public static final String ACTION_START = "com.gwdb.fileserver.START";
    public static final String ACTION_STOP  = "com.gwdb.fileserver.STOP";

    /** 启动时传入的偏好端口 */
    public static final String EXTRA_START_PORT = "start_port";

    public static final int DEFAULT_PORT = 8080;
    public static final int PORT_RANGE   = 10; // 尝试偏好端口起结10个

    /** 当前实际运行端口，-1 表示未启动。供 UI 读取。 */
    public static volatile int activePort = -1;

    // ---- 进内回调机制（代替广播，解决部分 ROM 广播不可达问题）----

    /** 服务启动结果回调 */
    public interface StatusCallback {
        void onServerStarted(int port);
        void onServerFailed(String error);
    }

    private static WeakReference<StatusCallback> sCallback;

    /** 由 MainActivity 在 onCreate 中注册，onDestroy 中清零 */
    public static void setStatusCallback(StatusCallback cb) {
        sCallback = (cb == null) ? null : new WeakReference<>(cb);
    }

    /** 在调用线程直接触发；由于 Service 的 onStartCommand 运行在主线程，与 Activity 共享同一线盘 */
    private static void fireStarted(int port) {
        if (sCallback != null) {
            StatusCallback cb = sCallback.get();
            if (cb != null) cb.onServerStarted(port);
        }
    }

    private static void fireFailed(String err) {
        if (sCallback != null) {
            StatusCallback cb = sCallback.get();
            if (cb != null) cb.onServerFailed(err);
        }
    }

    private static final String CHANNEL_ID = "fileserver_channel";

    private FileServer server;
    private int startPort = DEFAULT_PORT; // 实际使用的起始端口，由 Intent 传入

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopServer();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null) {
            startPort = intent.getIntExtra(EXTRA_START_PORT, DEFAULT_PORT);
        }
        startServer();
        return START_STICKY;
    }

    private void startServer() {
        if (server != null && server.isRunning()) return;

        // 立即进入前台，防止 Android 8+ 的 5 秒超时崩溃
        startForeground(1, buildNotification("正在启动...", "正在尝试绑定端口..."));

        try {
            String ip   = getLocalIp();
            File   root = Environment.getExternalStorageDirectory();

            for (int port = startPort; port < startPort + PORT_RANGE; port++) {
                try {
                    server = new FileServer(root, port);
                    server.setIndexHtml(loadAsset("index.html"));
                    server.start();
                    activePort = port;
                    String url = (ip != null ? ip : "<本机IP>") + ":" + port;
                    // 启动成功，更新通知为实际地址
                    startForeground(1, buildNotification("文件服务器运行中", "http://" + url));
                    fireStarted(port);
                    return;
                } catch (BindException e) {
                    server = null; // 端口占用，试下一个
                } catch (IOException e) {
                    server = null;
                    break; // 非端口问题，不再重试
                }
            }
            // 所有端口均失败
            activePort = -1;
            String err = "启动失败：端口 " + startPort + "–" + (startPort + PORT_RANGE - 1)
                    + " 均被占用，请关闭其他占用端口的程序或更换端口后重试";
            fireFailed(err);
        } catch (Exception e) {
            // 其他未预期异常
            activePort = -1;
            fireFailed("启动失败：" + e.getMessage());
        }
        stopForeground(true);
        stopSelf();
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
        activePort = -1;
    }

    private String loadAsset(String name) {
        try (InputStream is = getAssets().open(name);
             BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            return "<h1>无法加载页面</h1>";
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "文件服务器", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String title, String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setOngoing(true)
                .build();
    }

    /** 获取本机局域网 IPv4 地址（优先 Wi-Fi 接口）。 */
    private String getLocalIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> en =
                    java.net.NetworkInterface.getNetworkInterfaces();
            String candidate = null;
            while (en.hasMoreElements()) {
                java.net.NetworkInterface nif = en.nextElement();
                if (nif.isLoopback() || !nif.isUp()) continue;
                String name = nif.getName();
                java.util.Enumeration<java.net.InetAddress> adds = nif.getInetAddresses();
                while (adds.hasMoreElements()) {
                    java.net.InetAddress a = adds.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        String host = a.getHostAddress();
                        if (name.startsWith("wlan") || name.startsWith("eth")) return host;
                        if (candidate == null) candidate = host;
                    }
                }
            }
            return candidate;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopServer();
        super.onDestroy();
    }
}
