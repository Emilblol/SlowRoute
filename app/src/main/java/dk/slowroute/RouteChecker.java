package dk.slowroute;

import org.osmdroid.util.GeoPoint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RouteChecker {
    private static final double CELL = 50.0;
    private static final double TOLERANCE = 6.0;
    private static final double MIN_PARALLEL_COS = 0.85;
    private static final double MIN_STRETCH = 25.0;

    private final double refLat;
    private final double kx;
    private final double ky;
    private final List<double[]> segments = new ArrayList<double[]>();
    private final Map<Long, List<Integer>> grid = new HashMap<Long, List<Integer>>();

    public RouteChecker(List<List<GeoPoint>> forbidden, double referenceLat) {
        refLat = referenceLat;
        ky = 110574.0;
        kx = 111320.0 * Math.cos(Math.toRadians(refLat));
        for (int l = 0; l < forbidden.size(); l++) {
            List<GeoPoint> line = forbidden.get(l);
            for (int i = 0; i + 1 < line.size(); i++) {
                addSegment(x(line.get(i)), y(line.get(i)), x(line.get(i + 1)), y(line.get(i + 1)));
            }
        }
    }

    private double x(GeoPoint p) {
        return p.getLongitude() * kx;
    }

    private double y(GeoPoint p) {
        return p.getLatitude() * ky;
    }

    private static long key(long cx, long cy) {
        return (cx << 32) ^ (cy & 0xffffffffL);
    }

    private void addSegment(double ax, double ay, double bx, double by) {
        int id = segments.size();
        segments.add(new double[] { ax, ay, bx, by });
        long minX = (long) Math.floor((Math.min(ax, bx) - TOLERANCE) / CELL);
        long maxX = (long) Math.floor((Math.max(ax, bx) + TOLERANCE) / CELL);
        long minY = (long) Math.floor((Math.min(ay, by) - TOLERANCE) / CELL);
        long maxY = (long) Math.floor((Math.max(ay, by) + TOLERANCE) / CELL);
        for (long cx = minX; cx <= maxX; cx++) {
            for (long cy = minY; cy <= maxY; cy++) {
                Long k = Long.valueOf(key(cx, cy));
                List<Integer> list = grid.get(k);
                if (list == null) {
                    list = new ArrayList<Integer>(2);
                    grid.put(k, list);
                }
                list.add(Integer.valueOf(id));
            }
        }
    }

    private boolean onForbidden(double ax, double ay, double bx, double by) {
        double mx = (ax + bx) / 2.0;
        double my = (ay + by) / 2.0;
        double dx = bx - ax;
        double dy = by - ay;
        double len = Math.sqrt(dx * dx + dy * dy);
        List<Integer> list = grid.get(Long.valueOf(key((long) Math.floor(mx / CELL), (long) Math.floor(my / CELL))));
        if (list == null) {
            return false;
        }
        for (int i = 0; i < list.size(); i++) {
            double[] s = segments.get(list.get(i).intValue());
            double sx = s[2] - s[0];
            double sy = s[3] - s[1];
            double slen2 = sx * sx + sy * sy;
            if (slen2 < 1e-6) {
                continue;
            }
            double t = ((mx - s[0]) * sx + (my - s[1]) * sy) / slen2;
            t = Math.max(0.0, Math.min(1.0, t));
            double px = s[0] + t * sx - mx;
            double py = s[1] + t * sy - my;
            if (px * px + py * py > TOLERANCE * TOLERANCE) {
                continue;
            }
            if (len < 3.0) {
                return true;
            }
            double cos = Math.abs(dx * sx + dy * sy) / (len * Math.sqrt(slen2));
            if (cos >= MIN_PARALLEL_COS) {
                return true;
            }
        }
        return false;
    }

    public List<GeoPoint> offendingPoints(List<GeoPoint> route) {
        List<GeoPoint> result = new ArrayList<GeoPoint>();
        double stretch = 0;
        int stretchStart = -1;
        for (int i = 0; i + 1 <= route.size(); i++) {
            boolean bad = false;
            double len = 0;
            if (i + 1 < route.size()) {
                GeoPoint a = route.get(i);
                GeoPoint b = route.get(i + 1);
                double ax = x(a);
                double ay = y(a);
                double bx = x(b);
                double by = y(b);
                len = Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay));
                bad = onForbidden(ax, ay, bx, by);
            }
            if (bad) {
                if (stretchStart < 0) {
                    stretchStart = i;
                    stretch = 0;
                }
                stretch += len;
            } else if (stretchStart >= 0) {
                if (stretch >= MIN_STRETCH) {
                    result.add(middle(route, stretchStart, i, stretch));
                }
                stretchStart = -1;
            }
        }
        return result;
    }

    private GeoPoint middle(List<GeoPoint> route, int from, int to, double total) {
        double half = total / 2.0;
        double walked = 0;
        for (int i = from; i < to; i++) {
            GeoPoint a = route.get(i);
            GeoPoint b = route.get(i + 1);
            double len = Geo.distance(a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude());
            if (walked + len >= half && len > 0) {
                double f = (half - walked) / len;
                return new GeoPoint(a.getLatitude() + f * (b.getLatitude() - a.getLatitude()),
                        a.getLongitude() + f * (b.getLongitude() - a.getLongitude()));
            }
            walked += len;
        }
        return route.get((from + to) / 2);
    }
}
