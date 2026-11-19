package dk.slowroute;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

public final class OfflineGraph {
    public static final int MAGIC = 0x534C5254;
    public static final int VERSION = 1;
    private static final int HEADER_INTS = 14;
    private static final double MAX_SNAP_METERS = 2000.0;
    private static final double SNAP_SLACK_METERS = 60.0;
    private static final float SNAP_PENALTY = 4f;
    private static final int SNAP_CANDIDATES = 8;
    private static final double SPEED_MS = 30.0 / 3.6;

    public static final class Path {
        public final double[] lats;
        public final double[] lons;
        public final double seconds;
        public final int explored;

        Path(double[] lats, double[] lons, double seconds, int explored) {
            this.lats = lats;
            this.lons = lons;
            this.seconds = seconds;
            this.explored = explored;
        }
    }

    private final IntBuffer ib;
    private final int n;
    private final int a;
    private final int u;
    private final int g;
    private final int rows;
    private final int cols;
    private final int lat0;
    private final int lon0;
    private final int dlat;
    private final int dlon;
    public final int builtDay;
    private final int latAt;
    private final int lonAt;
    private final int adjOffAt;
    private final int adjAt;
    private final int eaAt;
    private final int ebAt;
    private final int costAt;
    private final int gsAt;
    private final int gLatAt;
    private final int gLonAt;
    private final int gridAt;

    private float[] best;
    private int[] via;
    private byte[] state;
    private int[] touched = new int[1 << 16];
    private int touchedCount;

    private OfflineGraph(IntBuffer ib) throws IOException {
        this.ib = ib;
        if (ib.get(0) != MAGIC || ib.get(1) != VERSION) {
            throw new IOException("road file has the wrong format");
        }
        n = ib.get(2);
        a = ib.get(3);
        u = ib.get(4);
        g = ib.get(5);
        rows = ib.get(6);
        cols = ib.get(7);
        lat0 = ib.get(8);
        lon0 = ib.get(9);
        dlat = ib.get(10);
        dlon = ib.get(11);
        builtDay = ib.get(12);
        latAt = HEADER_INTS;
        lonAt = latAt + n;
        adjOffAt = lonAt + n;
        adjAt = adjOffAt + n + 1;
        eaAt = adjAt + a;
        ebAt = eaAt + u;
        costAt = ebAt + u;
        gsAt = costAt + u;
        gLatAt = gsAt + u + 1;
        gLonAt = gLatAt + g;
        gridAt = gLonAt + g;
        long expected = (long) gridAt + (long) rows * cols + 1;
        if (expected != ib.capacity()) {
            throw new IOException("road file is damaged");
        }
    }

