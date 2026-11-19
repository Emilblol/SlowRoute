package dk.slowroute;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Paint;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.conscrypt.Conscrypt;
import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.events.DelayedMapListener;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.FolderOverlay;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.io.File;
import java.security.Security;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int LOCATION_REQUEST = 1;
    private static final double MAX_TRIP_METERS = 100000.0;
    private static final BoundingBox SWEDEN = new BoundingBox(69.4, 24.6, 55.0, 10.4);
    private static final double RED_MIN_ZOOM = 10.0;
    private static final String ROAD_MAP_MB = "100";
    private static final int RED_COLOR = 0xDDE53935;
    private static final int ROUTE_COLOR = 0xFF1E6FD9;
    private static final String DEFAULT_INFO = "Search for a place in Sweden, or long-press the map to set a start or destination.";

    private MapView map;
    private FolderOverlay redLayer;
    private FolderOverlay routeLayer;
    private MyLocationNewOverlay myLocation;
    private Marker startMarker;
    private Marker endMarker;
    private BoundingBox loadedBox;
    private int redRequest;
    private int routeRequest;
    private TextView info;
    private TextView legend;
    private EditText search;
    private ProgressBar progress;
    private TextView offline;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1);
        } catch (Throwable t) {
            t.printStackTrace();
        }
        IConfigurationProvider conf = Configuration.getInstance();
        conf.load(this, PreferenceManager.getDefaultSharedPreferences(this));
        conf.setUserAgentValue(getPackageName());
        File base = new File(getCacheDir(), "osmdroid");
        conf.setOsmdroidBasePath(base);
        conf.setOsmdroidTileCache(new File(base, "tiles"));

        setContentView(R.layout.activity_main);
        map = (MapView) findViewById(R.id.map);
        info = (TextView) findViewById(R.id.info);
        legend = (TextView) findViewById(R.id.legend);
        search = (EditText) findViewById(R.id.search);
        progress = (ProgressBar) findViewById(R.id.progress);
        offline = (TextView) findViewById(R.id.offline);
        offline.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (GraphStore.get() == null && !GraphStore.isBusy()) {
                    askDownload();
                }
            }
        });

        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        map.setMinZoomLevel(5.0);
        map.setScrollableAreaLimitDouble(SWEDEN);
        map.getController().setZoom(14.0);
        map.getController().setCenter(new GeoPoint(59.3293, 18.0686));

        map.getOverlays().add(new MapEventsOverlay(new MapEventsReceiver() {
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                return false;
            }

            public boolean longPressHelper(GeoPoint p) {
                checkSwedenThenAsk(p);
                return true;
            }
        }));
        redLayer = new FolderOverlay();
        routeLayer = new FolderOverlay();
        map.getOverlays().add(redLayer);
        map.getOverlays().add(routeLayer);

        map.addMapListener(new DelayedMapListener(new MapListener() {
            public boolean onScroll(ScrollEvent event) {
                refreshForbiddenRoads();
                return true;
            }

            public boolean onZoom(ZoomEvent event) {
                refreshForbiddenRoads();
                return true;
            }
        }, 250));

        ((Button) findViewById(R.id.searchButton)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                runSearch();
            }
        });
        ((Button) findViewById(R.id.clearButton)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                clearRoute();
            }
        });
        ((Button) findViewById(R.id.locateButton)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                goToMyLocation();
            }
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    runSearch();
                    return true;
                }
                return false;
            }
        });

        if (hasLocationPermission()) {
            enableLocation();
        } else {
            askLocationPermission();
        }

        map.post(new Runnable() {
            public void run() {
                refreshForbiddenRoads();
            }
        });

        ForbiddenCache.prepare(getFilesDir(), new Runnable() {
            public void run() {
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (!isFinishing()) {
                            loadedBox = null;
                            refreshForbiddenRoads();
                        }
                    }
                });
            }
        });

        if (GraphStore.get() != null) {
            showOffline("Offline road map ready · instant routes", false);
        } else if (GraphStore.isDownloaded(getFilesDir())) {
            showOffline("Loading road map…", true);
            GraphStore.open(getFilesDir(), graphListener());
        } else if (!GraphStore.isBusy()) {
            showOffline("Tap to download the road map for instant routes", true);
            askDownload();
        }
    }

    private void showOffline(String text, boolean visible) {
        offline.setText(text);
        offline.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void askDownload() {
        new AlertDialog.Builder(this)
                .setTitle("Instant routes")
                .setMessage("Download the road map of Sweden once (about " + ROAD_MAP_MB + " MB, best on Wi-Fi). After that, routes are found instantly, even without internet.")
                .setPositiveButton("Download", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        showOffline("Downloading road map… 0%", true);
                        GraphStore.download(getFilesDir(), graphListener());
                    }
                })
                .setNegativeButton("Later", null)
                .show();
    }

    private GraphStore.Listener graphListener() {
        return new GraphStore.Listener() {
            public void onProgress(final int percent) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (!isFinishing()) {
                            showOffline("Downloading road map… " + percent + "%", true);
                        }
                    }
                });
            }

            public void onReady(OfflineGraph graph) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (!isFinishing()) {
                            showOffline("Road map ready · routes are now instant", true);
                            offline.postDelayed(new Runnable() {
                                public void run() {
                                    offline.setVisibility(View.GONE);
                                }
                            }, 4000);
                        }
                    }
                });
            }

            public void onFailed(final String message) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (!isFinishing()) {
                            showOffline("Road map download failed (" + message + "). Tap to try again.", true);
                        }
                    }
                });
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        map.onResume();
        if (myLocation != null) {
            myLocation.enableMyLocation();
        }
    }

    @Override
    protected void onPause() {
        map.onPause();
        if (myLocation != null) {
            myLocation.disableMyLocation();
        }
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == LOCATION_REQUEST && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            enableLocation();
        }
    }

    private boolean hasLocationPermission() {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void askLocationPermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, LOCATION_REQUEST);
        }
    }

    private void enableLocation() {
        if (myLocation != null) {
            return;
        }
        GpsMyLocationProvider provider = new GpsMyLocationProvider(this);
        provider.addLocationSource(LocationManager.NETWORK_PROVIDER);
        myLocation = new MyLocationNewOverlay(provider, map);
        myLocation.enableMyLocation();
        map.getOverlays().add(myLocation);
        final MyLocationNewOverlay overlay = myLocation;
        overlay.runOnFirstFix(new Runnable() {
            public void run() {
                runOnUiThread(new Runnable() {
                    public void run() {
                        GeoPoint p = overlay.getMyLocation();
                        if (p != null && startMarker == null && endMarker == null) {
                            map.getController().setZoom(15.0);
                            map.getController().animateTo(p);
                        }
                    }
                });
            }
        });
    }

    private void goToMyLocation() {
        if (!hasLocationPermission()) {
            askLocationPermission();
            return;
        }
        enableLocation();
        GeoPoint p = myLocation.getMyLocation();
        if (p == null) {
            Toast.makeText(this, "Waiting for GPS…", Toast.LENGTH_SHORT).show();
            return;
        }
        map.getController().animateTo(p);
    }

    private GeoPoint currentStart() {
        if (startMarker != null) {
            return startMarker.getPosition();
        }
        if (myLocation != null && myLocation.getMyLocation() != null) {
            GeoPoint p = myLocation.getMyLocation();
            if (insideBox(SWEDEN, p)) {
                return new GeoPoint(p.getLatitude(), p.getLongitude());
            }
        }
        return null;
    }

    private static boolean insideBox(BoundingBox box, GeoPoint p) {
        return p.getLatitude() <= box.getLatNorth() && p.getLatitude() >= box.getLatSouth()
                && p.getLongitude() <= box.getLonEast() && p.getLongitude() >= box.getLonWest();
    }

    private void checkSwedenThenAsk(final GeoPoint p) {
        if (!insideBox(SWEDEN, p)) {
            Toast.makeText(this, "SlowRoute only works in Sweden.", Toast.LENGTH_SHORT).show();
            return;
        }
        setBusy(true);
        new Thread(new Runnable() {
            public void run() {
                String country;
                try {
                    country = OsmData.countryCode(p);
                } catch (Exception e) {
                    country = null;
                }
                final boolean ok = country == null || "se".equals(country);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        setBusy(false);
                        if (ok) {
                            askPointType(p);
                        } else {
                            Toast.makeText(MainActivity.this, "That's outside Sweden. SlowRoute only works in Sweden.", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }).start();
    }

    private void askPointType(final GeoPoint p) {
        String[] items = { "Start here", "Go here" };
        new AlertDialog.Builder(this).setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                if (which == 0) {
                    setStart(p);
                } else {
                    setDestination(p, null);
                }
            }
        }).show();
    }

    private void setStart(GeoPoint p) {
        if (startMarker == null) {
            startMarker = new Marker(map);
            startMarker.setIcon(getResources().getDrawable(R.drawable.start_dot));
            startMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            startMarker.setTitle("Start");
            map.getOverlays().add(startMarker);
        }
        startMarker.setPosition(p);
        map.invalidate();
        if (endMarker != null) {
            findRoute();
        } else {
            info.setText("Start set. Now search or long-press to pick where you're going.");
        }
    }

    private void setDestination(GeoPoint p, String name) {
        if (endMarker == null) {
            endMarker = new Marker(map);
            endMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            map.getOverlays().add(endMarker);
        }
        endMarker.setPosition(p);
        endMarker.setTitle(name == null ? "Destination" : name);
        map.invalidate();
        findRoute();
    }

    private void clearRoute() {
        routeRequest++;
        setBusy(false);
        if (startMarker != null) {
            map.getOverlays().remove(startMarker);
            startMarker = null;
        }
        if (endMarker != null) {
            map.getOverlays().remove(endMarker);
            endMarker = null;
        }
        routeLayer.getItems().clear();
        search.setText("");
        info.setText(DEFAULT_INFO);
        map.invalidate();
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private void runSearch() {
        final String text = search.getText().toString().trim();
        if (text.length() == 0) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        final BoundingBox near = map.getBoundingBox();
        final int id = ++routeRequest;
        setBusy(true);
        info.setText("Searching…");
        new Thread(new Runnable() {
            public void run() {
                OsmData.Place found = null;
                String error = null;
                try {
                    found = OsmData.search(text, near);
                } catch (Exception e) {
                    error = "Search failed (" + e.getMessage() + "). Check your internet connection.";
                }
                final OsmData.Place place = found;
                final String err = error;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (id != routeRequest || isFinishing()) {
                            return;
                        }
                        setBusy(false);
                        if (err != null) {
                            info.setText(err);
                        } else if (place == null) {
                            info.setText("Couldn't find \"" + text + "\".");
                        } else {
                            map.getController().animateTo(place.point);
                            setDestination(place.point, place.name);
                        }
                    }
                });
            }
        }).start();
    }

    private void findRoute() {
        if (endMarker == null) {
            return;
        }
        GeoPoint from = currentStart();
        if (from == null) {
            info.setText("No start point in Sweden yet. Turn on GPS, or long-press the map and choose \"Start here\".");
            return;
        }
        final GeoPoint start = from;
        final GeoPoint to = endMarker.getPosition();
        double straight = Geo.distance(start.getLatitude(), start.getLongitude(), to.getLatitude(), to.getLongitude());
        if (straight > MAX_TRIP_METERS) {
            info.setText("That's more than 100 km away. Pick a closer destination, or split the trip into parts.");
            return;
        }
        routeLayer.getItems().clear();
        map.invalidate();
        final int id = ++routeRequest;
        setBusy(true);
        info.setText("Finding a legal route…");
        new Thread(new Runnable() {
            public void run() {
                RouteResult result = null;
                String error = null;
                try {
                    result = Router.route(start, to);
                } catch (OutOfMemoryError e) {
                    error = "That area has too many roads to load on this phone. Try a shorter trip.";
                } catch (Exception e) {
                    error = "Couldn't get a route (" + e.getMessage() + "). Check your internet and try again.";
                }
                final RouteResult route = result;
                final String err = error;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (id != routeRequest || isFinishing()) {
                            return;
                        }
                        setBusy(false);
                        showRoute(route, err);
                    }
                });
            }
        }).start();
    }

    private void showRoute(RouteResult route, String error) {
        if (error != null) {
            info.setText(error);
            return;
        }
        if (route == null) {
            info.setText("No legal route found. The destination might only be reachable through motorväg or motortrafikled.");
            return;
        }
        Polyline line = new Polyline(map);
        line.setPoints(route.points);
        line.getOutlinePaint().setColor(ROUTE_COLOR);
        line.getOutlinePaint().setStrokeWidth(14f);
        line.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        line.getOutlinePaint().setStrokeJoin(Paint.Join.ROUND);
        line.setInfoWindow(null);
        routeLayer.getItems().clear();
        routeLayer.add(line);
        map.invalidate();
        map.zoomToBoundingBox(BoundingBox.fromGeoPoints(route.points), true, 150);
        long minutes = Math.max(1L, Math.round(route.seconds / 60.0));
        info.setText(String.format(Locale.getDefault(),
                "%.1f km · about %d min at 30 km/h\n%s",
                route.meters / 1000.0, minutes,
                route.offline ? "Only legal roads for A-traktor (offline map)."
                        : route.verified ? "Avoids motorväg and motortrafikled." : "Couldn't fully check this route against motorväg/motortrafikled. Watch the signs."));
    }

    private void refreshForbiddenRoads() {
        if (map.getZoomLevelDouble() < RED_MIN_ZOOM) {
            redRequest++;
            loadedBox = null;
            if (!redLayer.getItems().isEmpty()) {
                redLayer.getItems().clear();
                map.invalidate();
            }
            legend.setText("Zoom in to see roads you can't drive on");
            return;
        }
        BoundingBox view = map.getBoundingBox();
        if (loadedBox != null && contains(loadedBox, view)) {
            return;
        }
        final BoundingBox wanted = view.increaseByScale(1.8f);
        final int id = ++redRequest;
        legend.setText("Loading forbidden roads…");
        new Thread(new Runnable() {
            public void run() {
                List<List<GeoPoint>> lines;
                try {
                    ForbiddenCache cache = ForbiddenCache.get();
                    lines = cache != null ? cache.query(wanted) : OsmData.fetchForbidden(wanted);
                } catch (Exception e) {
                    lines = null;
                }
                final List<List<GeoPoint>> result = lines;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (id != redRequest || isFinishing()) {
                            return;
                        }
                        if (result == null) {
                            legend.setText("Couldn't load forbidden roads. Move the map to retry.");
                            return;
                        }
                        redLayer.getItems().clear();
                        for (int i = 0; i < result.size(); i++) {
                            redLayer.add(redLine(result.get(i)));
                        }
                        loadedBox = wanted;
                        legend.setText("Red = not allowed for A-traktor");
                        map.invalidate();
                    }
                });
            }
        }).start();
    }

    private Polyline redLine(List<GeoPoint> points) {
        Polyline line = new Polyline(map);
        line.setPoints(points);
        line.getOutlinePaint().setColor(RED_COLOR);
        line.getOutlinePaint().setStrokeWidth(9f);
        line.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        line.setInfoWindow(null);
        return line;
    }

    private static boolean contains(BoundingBox outer, BoundingBox inner) {
        return outer.getLatNorth() >= inner.getLatNorth()
                && outer.getLatSouth() <= inner.getLatSouth()
                && outer.getLonEast() >= inner.getLonEast()
                && outer.getLonWest() <= inner.getLonWest();
    }
}
