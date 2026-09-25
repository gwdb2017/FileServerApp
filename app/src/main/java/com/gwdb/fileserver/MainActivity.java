package com.gwdb.fileserver;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_MANAGE = 1001;
    private static final int REQ_STORAGE = 1002;
    private static final int REQ_NOTIFY  = 1003;

    private static final String PREFS = "fileserver_prefs";
    private static final String KEY_PORT = "port";
    private static final int MIN_PORT = 1024;
    private static final int MAX_PORT = 65535;

    private TextView statusText;
    private Button toggleBtn;
    private EditText portInput;
    private boolean running = false;
    private boolean pendingStart = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        toggleBtn  = findViewById(R.id.toggleBtn);
        portInput  = findViewById(R.id.portInput);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        portInput.setText(String.valueOf(prefs.getInt(KEY_PORT, ServerService.DEFAULT_PORT)));

        toggleBtn.setOnClickListener(v -> {
            if (running) stopServer();
            else startServer();
        });
        updateStatus();

        // 注册服务状态回调（代替广播）
        ServerService.setStatusCallback(new ServerService.StatusCallback() {
            @Override
            public void onServerStarted(int port) {
                if (isFinishing() || isDestroyed()) return;
                running = true;
                updateStatus();
            }
            @Override
            public void onServerFailed(String error) {
                if (isFinishing() || isDestroyed()) return;
                running = false;
                updateStatus();
                if (error != null) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("启动失败")
                            .setMessage(error)
                            .setPositiveButton("确定", null)
                            .show();
                }
            }
        });

        // 首次启动时主动申请权限
        pendingStart = false;
        requestPermissionsIfNeeded();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ServerService.setStatusCallback(null); // 防止内存泄漏
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 返回前台时同步服务实际运行状态
        running = ServerService.activePort > 0;
        updateStatus();
    }

    private void startServer() {
        int port = parsePort();
        if (port < 0) return; // parsePort 已经弹了提示
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_PORT, port).apply();
        if (!hasStoragePermission() || needNotifyPermission()) {
            pendingStart = true;
            requestPermissionsIfNeeded();
            return;
        }
        reallyStartService();
    }

    /**
     * 按顺序检查并申请：存储权限 → 通知权限。
     * pendingStart 决定是否在全部就绪后自动启动服务。
     */
    private void requestPermissionsIfNeeded() {
        if (!hasStoragePermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+ 无法弹系统对话框，必须先弹 AlertDialog 说明原因再跳设置
                showManageStorageDialog();
            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQ_STORAGE);
            }
            return;
        }
        // 存储已授予，再检查通知权限（Android 13+）
        if (needNotifyPermission()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
            return;
        }
        // 全部权限就绪
        onPermissionsReady();
    }

    /** Android 11+ 的「所有文件访问」权限：先弹说明对话框，用户确认后再跳设置页。 */
    private void showManageStorageDialog() {
        new AlertDialog.Builder(this)
                .setTitle("需要存储权限")
                .setMessage("本应用需要访问手机共享存储中的文件，请在下一步的设页界面中开启「所有文件访问」权限。")
                .setCancelable(false)
                .setPositiveButton("去设置", (d, w) -> openAllFilesAccessSettings())
                .setNegativeButton("取消", (d, w) -> {
                    pendingStart = false;
                    Toast.makeText(this, "未授予存储权限，无法启动服务", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void openAllFilesAccessSettings() {
        // 优先尝试直接跳转到本 App 的权限设置页
        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(intent, REQ_MANAGE);
            return;
        }
        // 降级：打开「所有文件访问」总列表页
        intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(intent, REQ_MANAGE);
            return;
        }
        // 最终降级：打开本 App 的详情设置页
        intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        startActivityForResult(intent, REQ_MANAGE);
    }

    private boolean needNotifyPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
    }

    private void onPermissionsReady() {
        if (pendingStart) {
            pendingStart = false;
            reallyStartService();
        }
    }

    private void reallyStartService() {
        int port = parsePort();
        if (port < 0) return;
        running = true;
        updateStatus(); // 立即显示「启动中...」状态
        Intent i = new Intent(this, ServerService.class);
        i.setAction(ServerService.ACTION_START);
        i.putExtra(ServerService.EXTRA_START_PORT, port);
        ContextCompat.startForegroundService(this, i);
    }

    /**
     * 解析并校验端口输入。返回 -1 表示不合法（已弹提示）。
     */
    private int parsePort() {
        String s = portInput.getText().toString().trim();
        try {
            int p = Integer.parseInt(s);
            if (p < MIN_PORT || p > MAX_PORT) {
                Toast.makeText(this, "端口范围应为 " + MIN_PORT + "–" + MAX_PORT, Toast.LENGTH_SHORT).show();
                return -1;
            }
            return p;
        } catch (NumberFormatException e) {
            Toast.makeText(this, "请输入有数字的端口号", Toast.LENGTH_SHORT).show();
            return -1;
        }
    }

    private void stopServer() {
        Intent i = new Intent(this, ServerService.class);
        i.setAction(ServerService.ACTION_STOP);
        startService(i);
        running = false;
        updateStatus();
    }

    private void updateStatus() {
        // 读取 SharedPreferences 中保存的偏好端口，不重复调用 parsePort()
        int preferred = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_PORT, ServerService.DEFAULT_PORT);

        if (!running) {
            // 已停止
            portInput.setEnabled(true);
            toggleBtn.setEnabled(true);
            statusText.setText("服务未运行。\n\n点击「启动服务器」开始；首次需授予存储权限。");
            toggleBtn.setText("启动服务器");

        } else if (ServerService.activePort > 0) {
            // 已启动
            int port = ServerService.activePort;
            portInput.setEnabled(false);
            toggleBtn.setEnabled(true);
            String ip  = getLocalIp();
            String url = (ip != null ? ip : "<手机IP>") + ":" + port;
            String note = (port != preferred)
                    ? "\n\n（偏好端口 " + preferred + " 被占用，已自动切换为 " + port + "）"
                    : "";
            statusText.setText("服务器已启动！\n\n在手机或同一 Wi-Fi 下的电脑浏览器打开：\n\nhttp://" + url + note
                    + "\n\n（手机与访问设备需处于同一局域网）");
            toggleBtn.setText("停止服务器");

        } else {
            // 启动中（广播尚未到束）
            portInput.setEnabled(false);
            toggleBtn.setEnabled(false);
            statusText.setText("正在启动服务器...");
            toggleBtn.setText("正在启动...");
        }
    }

    // ---- 权限 ----

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MANAGE) {
            if (hasStoragePermission()) {
                requestPermissionsIfNeeded(); // 继续检查通知权限
            } else {
                pendingStart = false;
                Toast.makeText(this, "未授予存储权限，无法启动", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestPermissionsIfNeeded(); // 继续检查通知权限
            } else {
                pendingStart = false;
                Toast.makeText(this, "未授予存储权限", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_NOTIFY) {
            // 无论是否授予通知权限都允许启动（服务通知非强制）
            onPermissionsReady();
        }
    }

    // ---- 获取本机 IP ----

    private String getLocalIp() {
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                NetworkInterface nif = en.nextElement();
                if (nif.isLoopback() || !nif.isUp()) continue;
                Enumeration<InetAddress> adds = nif.getInetAddresses();
                while (adds.hasMoreElements()) {
                    InetAddress a = adds.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String name = nif.getName();
                        String host = a.getHostAddress();
                        if (name.startsWith("wlan") || name.startsWith("eth")) return host;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