    public static OfflineGraph open(File file) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            FileChannel ch = raf.getChannel();
            MappedByteBuffer buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            buf.order(ByteOrder.LITTLE_ENDIAN);
            return new OfflineGraph(buf.asIntBuffer());
        } finally {
            raf.close();
        }
    }

    public int nodeCount() {
        return n;
    }

    private double nodeLat(int i) {
        return ib.get(latAt + i) / 1e6;
    }

    private double nodeLon(int i) {
        return ib.get(lonAt + i) / 1e6;
    }

    private boolean hasExit(int i) {
        return ib.get(adjOffAt + i + 1) > ib.get(adjOffAt + i);
    }

    private int[] snap(double lat, double lon) {
        int[] found = new int[SNAP_CANDIDATES];
        double[] dist = new double[SNAP_CANDIDATES];
        int count = 0;
        int r0 = (int) Math.floor((lat * 1e6 - lat0) / dlat);
        int c0 = (int) Math.floor((lon * 1e6 - lon0) / dlon);
        for (int radius = 1; radius <= 2; radius++) {
            count = 0;
            for (int r = r0 - radius; r <= r0 + radius; r++) {
                if (r < 0 || r >= rows) {
                    continue;
                }
                for (int c = c0 - radius; c <= c0 + radius; c++) {
                    if (c < 0 || c >= cols) {
                        continue;
                    }
                    int cell = r * cols + c;
                    int from = ib.get(gridAt + cell);
                    int to = ib.get(gridAt + cell + 1);
                    for (int i = from; i < to; i++) {
                        if (!hasExit(i)) {
                            continue;
                        }
                        double d = Geo.distance(lat, lon, nodeLat(i), nodeLon(i));
                        if (d > MAX_SNAP_METERS) {
                            continue;
                        }
                        if (count < SNAP_CANDIDATES || d < dist[count - 1]) {
                            int pos = count < SNAP_CANDIDATES ? count : count - 1;
                            while (pos > 0 && dist[pos - 1] > d) {
                                found[pos] = found[pos - 1];
                                dist[pos] = dist[pos - 1];
                                pos--;
                            }
                            found[pos] = i;
                            dist[pos] = d;
                            if (count < SNAP_CANDIDATES) {
                                count++;
                            }
                        }
                    }
                }
            }
            if (count > 0) {
                break;
            }
        }
        int keep = 0;
        while (keep < count && dist[keep] <= dist[0] + SNAP_SLACK_METERS) {
            keep++;
        }
        return Arrays.copyOf(found, keep);
    }

    private void touch(int i) {
        if (state[i] == 0) {
            if (touchedCount == touched.length) {
                touched = Arrays.copyOf(touched, touchedCount * 2);
            }
            touched[touchedCount++] = i;
            state[i] = 1;
        }
    }

    private void reset() {
        for (int i = 0; i < touchedCount; i++) {
            int k = touched[i];
            state[k] = 0;
            best[k] = Float.MAX_VALUE;
            via[k] = -1;
        }
        touchedCount = 0;
    }

    public synchronized Path route(double fromLat, double fromLon, double toLat, double toLon) {
        int[] starts = snap(fromLat, fromLon);
        int[] goals = snap(toLat, toLon);
        if (starts.length == 0 || goals.length == 0) {
            return null;
        }
        if (best == null) {
            best = new float[n + 1];
            via = new int[n + 1];
            state = new byte[n + 1];
            Arrays.fill(best, Float.MAX_VALUE);
            Arrays.fill(via, -1);
        }
        int target = n;
        float[] goalCost = new float[goals.length];
        for (int i = 0; i < goals.length; i++) {
            goalCost[i] = SNAP_PENALTY * 10f * (float) Geo.distance(toLat, toLon, nodeLat(goals[i]), nodeLon(goals[i]));
        }
        double ky = 1.10574;
        double kx = 1.11320 * Math.cos(Math.toRadians(Math.min(89.0, Math.max(Math.abs(fromLat), Math.abs(toLat)) + 1.0)));
        int tLat = (int) Math.round(toLat * 1e6);
        int tLon = (int) Math.round(toLon * 1e6);
        MinHeap heap = new MinHeap(1 << 14);
        for (int i = 0; i < starts.length; i++) {
            int s = starts[i];
            float cost = SNAP_PENALTY * 10f * (float) Geo.distance(fromLat, fromLon, nodeLat(s), nodeLon(s));
            touch(s);
            if (cost < best[s]) {
                best[s] = cost;
                via[s] = -1;
                heap.push(s, cost + heuristic(s, tLat, tLon, kx, ky));
            }
        }
        touch(target);
        int explored = 0;
        while (!heap.isEmpty()) {
            int x = heap.pop();
            if (state[x] == 2) {
                continue;
            }
            state[x] = 2;
            if (x == target) {
                break;
            }
            explored++;
            float bx = best[x];
            for (int i = 0; i < goals.length; i++) {
                if (goals[i] == x) {
                    float ng = bx + goalCost[i];
                    if (ng < best[target]) {
                        best[target] = ng;
                        via[target] = x;
                        heap.push(target, ng);
                    }
                }
            }
            int from = ib.get(adjOffAt + x);
            int to = ib.get(adjOffAt + x + 1);
            for (int k = from; k < to; k++) {
                int v = ib.get(adjAt + k);
                int e = v >>> 1;
                int y = (v & 1) == 0 ? ib.get(ebAt + e) : ib.get(eaAt + e);
                touch(y);
                if (state[y] == 2) {
                    continue;
                }
                float ng = bx + ib.get(costAt + e);
                if (ng < best[y]) {
                    best[y] = ng;
                    via[y] = v;
                    heap.push(y, ng + heuristic(y, tLat, tLon, kx, ky));
                }
            }
        }
        if (state[target] != 2) {
            reset();
            return null;
        }
        Path path = build(fromLat, fromLon, toLat, toLon, via[target], explored);
        reset();
        return path;
    }

    private float heuristic(int i, int tLat, int tLon, double kx, double ky) {
        double dx = (ib.get(lonAt + i) - tLon) * kx;
        double dy = (ib.get(latAt + i) - tLat) * ky;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private Path build(double fromLat, double fromLon, double toLat, double toLon, int last, int explored) {
        int edges = 0;
        int points = 0;
        int x = last;
        while (via[x] != -1) {
            int v = via[x];
            int e = v >>> 1;
            edges++;
            points += ib.get(gsAt + e + 1) - ib.get(gsAt + e) + 1;
            x = (v & 1) == 0 ? ib.get(eaAt + e) : ib.get(ebAt + e);
        }
        int[] chain = new int[edges];
        x = last;
        int pos = edges;
        while (via[x] != -1) {
            int v = via[x];
            chain[--pos] = v;
            int e = v >>> 1;
            x = (v & 1) == 0 ? ib.get(eaAt + e) : ib.get(ebAt + e);
        }
        int total = points + 3;
        double[] lats = new double[total];
        double[] lons = new double[total];
        int k = 0;
        lats[k] = fromLat;
        lons[k++] = fromLon;
        lats[k] = nodeLat(x);
        lons[k++] = nodeLon(x);
        long cost = 0;
        for (int i = 0; i < edges; i++) {
            int v = chain[i];
            int e = v >>> 1;
            boolean rev = (v & 1) != 0;
            cost += ib.get(costAt + e);
            int gs = ib.get(gsAt + e);
            int ge = ib.get(gsAt + e + 1);
            if (!rev) {
                for (int j = gs; j < ge; j++) {
                    lats[k] = ib.get(gLatAt + j) / 1e6;
                    lons[k++] = ib.get(gLonAt + j) / 1e6;
                }
            } else {
                for (int j = ge - 1; j >= gs; j--) {
                    lats[k] = ib.get(gLatAt + j) / 1e6;
                    lons[k++] = ib.get(gLonAt + j) / 1e6;
                }
            }
            int y = rev ? ib.get(eaAt + e) : ib.get(ebAt + e);
            lats[k] = nodeLat(y);
            lons[k++] = nodeLon(y);
        }
        lats[k] = toLat;
        lons[k++] = toLon;
        return new Path(Arrays.copyOf(lats, k), Arrays.copyOf(lons, k), cost / 10.0 / SPEED_MS, explored);
    }
}
