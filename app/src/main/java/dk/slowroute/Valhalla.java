package dk.slowroute;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.osmdroid.util.GeoPoint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public final class Valhalla {
    private static final String SERVER = "https://valhalla1.openstreetmap.de/route";
    private static final String USER_AGENT = "SlowRoute/1.1 (route planner for A-traktor)";

    private Valhalla() {
    }

    public static RouteResult route(GeoPoint from, GeoPoint to, List<GeoPoint> excludes) throws IOException {
        String body;
        try {
            body = request(from, to, excludes).toString();
        } catch (JSONException e) {
            throw new IOException("could not build route request");
        }
        HttpURLConnection c = (HttpURLConnection) new URL(SERVER).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        String answer;
        int code;
        try {
            OutputStream out = c.getOutputStream();
            try {
                out.write(body.getBytes("UTF-8"));
            } finally {
                out.close();
            }
            code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            answer = in == null ? "" : readAll(in);
        } finally {
            c.disconnect();
        }
        if (code == 400) {
            return null;
        }
        if (code != 200) {
            throw new IOException("route server answered " + code);
        }
        try {
            return parse(new JSONObject(answer));
        } catch (JSONException e) {
            throw new IOException("unexpected answer from route server");
        }
    }

    private static JSONObject request(GeoPoint from, GeoPoint to, List<GeoPoint> excludes) throws JSONException {
        JSONObject filter = new JSONObject();
        filter.put("max_road_class", "primary");
        JSONArray locations = new JSONArray();
        locations.put(location(from).put("search_filter", filter));
        locations.put(location(to).put("search_filter", filter));
        JSONObject auto = new JSONObject();
        auto.put("top_speed", (int) RoadRules.MAX_SPEED_KMH);
        auto.put("fixed_speed", (int) RoadRules.MAX_SPEED_KMH);
        auto.put("use_highways", 0.0);
        auto.put("shortest", false);
        JSONObject options = new JSONObject();
        options.put("auto", auto);
        JSONObject root = new JSONObject();
        root.put("locations", locations);
        root.put("costing", "auto");
        root.put("costing_options", options);
        root.put("units", "kilometers");
        root.put("directions_type", "none");
        if (excludes != null && !excludes.isEmpty()) {
            JSONArray ex = new JSONArray();
            for (int i = 0; i < excludes.size(); i++) {
                ex.put(location(excludes.get(i)));
            }
            root.put("exclude_locations", ex);
        }
        return root;
    }

    private static JSONObject location(GeoPoint p) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("lat", p.getLatitude());
        o.put("lon", p.getLongitude());
        return o;
    }

    private static RouteResult parse(JSONObject json) throws JSONException {
        JSONObject trip = json.getJSONObject("trip");
        JSONArray legs = trip.getJSONArray("legs");
        List<GeoPoint> points = new ArrayList<GeoPoint>();
        for (int i = 0; i < legs.length(); i++) {
            decode(legs.getJSONObject(i).getString("shape"), points);
        }
        if (points.size() < 2) {
            return null;
        }
        JSONObject summary = trip.getJSONObject("summary");
        double meters = summary.optDouble("length", 0) * 1000.0;
        double seconds = meters / (RoadRules.MAX_SPEED_KMH / 3.6);
        return new RouteResult(points, meters, seconds);
    }

    static void decode(String shape, List<GeoPoint> out) {
        int index = 0;
        int lat = 0;
        int lon = 0;
        int length = shape.length();
        while (index < length) {
            int result = 0;
            int shift = 0;
            int b;
            do {
                b = shape.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20 && index < length);
            lat += (result & 1) != 0 ? ~(result >> 1) : (result >> 1);
            result = 0;
            shift = 0;
            do {
                b = shape.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20 && index < length);
            lon += (result & 1) != 0 ? ~(result >> 1) : (result >> 1);
            out.add(new GeoPoint(lat / 1e6, lon / 1e6));
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }
}
