package dk.slowroute;

import org.osmdroid.util.GeoPoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class RoadGraph {
    private static final double SPEED_MS = RoadRules.MAX_SPEED_KMH / 3.6;
    private static final double MAX_SNAP_METERS = 2000.0;
    private static final double SNAP_SLACK_METERS = 60.0;
    private static final float SNAP_PENALTY = 4f;
    private static final int SNAP_CANDIDATES = 8;

    private final LongIntMap index = new LongIntMap(1 << 15);
    private double[] lat = new double[1 << 15];
    private double[] lon = new double[1 << 15];
    private int count;
    private List<Way> ways = new ArrayList<Way>();
    private int[] offsets;
    private int[] targets;
    private float[] weights;
    private int[] inDegree;

    private static final class Way {
        final long[] nodes;
        final int direction;
        final float slowdown;

        Way(long[] nodes, int direction, float slowdown) {
            this.nodes = nodes;
            this.direction = direction;
            this.slowdown = slowdown;
        }
    }

    public void addNode(long id, double latitude, double longitude) {
        if (index.get(id) >= 0) {
            return;
        }
        if (count == lat.length) {
            lat = Arrays.copyOf(lat, count * 2);
            lon = Arrays.copyOf(lon, count * 2);
        }
        lat[count] = latitude;
        lon[count] = longitude;
        index.put(id, count);
        count++;
    }

    public void addWay(long[] nodes, int direction, float slowdown) {
        if (nodes != null && nodes.length > 1) {
            ways.add(new Way(nodes, direction, slowdown));
        }
    }

    public int nodeCount() {
        return count;
    }

    public void build() {
        int[] outDegree = new int[count];
        inDegree = new int[count];
        int edges = 0;
        for (int w = 0; w < ways.size(); w++) {
            Way way = ways.get(w);
            for (int i = 0; i + 1 < way.nodes.length; i++) {
                int a = index.get(way.nodes[i]);
                int b = index.get(way.nodes[i + 1]);
                if (a < 0 || b < 0 || a == b) {
                    continue;
                }
                if (way.direction != -1) {
                    outDegree[a]++;
                    inDegree[b]++;
                    edges++;
                }
                if (way.direction != 1) {
                    outDegree[b]++;
                    inDegree[a]++;
                    edges++;
                }
            }
        }
        offsets = new int[count + 1];
        for (int i = 0; i < count; i++) {
            offsets[i + 1] = offsets[i] + outDegree[i];
        }
        targets = new int[edges];
        weights = new float[edges];
        int[] fill = Arrays.copyOf(offsets, count);
        for (int w = 0; w < ways.size(); w++) {
            Way way = ways.get(w);
            for (int i = 0; i + 1 < way.nodes.length; i++) {
                int a = index.get(way.nodes[i]);
                int b = index.get(way.nodes[i + 1]);
                if (a < 0 || b < 0 || a == b) {
                    continue;
                }
                float cost = (float) (Geo.distance(lat[a], lon[a], lat[b], lon[b]) * way.slowdown);
                if (way.direction != -1) {
                    targets[fill[a]] = b;
                    weights[fill[a]] = cost;
                    fill[a]++;
                }
                if (way.direction != 1) {
                    targets[fill[b]] = a;
                    weights[fill[b]] = cost;
                    fill[b]++;
                }
            }
        }
        ways = null;
    }

    private int[] nearest(double latitude, double longitude, boolean needsExit) {
        int[] best = new int[SNAP_CANDIDATES];
        double[] bestDist = new double[SNAP_CANDIDATES];
        int found = 0;
        for (int i = 0; i < count; i++) {
            boolean usable = needsExit ? offsets[i + 1] > offsets[i] : inDegree[i] > 0;
            if (!usable) {
                continue;
            }
            double d = Geo.distance(latitude, longitude, lat[i], lon[i]);
            if (d > MAX_SNAP_METERS) {
                continue;
            }
            if (found < SNAP_CANDIDATES || d < bestDist[found - 1]) {
                int pos = found < SNAP_CANDIDATES ? found : found - 1;
                while (pos > 0 && bestDist[pos - 1] > d) {
                    best[pos] = best[pos - 1];
                    bestDist[pos] = bestDist[pos - 1];
                    pos--;
                }
                best[pos] = i;
                bestDist[pos] = d;
                if (found < SNAP_CANDIDATES) {
                    found++;
                }
            }
        }
        int keep = 0;
        while (keep < found && bestDist[keep] <= bestDist[0] + SNAP_SLACK_METERS) {
            keep++;
        }
        return Arrays.copyOf(best, keep);
    }

    public RouteResult findPath(double fromLat, double fromLon, double toLat, double toLon) {
        if (offsets == null || count == 0) {
            return null;
        }
        int[] starts = nearest(fromLat, fromLon, true);
        int[] goals = nearest(toLat, toLon, false);
        if (starts.length == 0 || goals.length == 0) {
            return null;
        }
        int target = count;
        float[] g = new float[count + 1];
        Arrays.fill(g, Float.MAX_VALUE);
        int[] parent = new int[count + 1];
        Arrays.fill(parent, -1);
        boolean[] done = new boolean[count + 1];
        float[] goalCost = new float[count];
        Arrays.fill(goalCost, -1f);
        for (int i = 0; i < goals.length; i++) {
            goalCost[goals[i]] = SNAP_PENALTY * (float) Geo.distance(toLat, toLon, lat[goals[i]], lon[goals[i]]);
        }
        MinHeap heap = new MinHeap(4096);
        for (int i = 0; i < starts.length; i++) {
            int s = starts[i];
            float cost = SNAP_PENALTY * (float) Geo.distance(fromLat, fromLon, lat[s], lon[s]);
            if (cost < g[s]) {
                g[s] = cost;
                heap.push(s, cost + heuristic(s, toLat, toLon));
            }
        }
        while (!heap.isEmpty()) {
            int u = heap.pop();
            if (done[u]) {
                continue;
            }
            done[u] = true;
            if (u == target) {
                break;
            }
            if (goalCost[u] >= 0f) {
                float ng = g[u] + goalCost[u];
                if (ng < g[target]) {
                    g[target] = ng;
                    parent[target] = u;
                    heap.push(target, ng);
                }
            }
            for (int e = offsets[u]; e < offsets[u + 1]; e++) {
                int v = targets[e];
                if (done[v]) {
                    continue;
                }
                float ng = g[u] + weights[e];
                if (ng < g[v]) {
                    g[v] = ng;
                    parent[v] = u;
                    heap.push(v, ng + heuristic(v, toLat, toLon));
                }
            }
        }
        if (!done[target]) {
            return null;
        }
        List<Integer> path = new ArrayList<Integer>();
        int node = parent[target];
        while (node >= 0) {
            path.add(Integer.valueOf(node));
            node = parent[node];
        }
        List<GeoPoint> points = new ArrayList<GeoPoint>(path.size() + 2);
        points.add(new GeoPoint(fromLat, fromLon));
        for (int i = path.size() - 1; i >= 0; i--) {
            int n = path.get(i).intValue();
            points.add(new GeoPoint(lat[n], lon[n]));
        }
        points.add(new GeoPoint(toLat, toLon));
        double meters = 0;
        for (int i = 1; i < points.size(); i++) {
            GeoPoint a = points.get(i - 1);
            GeoPoint b = points.get(i);
            meters += Geo.distance(a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude());
        }
        return new RouteResult(points, meters, g[target] / SPEED_MS);
    }

    private float heuristic(int node, double toLat, double toLon) {
        return (float) Geo.distance(lat[node], lon[node], toLat, toLon);
    }
}
