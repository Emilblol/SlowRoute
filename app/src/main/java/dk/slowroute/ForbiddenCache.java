package dk.slowroute;

import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class ForbiddenCache {
    private static final int VERSION = 1;
    private static final long MAX_AGE_MS = 14L * 24L * 60L * 60L * 1000L;
    private static final String FILE_NAME = "forbidden_se.bin";

    private static volatile ForbiddenCache instance;
    private static volatile boolean loading;

    private final float[][] lines;
    private final float[][] boxes;
    private final long created;

    private ForbiddenCache(float[][] lines, long created) {
        this.lines = lines;
        this.created = created;
        boxes = new float[lines.length][];
        for (int i = 0; i < lines.length; i++) {
            float[] l = lines[i];
            float minLat = Float.MAX_VALUE;
            float maxLat = -Float.MAX_VALUE;
            float minLon = Float.MAX_VALUE;
            float maxLon = -Float.MAX_VALUE;
            for (int j = 0; j + 1 < l.length; j += 2) {
                minLat = Math.min(minLat, l[j]);
                maxLat = Math.max(maxLat, l[j]);
                minLon = Math.min(minLon, l[j + 1]);
                maxLon = Math.max(maxLon, l[j + 1]);
            }
            boxes[i] = new float[] { minLat, maxLat, minLon, maxLon };
        }
    }

    public static ForbiddenCache get() {
        return instance;
    }

    public static void prepare(final File dir, final Runnable onReady) {
        if (instance != null || loading) {
            return;
        }
        loading = true;
        new Thread(new Runnable() {
            public void run() {
                File file = new File(dir, FILE_NAME);
                ForbiddenCache cache = null;
                try {
                    cache = read(file);
                } catch (IOException e) {
                    cache = null;
                }
                if (cache != null) {
                    instance = cache;
                    if (onReady != null) {
                        onReady.run();
                    }
                }
                if (cache == null || System.currentTimeMillis() - cache.created > MAX_AGE_MS) {
                    try {
                        ForbiddenCache fresh = fromLines(OsmData.fetchForbiddenSweden(), System.currentTimeMillis());
                        write(file, fresh);
                        instance = fresh;
                        if (onReady != null) {
                            onReady.run();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                loading = false;
            }
        }).start();
    }

    private static ForbiddenCache fromLines(List<List<GeoPoint>> source, long created) {
        float[][] lines = new float[source.size()][];
        for (int i = 0; i < source.size(); i++) {
            List<GeoPoint> line = source.get(i);
            float[] l = new float[line.size() * 2];
            for (int j = 0; j < line.size(); j++) {
                l[j * 2] = (float) line.get(j).getLatitude();
                l[j * 2 + 1] = (float) line.get(j).getLongitude();
            }
            lines[i] = l;
        }
        return new ForbiddenCache(lines, created);
    }

    private static ForbiddenCache read(File file) throws IOException {
        if (!file.exists()) {
            return null;
        }
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 65536));
        try {
            if (in.readInt() != VERSION) {
                return null;
            }
            long created = in.readLong();
            int count = in.readInt();
            float[][] lines = new float[count][];
            for (int i = 0; i < count; i++) {
                int n = in.readInt();
                float[] l = new float[n];
                for (int j = 0; j < n; j++) {
                    l[j] = in.readFloat();
                }
                lines[i] = l;
            }
            return new ForbiddenCache(lines, created);
        } finally {
            in.close();
        }
    }

    private static void write(File file, ForbiddenCache cache) throws IOException {
        File tmp = new File(file.getPath() + ".tmp");
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 65536));
        try {
            out.writeInt(VERSION);
            out.writeLong(cache.created);
            out.writeInt(cache.lines.length);
            for (int i = 0; i < cache.lines.length; i++) {
                float[] l = cache.lines[i];
                out.writeInt(l.length);
                for (int j = 0; j < l.length; j++) {
                    out.writeFloat(l[j]);
                }
            }
        } finally {
            out.close();
        }
        if (!tmp.renameTo(file)) {
            file.delete();
            tmp.renameTo(file);
        }
    }

    public List<List<GeoPoint>> query(BoundingBox box) {
        float north = (float) box.getLatNorth();
        float south = (float) box.getLatSouth();
        float east = (float) box.getLonEast();
        float west = (float) box.getLonWest();
        List<List<GeoPoint>> result = new ArrayList<List<GeoPoint>>();
        for (int i = 0; i < lines.length; i++) {
            float[] b = boxes[i];
            if (b[1] < south || b[0] > north || b[3] < west || b[2] > east) {
                continue;
            }
            float[] l = lines[i];
            List<GeoPoint> points = new ArrayList<GeoPoint>(l.length / 2);
            for (int j = 0; j + 1 < l.length; j += 2) {
                points.add(new GeoPoint(l[j], l[j + 1]));
            }
            result.add(points);
        }
        return result;
    }
}
