package dbg;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * 锤子日历调试日志器。
 *
 * 设计目标：让 AI 能拿到可复现的结构化调试信息。
 *  - 双写：logcat（实时看）+ 文件（adb pull 离线分析）
 *  - 环形缓冲：内存保留最近 N 条，崩溃时也能回看
 *  - 文件轮转：单文件上限 512KB，超限后改名 .1 重新开始
 *  - 零三方依赖：仅用 Android framework + JDK，避免与宿主 APK 冲突
 *
 * 日志文件位置：<app filesDir>/cal_dbg.log
 */
public final class Logger {

    public static final String TAG = "CalDbg";

    /** 内存环形缓冲条数 */
    private static final int MEM_CAPACITY = 512;
    /** 单文件大小上限 */
    private static final long MAX_FILE_BYTES = 512L * 1024L;

    /** 总开关，默认开 */
    private static volatile boolean enabled = true;
    /** 是否同时写文件，默认开 */
    private static volatile boolean fileEnabled = true;

    private static final Deque<String> MEM = new ArrayDeque<>(MEM_CAPACITY);

    private static File logFile;
    private static BufferedWriter writer;
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private Logger() {
    }

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    /**
     * 在 Application.onCreate 里调用一次。
     *
     * @param context 任意 Context（用 getApplicationContext 兜底）
     */
    public static void init(Context context) {
        if (context == null) {
            return;
        }
        try {
            Context app = context.getApplicationContext();
            if (app == null) {
                app = context;
            }
            File dir = app.getFilesDir();
            if (dir == null) {
                return;
            }
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            logFile = new File(dir, "cal_dbg.log");
            //noinspection ResultOfMethodCallIgnored
            logFile.getParentFile().mkdirs();
            openWriter();
            Log.i(TAG, "logger init, file=" + logFile.getAbsolutePath()
                    + " android=" + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT);
        } catch (Throwable t) {
            // 日志组件绝不能拖垮宿主
            Log.e(TAG, "logger init failed", t);
        }
    }

    private static synchronized void openWriter() {
        try {
            if (logFile == null) {
                return;
            }
            if (writer != null) {
                writer.flush();
                writer.close();
                writer = null;
            }
            if (logFile.exists() && logFile.length() > MAX_FILE_BYTES) {
                File old = new File(logFile.getAbsolutePath() + ".1");
                if (old.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    old.delete();
                }
                //noinspection ResultOfMethodCallIgnored
                logFile.renameTo(old);
            }
            writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(logFile, true), "UTF-8"));
        } catch (Throwable t) {
            writer = null;
            Log.e(TAG, "open writer failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 开关
    // ------------------------------------------------------------------

    public static void setEnabled(boolean on) {
        enabled = on;
        Log.i(TAG, "enabled=" + on);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setFileEnabled(boolean on) {
        fileEnabled = on;
    }

    /** 日志文件路径，未初始化时返回 null */
    public static String getLogFilePath() {
        return logFile == null ? null : logFile.getAbsolutePath();
    }

    // ------------------------------------------------------------------
    // 写日志
    // ------------------------------------------------------------------

    public static void i(String tag, String msg) {
        write('I', tag, msg, null);
    }

    public static void w(String tag, String msg) {
        write('W', tag, msg, null);
    }

    public static void e(String tag, String msg, Throwable t) {
        write('E', tag, msg, t);
    }

    public static void d(String tag, String msg) {
        write('D', tag, msg, null);
    }

    private static void write(char level, String tag, String msg, Throwable t) {
        if (!enabled) {
            return;
        }
        String line = format(level, tag, msg, t);
        // logcat
        try {
            switch (level) {
                case 'E':
                    Log.e(TAG, line, t);
                    break;
                case 'W':
                    Log.w(TAG, line);
                    break;
                case 'D':
                    Log.d(TAG, line);
                    break;
                default:
                    Log.i(TAG, line);
                    break;
            }
        } catch (Throwable ignored) {
        }
        // 内存环形缓冲
        pushMemory(line);
        // 文件
        if (fileEnabled) {
            writeFile(line);
        }
    }

    private static String format(char level, String tag, String msg, Throwable t) {
        String time;
        try {
            time = FMT.format(new Date());
        } catch (Throwable e) {
            time = "?";
        }
        StringBuilder sb = new StringBuilder(160);
        sb.append(time).append(' ').append(level).append('/').append(TAG);
        sb.append(" [").append(tag == null ? "-" : tag).append("] ");
        sb.append(msg == null ? "" : msg);
        if (t != null) {
            sb.append(" | ex=");
            sb.append(t.getClass().getName());
            if (t.getMessage() != null) {
                sb.append(": ").append(t.getMessage());
            }
            // 只带前 3 层栈，够定位且不刷屏
            StackTraceElement[] st = t.getStackTrace();
            if (st != null) {
                int n = Math.min(st.length, 3);
                for (int i = 0; i < n; i++) {
                    sb.append(" <- ").append(st[i].toString());
                }
            }
        }
        return sb.toString();
    }

    private static synchronized void pushMemory(String line) {
        if (MEM.size() >= MEM_CAPACITY) {
            MEM.pollFirst();
        }
        MEM.addLast(line);
    }

    private static synchronized void writeFile(String line) {
        if (writer == null) {
            openWriter();
            if (writer == null) {
                return;
            }
        }
        try {
            writer.write(line);
            writer.write('\n');
            writer.flush();
        } catch (Throwable t) {
            // 写失败（磁盘满/权限）就重建一次
            try {
                writer = null;
                openWriter();
                if (writer != null) {
                    writer.write(line);
                    writer.write('\n');
                    writer.flush();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // 供调试/AI 读取
    // ------------------------------------------------------------------

    /** 取内存中最近 N 条日志（倒序，最新在前） */
    public static String dumpMemory(int n) {
        synchronized (Logger.class) {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            java.util.Iterator<String> it = MEM.descendingIterator();
            while (it.hasNext()) {
                if (i++ >= n) {
                    break;
                }
                sb.append(it.next()).append('\n');
            }
            return sb.toString();
        }
    }

    /** 读取日志文件全部内容（截断保护） */
    public static String readLogFile(int maxChars) {
        if (logFile == null || !logFile.exists()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(logFile), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                    if (sb.length() > maxChars) {
                        sb.append("...[truncated]\n");
                        break;
                    }
                }
            } finally {
                r.close();
            }
        } catch (IOException e) {
            return sb.toString();
        }
        return sb.toString();
    }

    /** 清空日志文件（保留文件，清空内容） */
    public static void clearLogFile() {
        synchronized (Logger.class) {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Throwable ignored) {
                }
                writer = null;
            }
            if (logFile != null && logFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                logFile.delete();
            }
            openWriter();
        }
    }
}
