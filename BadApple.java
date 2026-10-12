import javax.sound.sampled.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Bad Apple 终端字符播放器 —— 单文件、零依赖、直接播放 mp4
 *
 * 原理：
 *   1. 启动一个 ffmpeg 子进程，负责：
 *      - 解码视频 -> 缩放 -> 灰度 rawvideo -> 管道 stdout
 *      - 同时用另一个 ffmpeg 子进程播放音频
 *   2. Java 从管道读原始灰度像素，映射成 ASCII，刷新终端
 *
 * 前置条件：
 *   - 系统 PATH 中有 ffmpeg
 *   - 当前目录有 bad_apple.mp4
 *
 * PowerShell 运行：
 *   javac BadApple.java
 *   java BadApple
 *
 * 建议用 Windows Terminal，并先执行 chcp 65001
 */
public class BadApple {

    // ============ 可调参数 ============
    private static final String VIDEO = "bad_apple.mp4";
    private static final int WIDTH = 200;        // 输出字符宽度
    private static final int FPS = 60;           // 播放帧率
    private static final String RAMP = " .:-=+*#%@"; // 暗 -> 亮
    // ==================================

    public static void main(String[] args) throws Exception {
        File video = new File(VIDEO);
        if (!video.exists()) {
            System.err.println("找不到 " + VIDEO);
            return;
        }
        if (!ffmpegAvailable()) {
            System.err.println("找不到 ffmpeg，请确认它在 PATH 中。");
            return;
        }

        // 根据宽度和视频宽高比推算字符行数（假设字符高宽比约 2:1）
        // 先探测视频尺寸
        int[] size = probeSize(video);
        int vw = size[0], vh = size[1];
        int outH = Math.max(1, (int) Math.round((double) WIDTH * vh / vw * 0.5));

        System.out.println("视频 " + vw + "x" + vh + " -> 字符 " + WIDTH + "x" + outH);

        // ---------- 启动音频----------
        Process audioProc = new ProcessBuilder(
                "ffmpeg", "-loglevel", "quiet",
                "-i", VIDEO,
                "-vn", "-f", "s16le", "-ar", "44100", "-ac", "2", "-"
        ).redirectErrorStream(false).start();

        Thread audioThread = new Thread(() -> {
            try (AudioInputStream ais = new AudioInputStream(
                    audioProc.getInputStream(),
                    new AudioFormat(44100, 16, 2, true, false),
                    AudioSystem.NOT_SPECIFIED)) {
                SourceDataLine line = AudioSystem.getSourceDataLine(
                        new AudioFormat(44100, 16, 2, true, false));
                line.open();
                line.start();
                byte[] buf = new byte[8192];
                int n;
                while ((n = ais.read(buf)) > 0) {
                    line.write(buf, 0, n);
                }
                line.drain();
                line.stop();
                line.close();
            } catch (Exception ignored) {
            }
        });
        audioThread.setDaemon(true);
        audioThread.start();

        // ---------- 启动视频解码 ----------
        // 输出 raw 灰度：每帧 WIDTH x outH 字节
        Process videoProc = new ProcessBuilder(
                "ffmpeg", "-loglevel", "quiet",
                "-i", VIDEO,
                "-vf", "fps=" + FPS + ",scale=" + WIDTH + ":" + outH + ":flags=neighbor,format=gray",
                "-f", "rawvideo", "-pix_fmt", "gray", "-"
        ).redirectErrorStream(false).start();

        // 预分配每帧字节数
        int frameBytes = WIDTH * outH;
        byte[] frame = new byte[frameBytes];

        // ---------- 终端 ----------
        System.out.print("\033[2J");      // 清屏
        System.out.print("\033[?25l");    // 隐藏光标
        System.out.flush();

        // 减少闪烁
        OutputStream out = new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16);
        StringBuilder sb = new StringBuilder((WIDTH + 1) * outH + 16);

        long frameDurationNs = 1_000_000_000L / FPS;
        long start = System.nanoTime();
        int frameIndex = 0;

        try (InputStream in = new BufferedInputStream(videoProc.getInputStream(), 1 << 16)) {
            while (true) {
                int read = readFully(in, frame);
                if (read < frameBytes) break; // 视频结束

                sb.setLength(0);
                sb.append("\033[H"); // 光标回左上角
                for (int y = 0; y < outH; y++) {
                    int row = y * WIDTH;
                    for (int x = 0; x < WIDTH; x++) {
                        int v = frame[row + x] & 0xFF;
                        sb.append(RAMP.charAt(v * (RAMP.length() - 1) / 255));
                    }
                    if (y < outH - 1) sb.append('\n');
                }
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();

                // 控帧
                frameIndex++;
                long target = start + (long) frameIndex * frameDurationNs;
                long sleep = target - System.nanoTime();
                if (sleep > 0) {
                    Thread.sleep(sleep / 1_000_000, (int) (sleep % 1_000_000));
                }
            }
        }

        // ---------- 收尾 ----------
        System.out.print("\033[?25h"); // 恢复光标
        System.out.println();
        videoProc.waitFor();
        audioProc.destroy();
    }

    /** 从流里尽量读满 buffer，返回实际读到的字节数 */
    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    /** 用 ffprobe 探测视频宽高 */
    private static int[] probeSize(File video) throws Exception {
        Process p = new ProcessBuilder(
                "ffprobe", "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height",
                "-of", "csv=s=x:p=0",
                video.getAbsolutePath()
        ).redirectErrorStream(true).start();

        String line;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            line = r.readLine();
        }
        p.waitFor();

        if (line == null || !line.contains("x")) return new int[]{1280, 720};
        String[] parts = line.trim().split("x");
        return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }

    /** 检查 ffmpeg 是否可用 */
    private static boolean ffmpegAvailable() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version")
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}