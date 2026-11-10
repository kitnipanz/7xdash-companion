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
 * not name (traffic, borders, railways, icons) keep TomTom's look. The space scheme goes further:
 * a near-black map, faint blue roads and see-through glass 3D buildings that glow at the top.
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
    private static final Palette SPACE = new Palette(
            "#07101A", "#0A2440", "#0B1E1A", "#0F2230", "#08131C",
            "#14202E", "#060C14", "#1A2A40", "#0A1422", "#21406A", "#0C1A2E",
            "#7890AC", "#C8DCF0", "#3A6A99", "#07101A");
    /** Space: a night city, the buildings dark at the foot and lit pale toward the roof line. */
    private static final String SPACE_LIGHT_COLOR = "#DDEFFF";
    private static final float SPACE_GLASS_OPACITY = 0.8f;
    private static final float SPACE_LIGHT = 0.35f;

    private NavMapColors() {
    }

    /** Repaints a freshly loaded style. Before the app's own layers are added. */
    static void apply(Style style, boolean dark) {
        apply(style, dark, false);
    }

    /** As {@link #apply(Style, boolean)}; {@code space} paints the space scheme (on the dark style). */
    static void apply(Style style, boolean dark, boolean space) {
        // the 3D buildings' walls stay near their roofs' colour: soft blocks, not dark slabs
        org.maplibre.android.style.light.Light light = style.getLight();
        if (light != null) {
            light.setIntensity(space ? SPACE_LIGHT : BUILDING_LIGHT);
            if (space) {
                light.setColor(SPACE_LIGHT_COLOR);
            }
        }
        Palette palette = space ? SPACE : dark ? NIGHT : DAY;
        for (Layer layer : style.getLayers()) {
            String paint = paintFor(layer.getId(), palette, dark || space);
            if (paint == null) {
                continue;
            }
            if (layer instanceof BackgroundLayer) {
                layer.setProperties(PropertyFactory.backgroundColor(paint));
            } else if (layer instanceof FillExtrusionLayer) {
                if (space) {
                    layer.setProperties(PropertyFactory.fillExtrusionColor(glass()),
                            PropertyFactory.fillExtrusionOpacity(SPACE_GLASS_OPACITY),
                            PropertyFactory.fillExtrusionVerticalGradient(true));
                } else {
                    layer.setProperties(PropertyFactory.fillExtrusionColor(paint));
                }
            } else if (layer instanceof FillLayer) {
                layer.setProperties(paint.startsWith("[") ? PropertyFactory.fillColor(Expression.raw(paint))
                        : PropertyFactory.fillColor(paint));
            } else if (layer instanceof LineLayer) {
                layer.setProperties(paint.startsWith("[") ? PropertyFactory.lineColor(Expression.raw(paint))
                        : PropertyFactory.lineColor(paint));
            } else if (layer instanceof SymbolLayer) {
                layer.setProperties(PropertyFactory.textColor(paint),
                        PropertyFactory.textHaloColor(palette.halo));
            }
        }
    }

    /** The colour (or, starting with {@code [}, the colour expression) for a layer of the style; null = leave it. */
    static String paintFor(String id, boolean dark) {
        return paintFor(id, dark ? NIGHT : DAY, dark);
    }

    private static String paintFor(String id, Palette p, boolean dark) {
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

    /** Space: taller buildings are lit brighter, so the skyline stands out of the night. */
    private static Expression glass() {
        return Expression.interpolate(Expression.linear(), Expression.coalesce(Expression.get("height"), Expression.literal(4)),
                Expression.stop(0, Expression.color(0xFF0C1C2A)),
                Expression.stop(40, Expression.color(0xFF173A4E)),
                Expression.stop(120, Expression.color(0xFF2E6F86)),
                Expression.stop(300, Expression.color(0xFF8FD3E3)));
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
