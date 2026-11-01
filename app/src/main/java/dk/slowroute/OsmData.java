package dk.slowroute;

import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class OsmData {
    private static final String[] OVERPASS_SERVERS = {
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
    };
    private static final String NOMINATIM = "https://nominatim.openstreetmap.org/search";
    private static final String USER_AGENT = "SlowRoute/1.0 (Android route planner for 30 km/h cars)";
    private static final double[] MARGINS_KM = { 3.0, 8.0 };

    private OsmData() {
    }

    public static final class Place {
        public final GeoPoint point;
        public final String name;

        Place(GeoPoint point, String name) {
            this.point = point;
            this.name = name;
        }
    }

    private static final class Element {
        String type;
        long id;
        double lat;
        double lon;
        long[] nodes;
        Map<String, String> tags;
        List<GeoPoint> geometry;
    }

    private interface ElementHandler {
        void onElement(Element e);
    }

    public static List<List<GeoPoint>> fetchForbidden(BoundingBox box) throws IOException {
        String b = bbox(box.getLatSouth(), box.getLonWest(), box.getLatNorth(), box.getLonEast());
        String query = "[out:json][timeout:25];("
                + "way[\"highway\"~\"^(motorway|motorway_link)$\"](" + b + ");"
                + "way[\"highway\"][\"motorroad\"=\"yes\"](" + b + ");"
                + "way[\"highway\"][\"minspeed\"](" + b + ");"
                + ");out geom qt;";
        final List<List<GeoPoint>> lines = new ArrayList<List<GeoPoint>>();
        InputStream in = overpass(query);
        try {
            readElements(in, new ElementHandler() {
                public void onElement(Element e) {
                    if ("way".equals(e.type) && e.geometry != null && e.geometry.size() > 1
                            && RoadRules.forbiddenForSlowCar(e.tags)) {
                        lines.add(e.geometry);
                    }
                }
            });
        } finally {
            in.close();
        }
        return lines;
    }

    public static RouteResult route(GeoPoint from, GeoPoint to) throws IOException {
        for (int i = 0; i < MARGINS_KM.length; i++) {
            RoadGraph graph = loadGraph(from, to, MARGINS_KM[i]);
            RouteResult result = graph.findPath(from.getLatitude(), from.getLongitude(), to.getLatitude(), to.getLongitude());
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private static RoadGraph loadGraph(GeoPoint a, GeoPoint b, double marginKm) throws IOException {
        double midLat = (a.getLatitude() + b.getLatitude()) / 2.0;
        double dLat = marginKm / 111.0;
        double dLon = marginKm / (111.0 * Math.cos(Math.toRadians(midLat)));
        String box = bbox(
                Math.min(a.getLatitude(), b.getLatitude()) - dLat,
                Math.min(a.getLongitude(), b.getLongitude()) - dLon,
                Math.max(a.getLatitude(), b.getLatitude()) + dLat,
                Math.max(a.getLongitude(), b.getLongitude()) + dLon);
        String query = "[out:json][timeout:120][maxsize:536870912];"
                + "way[\"highway\"~\"" + RoadRules.ROUTABLE_REGEX + "\"](" + box + ");"
                + "(._;>;);out qt;";
        final RoadGraph graph = new RoadGraph();
        InputStream in = overpass(query);
        String remark;
        try {
            remark = readElements(in, new ElementHandler() {
                public void onElement(Element e) {
                    if ("node".equals(e.type)) {
                        graph.addNode(e.id, e.lat, e.lon);
                    } else if ("way".equals(e.type) && RoadRules.isRoutable(e.tags)) {
                        graph.addWay(e.nodes, RoadRules.direction(e.tags), RoadRules.slowdown(e.tags));
                    }
                }
            });
        } finally {
            in.close();
        }
        if (remark != null && remark.toLowerCase(Locale.US).indexOf("error") >= 0) {
            throw new IOException("the map server is busy, try again in a minute");
        }
        graph.build();
        return graph;
    }

    public static Place search(String text, BoundingBox near) throws IOException {
        String url = NOMINATIM + "?format=json&limit=1&q=" + URLEncoder.encode(text, "UTF-8")
                + "&viewbox=" + String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f",
                near.getLonWest(), near.getLatNorth(), near.getLonEast(), near.getLatSouth());
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Accept-Language", Locale.getDefault().getLanguage());
        String body;
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                throw new IOException("search server answered " + code);
            }
            body = readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
        try {
            JSONArray results = new JSONArray(body);
            if (results.length() == 0) {
                return null;
            }
            JSONObject first = results.getJSONObject(0);
            GeoPoint point = new GeoPoint(Double.parseDouble(first.getString("lat")), Double.parseDouble(first.getString("lon")));
            String name = first.optString("display_name", text);
            int comma = name.indexOf(',');
            if (comma > 0) {
                name = name.substring(0, comma);
            }
            return new Place(point, name);
        } catch (JSONException e) {
            throw new IOException("unexpected answer from search server");
        }
    }

    private static String bbox(double south, double west, double north, double east) {
        return String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f", south, west, north, east);
    }

    private static InputStream overpass(String query) throws IOException {
        IOException last = null;
        for (int i = 0; i < OVERPASS_SERVERS.length; i++) {
            try {
                return post(OVERPASS_SERVERS[i], query);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    private static InputStream post(String server, String query) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(server).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(150000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        byte[] body = ("data=" + URLEncoder.encode(query, "UTF-8")).getBytes("UTF-8");
        OutputStream out = c.getOutputStream();
        try {
            out.write(body);
        } finally {
            out.close();
        }
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException("map server answered " + code);
        }
        return new BufferedInputStream(c.getInputStream(), 65536);
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

    private static String readElements(InputStream in, ElementHandler handler) throws IOException {
        JsonReader reader = new JsonReader(new InputStreamReader(in, "UTF-8"));
        String remark = null;
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("elements".equals(name)) {
                reader.beginArray();
                while (reader.hasNext()) {
                    handler.onElement(readElement(reader));
                }
                reader.endArray();
            } else if ("remark".equals(name) && reader.peek() == JsonToken.STRING) {
                remark = reader.nextString();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        return remark;
    }

    private static Element readElement(JsonReader reader) throws IOException {
        Element e = new Element();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (reader.peek() == JsonToken.NULL) {
                reader.skipValue();
            } else if ("type".equals(name)) {
                e.type = reader.nextString();
            } else if ("id".equals(name)) {
                e.id = reader.nextLong();
            } else if ("lat".equals(name)) {
                e.lat = reader.nextDouble();
            } else if ("lon".equals(name)) {
                e.lon = reader.nextDouble();
            } else if ("nodes".equals(name)) {
                e.nodes = readLongs(reader);
            } else if ("tags".equals(name)) {
                e.tags = readTags(reader);
            } else if ("geometry".equals(name)) {
                e.geometry = readGeometry(reader);
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        if (e.tags == null) {
            e.tags = new HashMap<String, String>();
        }
        return e;
    }

    private static long[] readLongs(JsonReader reader) throws IOException {
        long[] values = new long[16];
        int n = 0;
        reader.beginArray();
        while (reader.hasNext()) {
            if (n == values.length) {
                values = Arrays.copyOf(values, n * 2);
            }
            values[n++] = reader.nextLong();
        }
        reader.endArray();
        return Arrays.copyOf(values, n);
    }

    private static Map<String, String> readTags(JsonReader reader) throws IOException {
        Map<String, String> tags = new HashMap<String, String>();
        reader.beginObject();
        while (reader.hasNext()) {
            String key = reader.nextName();
            if (reader.peek() == JsonToken.STRING) {
                tags.put(key, reader.nextString());
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        return tags;
    }

    private static List<GeoPoint> readGeometry(JsonReader reader) throws IOException {
        List<GeoPoint> points = new ArrayList<GeoPoint>();
        reader.beginArray();
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.NULL) {
                reader.skipValue();
                continue;
            }
            double la = Double.NaN;
            double lo = Double.NaN;
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("lat".equals(name)) {
                    la = reader.nextDouble();
                } else if ("lon".equals(name)) {
                    lo = reader.nextDouble();
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
            if (!Double.isNaN(la) && !Double.isNaN(lo)) {
                points.add(new GeoPoint(la, lo));
            }
        }
        reader.endArray();
        return points;
    }
}
