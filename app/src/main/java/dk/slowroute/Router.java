package dk.slowroute;

import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class Router {
    private static final int MAX_ATTEMPTS = 6;
    private static final double CHECK_MARGIN_DEG = 0.02;

    private Router() {
    }

    public static RouteResult route(GeoPoint from, GeoPoint to) throws IOException {
        List<GeoPoint> excludes = new ArrayList<GeoPoint>();
        BoundingBox checkedBox = null;
        RouteChecker checker = null;
        RouteResult last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            RouteResult result = Valhalla.route(from, to, excludes);
            if (result == null) {
                if (last != null) {
                    last.verified = false;
                }
                return last;
            }
            last = result;
            BoundingBox need = BoundingBox.fromGeoPoints(result.points);
            if (checkedBox == null || !contains(checkedBox, need)) {
                BoundingBox wanted = new BoundingBox(
                        Math.min(85.0, need.getLatNorth() + CHECK_MARGIN_DEG),
                        Math.min(180.0, need.getLonEast() + CHECK_MARGIN_DEG * 2),
                        Math.max(-85.0, need.getLatSouth() - CHECK_MARGIN_DEG),
                        Math.max(-180.0, need.getLonWest() - CHECK_MARGIN_DEG * 2));
                try {
                    ForbiddenCache cache = ForbiddenCache.get();
                    List<List<GeoPoint>> forbidden = cache != null ? cache.query(wanted) : OsmData.fetchForbidden(wanted);
                    checker = new RouteChecker(forbidden, wanted.getCenterLatitude());
                    checkedBox = wanted;
                } catch (IOException e) {
                    result.verified = false;
                    return result;
                }
            }
            List<GeoPoint> bad = checker.offendingPoints(result.points);
            if (bad.isEmpty()) {
                return result;
            }
            excludes.addAll(bad);
        }
        if (last != null) {
            last.verified = false;
        }
        return last;
    }

    private static boolean contains(BoundingBox outer, BoundingBox inner) {
        return outer.getLatNorth() >= inner.getLatNorth()
                && outer.getLatSouth() <= inner.getLatSouth()
                && outer.getLonEast() >= inner.getLonEast()
                && outer.getLonWest() <= inner.getLonWest();
    }
}
