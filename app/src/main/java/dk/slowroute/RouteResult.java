package dk.slowroute;

import org.osmdroid.util.GeoPoint;

import java.util.List;

public final class RouteResult {
    public final List<GeoPoint> points;
    public final double meters;
    public final double seconds;

    public RouteResult(List<GeoPoint> points, double meters, double seconds) {
        this.points = points;
        this.meters = meters;
        this.seconds = seconds;
    }
}
