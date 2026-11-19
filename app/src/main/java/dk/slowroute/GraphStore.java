package dk.slowroute;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

public final class GraphStore {
    public static final String URL_STRING = "https://github.com/Emilblol/SlowRoute/releases/download/roads/sweden.bin.gz";
    private static final String FILE_NAME = "sweden.bin";
    private static final long MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L;

    public interface Listener {
        void onProgress(int percent);

        void onReady(OfflineGraph graph);

        void onFailed(String message);
    }

    private static volatile OfflineGraph graph;
    private static volatile boolean busy;

    private GraphStore() {
    }

    public static OfflineGraph get() {
        return graph;
    }

    public static boolean isDownloaded(File dir) {
        return new File(dir, FILE_NAME).exists();
    }

    public static boolean isBusy() {
        return busy;
    }

    public static void open(final File dir, final Listener listener) {
        if (graph != null || busy) {
            return;
        }
        busy = true;
        new Thread(new Runnable() {
            public void run() {
                File file = new File(dir, FILE_NAME);
                try {
                    graph = OfflineGraph.open(file);
                    busy = false;
                    listener.onReady(graph);
                    if (System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS) {
                        refresh(dir, null);
                    }
                } catch (Throwable e) {
                    busy = false;
                    file.delete();
                    listener.onFailed("saved road map was damaged, please download it again");
                }
            }
        }).start();
    }

    public static void download(final File dir, final Listener listener) {
        if (busy) {
            return;
        }
        busy = true;
        new Thread(new Runnable() {
            public void run() {
                refresh(dir, listener);
            }
        }).start();
    }

    private static void refresh(File dir, Listener listener) {
        busy = true;
        File file = new File(dir, FILE_NAME);
        File tmp = new File(dir, FILE_NAME + ".part");
        try {
            fetch(tmp, listener);
            OfflineGraph.open(tmp);
            if (graph == null) {
                if (!tmp.renameTo(file)) {
                    throw new IOException("could not save the road map");
                }
                graph = OfflineGraph.open(file);
            } else {
                File next = new File(dir, FILE_NAME + ".next");
                tmp.renameTo(next);
                file.delete();
                next.renameTo(file);
            }
            busy = false;
            if (listener != null) {
                listener.onReady(graph);
            }
        } catch (Throwable e) {
            tmp.delete();
            busy = false;
            if (listener != null) {
                listener.onFailed(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }
    }

    private static void fetch(File target, final Listener listener) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(URL_STRING).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "SlowRoute");
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                throw new IOException("road map download answered " + code);
            }
            final long total = c.getContentLength();
            InputStream raw = c.getInputStream();
            InputStream counted = new FilterInputStream(raw) {
                private long read;
                private int lastPercent = -1;

                private void count(long n) {
                    if (n <= 0 || total <= 0 || listener == null) {
                        return;
                    }
                    read += n;
                    int percent = (int) Math.min(99, read * 100 / total);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        listener.onProgress(percent);
                    }
                }

                @Override
                public int read() throws IOException {
                    int b = super.read();
                    if (b >= 0) {
                        count(1);
                    }
                    return b;
                }

                @Override
                public int read(byte[] buffer, int off, int len) throws IOException {
                    int n = super.read(buffer, off, len);
                    count(n);
                    return n;
                }
            };
            InputStream in = new GZIPInputStream(counted, 65536);
            OutputStream out = new BufferedOutputStream(new FileOutputStream(target), 65536);
            try {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                }
            } finally {
                out.close();
                in.close();
            }
        } finally {
            c.disconnect();
        }
    }
}
