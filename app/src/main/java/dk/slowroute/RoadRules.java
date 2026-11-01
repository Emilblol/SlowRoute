package dk.slowroute;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class RoadRules {
    public static final double MAX_SPEED_KMH = 30.0;
    public static final String ROUTABLE_REGEX = "^(trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|unclassified|residential|living_street|service|road)$";

    private static final Set<String> ROUTABLE = new HashSet<String>(Arrays.asList(
            "trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link",
            "tertiary", "tertiary_link", "unclassified", "residential", "living_street", "service", "road"));

    private static final Set<String> CLOSED = new HashSet<String>(Arrays.asList(
            "no", "private", "agricultural", "forestry", "delivery", "emergency", "psv", "bus"));

    private RoadRules() {
    }

    public static boolean forbiddenForSlowCar(Map<String, String> tags) {
        if (tags == null) {
            return false;
        }
        String highway = tags.get("highway");
        if ("motorway".equals(highway) || "motorway_link".equals(highway)) {
            return true;
        }
        if ("yes".equals(tags.get("motorroad"))) {
            return true;
        }
        double minSpeed = parseSpeed(tags.get("minspeed"));
        return !Double.isNaN(minSpeed) && minSpeed > MAX_SPEED_KMH;
    }

    public static boolean closedToCars(Map<String, String> tags) {
        String value = tags.get("motorcar");
        if (value == null) {
            value = tags.get("motor_vehicle");
        }
        if (value == null) {
            value = tags.get("vehicle");
        }
        if (value == null) {
            value = tags.get("access");
        }
        return value != null && CLOSED.contains(value);
    }

    public static boolean isRoutable(Map<String, String> tags) {
        if (tags == null || !ROUTABLE.contains(tags.get("highway"))) {
            return false;
        }
        if ("yes".equals(tags.get("area")) || "reversible".equals(tags.get("oneway"))) {
            return false;
        }
        return !forbiddenForSlowCar(tags) && !closedToCars(tags);
    }

    public static int direction(Map<String, String> tags) {
        String oneway = tags.get("oneway");
        if (oneway == null) {
            String junction = tags.get("junction");
            if ("roundabout".equals(junction) || "circular".equals(junction)) {
                return 1;
            }
            return 0;
        }
        if ("yes".equals(oneway) || "1".equals(oneway) || "true".equals(oneway)) {
            return 1;
        }
        if ("-1".equals(oneway) || "reverse".equals(oneway)) {
            return -1;
        }
        return 0;
    }

    public static float slowdown(Map<String, String> tags) {
        double max = parseSpeed(tags.get("maxspeed"));
        if (Double.isNaN(max) && "living_street".equals(tags.get("highway"))) {
            max = 15.0;
        }
        if (Double.isNaN(max) || max <= 0 || max >= MAX_SPEED_KMH) {
            return 1f;
        }
        return (float) (MAX_SPEED_KMH / max);
    }

    public static double parseSpeed(String value) {
        if (value == null) {
            return Double.NaN;
        }
        String v = value.trim().toLowerCase();
        if (v.equals("walk")) {
            return 6.0;
        }
        int end = 0;
        while (end < v.length() && (Character.isDigit(v.charAt(end)) || v.charAt(end) == '.')) {
            end++;
        }
        if (end == 0) {
            return Double.NaN;
        }
        double speed;
        try {
            speed = Double.parseDouble(v.substring(0, end));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
        if (v.indexOf("mph") >= 0) {
            speed = speed * 1.609344;
        }
        return speed;
    }
}
