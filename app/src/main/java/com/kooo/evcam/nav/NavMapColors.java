package com.kooo.evcam.nav;

import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.BackgroundLayer;
import org.maplibre.android.style.layers.FillExtrusionLayer;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.maps.Style;

/**
 * Repaints TomTom's map in the familiar phone-map colours: pale land, white roads, yellow motorways,
 * soft green parks and blue water; slate and tan at night. The tiles stay TomTom's, only the paint of
 * their layers changes, so it also applies to the floating window and the HUD map. Layers this does
 * not name (traffic, borders, railways, icons) keep TomTom's look.
 */
final class NavMapColors {

    private static final String CLEAR = "rgba(0,0,0,0)";
    /** Lower is flatter shading on the 3D buildings. */
    private static final float BUILDING_LIGHT = 0.15f;

    /** One day or night palette. */
    private static final class Palette {
        final String land;
        final String water;
        final String park;
        final String building;
        final String buildingEdge;
        final String road;
        final String roadEdge;
        final String major;
        final String majorEdge;
        final String motorway;
        final String motorwayEdge;
        final String roadText;
        final String placeText;
        final String waterText;
        final String halo;

        Palette(String... c) {
            land = c[0];
            water = c[1];
            park = c[2];
            building = c[3];
            buildingEdge = c[4];
            road = c[5];
            roadEdge = c[6];
            major = c[7];
            majorEdge = c[8];
            motorway = c[9];
            motorwayEdge = c[10];
            roadText = c[11];
            placeText = c[12];
            waterText = c[13];
            halo = c[14];
        }
    }

    private static final Palette DAY = new Palette(
            "#F5F3EE", "#AADAFF", "#C5E8C5", "#ECEAE6", "#DEDBD5",
            "#FFFFFF", "#D5D8DC", "#FFF2AF", "#F0D98C", "#FDD663", "#E8B23A",
            "#5F6368", "#3C4043", "#4A7FA7", "#FFFFFF");
    private static final Palette NIGHT = new Palette(
            "#242F3E", "#17263C", "#263C3F", "#2C3849", "#222C3A",
            "#38414E", "#212A37", "#4A5364", "#2A3240", "#746855", "#1F2835",
            "#9CA5B3", "#D5DAE1", "#515C6D", "#17263C");

    private NavMapColors() {
    }

    /** Repaints a freshly loaded style. Before the app's own layers are added. */
    static void apply(Style style, boolean dark) {
        // the 3D buildings' walls stay near their roofs' colour: soft blocks, not dark slabs
        org.maplibre.android.style.light.Light light = style.getLight();
        if (light != null) {
            light.setIntensity(BUILDING_LIGHT);
        }
        for (Layer layer : style.getLayers()) {
            String paint = paintFor(layer.getId(), dark);
            if (paint == null) {
                continue;
            }
            if (layer instanceof BackgroundLayer) {
                layer.setProperties(PropertyFactory.backgroundColor(paint));
            } else if (layer instanceof FillExtrusionLayer) {
                layer.setProperties(PropertyFactory.fillExtrusionColor(paint));
            } else if (layer instanceof FillLayer) {
                layer.setProperties(paint.startsWith("[") ? PropertyFactory.fillColor(Expression.raw(paint))
                        : PropertyFactory.fillColor(paint));
            } else if (layer instanceof LineLayer) {
                layer.setProperties(paint.startsWith("[") ? PropertyFactory.lineColor(Expression.raw(paint))
                        : PropertyFactory.lineColor(paint));
            } else if (layer instanceof SymbolLayer) {
                layer.setProperties(PropertyFactory.textColor(paint),
                        PropertyFactory.textHaloColor((dark ? NIGHT : DAY).halo));
            }
        }
    }

    /** The colour (or, starting with {@code [}, the colour expression) for a layer of the style; null = leave it. */
    static String paintFor(String id, boolean dark) {
        Palette p = dark ? NIGHT : DAY;
        if (id.equals("background") || id.equals("LULC - Built-up area")
                || id.startsWith("Structure - Bridge")) {
            return p.land;
        }
        if (id.equals("Water - Fill") || id.equals("Water - Shadow") || id.equals("Water - Line")
                || id.equals("Water - Intermittent")) {
            return p.water;
        }
        if (id.equals("LULC - Protected Areas") || id.equals("LULC - Outdoor & Sport")) {
            return p.park;
        }
        if (id.equals("LULC - Landcover")) {
            return "[\"match\",[\"get\",\"category\"],[\"woodland\",\"grassland\",\"shrubland\",\"agricultural\"],\""
                    + p.park + "\",\"" + CLEAR + "\"]";
        }
        if (id.equals("LULC - Landuse") && dark) {
            // the day map keeps its faint hospital and shopping tints; at night they read as blotches
            return "[\"match\",[\"get\",\"group\"],[\"outdoor\",\"sport\"],\"" + p.park + "\",\"" + CLEAR + "\"]";
        }
        if (id.equals("Buildings - Fill") || id.equals("3D - Building")) {
            return p.building;
        }
        if (id.equals("Buildings - Outline") || id.equals("Buildings - Shadow")) {
            return p.buildingEdge;
        }
        if (isRoad(id)) {
            return id.contains("outline") ? roads(p.roadEdge, p.majorEdge, p.motorwayEdge)
                    : roads(p.road, p.major, p.motorway);
        }
        if (id.equals("TransitLabels - Road") || id.equals("TransitLabels - Path")) {
            return p.roadText;
        }
        if (id.startsWith("Places - ")) {
            return p.placeText;
        }
        if (id.equals("NatureLabels - Water area") || id.equals("NatureLabels - River line")) {
            return p.waterText;
        }
        return null;
    }

    /** A road line or its outline: the road layers of the surface, tunnels and bridges. */
    private static boolean isRoad(String id) {
        if (!(id.startsWith("Surface - ") || id.startsWith("Tunnel - ") || id.startsWith("Bridge"))) {
            return false;
        }
        if (id.contains("Railway") || id.contains("construction") || id.contains("Restricted")
                || id.contains("arrow") || id.contains("Turning")) {
            return false;
        }
        return id.contains("oad") || id.contains("Street") || id.contains("Motorway") || id.contains("Tarmac");
    }

    /** By the road's own class, since one tunnel or bridge layer holds every class of road. */
    private static String roads(String plain, String major, String motorway) {
        return "[\"match\",[\"get\",\"category\"],"
                + "[\"motorway\",\"motorway_link\",\"trunk\",\"trunk_link\"],\"" + motorway + "\","
                + "[\"primary\",\"primary_link\"],\"" + major + "\",\"" + plain + "\"]";
    }
}
