/**
 * Copyright (c) 2010-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 * <p>
 * This file is part of Neo4j Spatial.
 * <p>
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.gis.spatial.procedures;

import com.vividsolutions.jts.geom.*;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.io.ParseException;
import com.vividsolutions.jts.io.WKTReader;
import org.neo4j.cypher.internal.compiler.v3_2.GeographicPoint;
import org.neo4j.gis.spatial.*;
import org.neo4j.gis.spatial.encoders.SimpleGraphEncoder;
import org.neo4j.gis.spatial.encoders.SimplePointEncoder;
import org.neo4j.gis.spatial.encoders.SimplePropertyEncoder;
import org.neo4j.gis.spatial.index.LayerGeohashPointIndex;
import org.neo4j.gis.spatial.osm.OSMGeometryEncoder;
import org.neo4j.gis.spatial.osm.OSMImporter;
import org.neo4j.gis.spatial.pipes.GeoPipeFlow;
import org.neo4j.gis.spatial.pipes.GeoPipeline;
import org.neo4j.gis.spatial.rtree.ProgressLoggingListener;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.PropertyContainer;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.spatial.CRS;
import org.neo4j.kernel.api.proc.ProcedureSignature;
import org.neo4j.kernel.impl.proc.Procedures;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.logging.Log;
import org.neo4j.procedure.*;

import static org.neo4j.gis.spatial.SpatialDatabaseService.RTREE_INDEX_NAME;
import static org.neo4j.procedure.Mode.*;
import org.opengis.referencing.ReferenceIdentifier;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import javax.xml.stream.XMLStreamException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/*
TODO:
* don't pass raw coordinates, take an object which can be a property-container, geometry-point or a map
* optional default simplePointLayer should use the long form of "latitude and longitude" like the spatial functions do
*/

public class SpatialProcedures {

    public static final String DISTANCE = "OrthodromicDistance";
    @Context
    public GraphDatabaseService db;

    @Context
    public Log log;

    public static class NodeResult {
        public final Node node;

        public NodeResult(Node node) {
            this.node = node;
        }
    }

    public static class CountResult {
        public final long count;

        public CountResult(long count) {
            this.count = count;
        }
    }

    public static class NameResult {
        public final String name;
        public final String signature;

        public NameResult(String name, String signature) {
            this.name = name;
            this.signature = signature;
        }
    }

    public static class StringResult {
        public final String name;

        public StringResult(String name) {
            this.name = name;
        }
    }

    public static class NodeDistanceResult {
        public final Node node;
        public final double distance;

        public NodeDistanceResult(Node node, double distance) {
            this.node = node;
            this.distance = distance;
        }
    }

    public static class GeometryResult {
        public final Object geometry;

        public GeometryResult(Object geometry) {
            this.geometry = geometry;
        }
    }

    private static Map<String, Class> encoderClasses = new HashMap<>();

    static {
        populateEncoderClasses();
    }

    private static void populateEncoderClasses() {
        encoderClasses.clear();
        // TODO: Make this auto-find classes that implement GeometryEncoder
        for (Class cls : new Class[]{
                SimplePointEncoder.class, OSMGeometryEncoder.class, SimplePropertyEncoder.class,
                WKTGeometryEncoder.class, WKBGeometryEncoder.class, SimpleGraphEncoder.class
        }) {
            if (GeometryEncoder.class.isAssignableFrom(cls)) {
                String name = cls.getSimpleName();
                encoderClasses.put(name, cls);
            }
        }
    }

    @Procedure("spatial.procedures")
    @Description("Lists all spatial procedures with name and signature")
    public Stream<NameResult> listProcedures() {
        Procedures procedures = ((GraphDatabaseAPI) db).getDependencyResolver().resolveDependency(Procedures.class);
        Stream.Builder<NameResult> builder = Stream.builder();
        for (ProcedureSignature proc : procedures.getAllProcedures()) {
            if (proc.name().namespace()[0].equals("spatial")) {
                builder.accept(new NameResult(proc.name().toString(), proc.toString()));
            }
        }
        return builder.build();
    }

    @Procedure(value="spatial.layers", mode = WRITE)
    @Description("Returns name, and details for all layers")
    public Stream<NameResult> getAllLayers() {
        Stream.Builder<NameResult> builder = Stream.builder();
        SpatialDatabaseService spatial = wrap(db);
        for (String name : spatial.getLayerNames()) {
            Layer layer = spatial.getLayer(name);
            if (layer != null) {
                builder.accept(new NameResult(name, layer.getSignature()));
            }
        }
        return builder.build();
    }

    @Procedure("spatial.layerTypes")
    @Description("Returns the different registered layer types")
    public Stream<NameResult> getAllLayerTypes() {
        Stream.Builder<NameResult> builder = Stream.builder();
        for (Map.Entry<String, String> entry : wrap(db).getRegisteredLayerTypes().entrySet()) {
            builder.accept(new NameResult(entry.getKey(), entry.getValue()));
        }
        return builder.build();
    }

    @Procedure(value="spatial.addPointLayer", mode=WRITE)
    @Description("Adds a new simple point layer, returns the layer root node")
    public Stream<NodeResult> addSimplePointLayer(
            @Name("name") String name,
            @Name(value = "indexType", defaultValue = RTREE_INDEX_NAME) String indexType,
            @Name(value = "crsName", defaultValue = UNSET_CRS_NAME) String crsName) {
        SpatialDatabaseService sdb = wrap(db);
        Layer layer = sdb.getLayer(name);
        if (layer == null) {
            return streamNode(sdb.createLayer(name, SimplePointEncoder.class, SimplePointLayer.class,
                    sdb.resolveIndexClass(indexType), null,
                    selectCRS(crsName)).getLayerNode());
        } else {
            throw new IllegalArgumentException("Cannot create existing layer: " + name);
        }
    }

    @Procedure(value="spatial.addPointLayerGeohash", mode=WRITE)
    public Stream<NodeResult> addSimplePointLayerGeohash(
            @Name("name") String name,
            @Name(value = "crsName", defaultValue = WGS84_CRS_NAME) String crsName) {
        SpatialDatabaseService sdb = wrap(db);
        Layer layer = sdb.getLayer(name);
        if (layer == null) {
            return streamNode(sdb.createLayer(name, SimplePointEncoder.class, SimplePointLayer.class,
                    LayerGeohashPointIndex.class, null,
                    selectCRS(crsName)).getLayerNode());
        } else {
            throw new IllegalArgumentException("Cannot create existing layer: " + name);
        }
    }

    @Procedure(value="spatial.addPointLayerXY", mode=WRITE)
    @Description("Adds a new simple point layer with the given properties for x and y coordinates, returns the layer root node")
    public Stream<NodeResult> addSimplePointLayer(
            @Name("name") String name,
            @Name("xProperty") String xProperty,
            @Name("yProperty") String yProperty,
            @Name(value = "indexType", defaultValue = RTREE_INDEX_NAME) String indexType,
            @Name(value = "crsName", defaultValue = UNSET_CRS_NAME) String crsName) {
        SpatialDatabaseService sdb = wrap(db);
        Layer layer = sdb.getLayer(name);
        if (layer == null) {
            if (xProperty != null && yProperty != null) {
                return streamNode(sdb.createLayer(name, SimplePointEncoder.class, SimplePointLayer.class,
                        sdb.resolveIndexClass(indexType), sdb.makeEncoderConfig(xProperty, yProperty),
                        selectCRS(hintCRSName(crsName, yProperty))).getLayerNode());
            } else {
                throw new IllegalArgumentException("Cannot create layer '" + name + "': Missing encoder config values: xProperty[" + xProperty + "], yProperty[" + yProperty + "]");
            }
        } else {
            throw new IllegalArgumentException("Cannot create existing layer: " + name);
        }
    }

    @Procedure(value="spatial.addPointLayerWithConfig", mode=WRITE)
    @Description("Adds a new simple point layer with the given configuration, returns the layer root node")
    public Stream<NodeResult> addSimplePointLayerWithConfig(
            @Name("name") String name,
            @Name("encoderConfig") String encoderConfig,
            @Name(value = "indexType", defaultValue = RTREE_INDEX_NAME) String indexType,
            @Name(value = "crsName", defaultValue = UNSET_CRS_NAME) String crsName) {
        SpatialDatabaseService sdb = wrap(db);
        Layer layer = sdb.getLayer(name);
        if (layer == null) {
            if (encoderConfig.indexOf(':') > 0) {
                return streamNode(sdb.createLayer(name, SimplePointEncoder.class, SimplePointLayer.class,
                        sdb.resolveIndexClass(indexType), encoderConfig,
                        selectCRS(hintCRSName(crsName, encoderConfig))).getLayerNode());
            } else {
                throw new IllegalArgumentException("Cannot create layer '" + name + "': invalid encoder config '" + encoderConfig + "'");
            }
        } else {
            throw new IllegalArgumentException("Cannot create existing layer: " + name);
        }
    }

    public static final String UNSET_CRS_NAME = "";
    public static final String WGS84_CRS_NAME = "wgs84";

    /**
     * Currently this only supports the string 'WGS84', for the convenience of procedure users.
     * This should be expanded with CRS table lookup.
     * @param name
     * @return null or WGS84
     */
    public CoordinateReferenceSystem selectCRS(String name) {
        if (name == null) {
            return null;
        } else {
            switch (name.toLowerCase()) {
                case WGS84_CRS_NAME:
                    return org.geotools.referencing.crs.DefaultGeographicCRS.WGS84;
                case UNSET_CRS_NAME:
                    return null;
                default:
                    throw new IllegalArgumentException("Unsupported CRS name: " + name);
            }
        }
    }

    private String hintCRSName(String crsName, String hint) {
        if (crsName.equals(UNSET_CRS_NAME) && hint.toLowerCase().contains("lat")) {
            crsName = WGS84_CRS_NAME;
        }
        return crsName;
    }

    @Procedure(value="spatial.addLayerWithEncoder", mode=WRITE)
    @Description("Adds a new layer with the given encoder class and configuration, returns the layer root node")
    public Stream<NodeResult> addLayerWithEncoder(
            @Name("name") String name,
            @Name("encoder") String encoderClassName,
            @Name("encoderConfig") String encoderConfig) {
        SpatialDatabaseService sdb = wrap(db);
        Layer layer = sdb.getLayer(name);
        if (layer == null) {
            Class encoderClass = encoderClasses.get(encoderClassName);
            Class layerClass = sdb.suggestLayerClassForEncoder(encoderClass);
            if (encoderClass != null) {
                return streamNode(sdb.createLayer(name, encoderClass, layerClass, null, encoderConfig).getLayerNode());
            } else {
                throw new IllegalArgumentException("Cannot create layer '" + name + "': invalid encoder class '" + encoderClassName + "'");
            }
        } else {
            throw new IllegalArgumentException("Cannot create existing layer: " + name);
        }
    }

    @Procedure(value="spatial.addLayer", mode=WRITE)
    @Description("Adds a new layer with the given type (see spatial.getAllLayerTypes) and configuration, returns the layer root node")
    public Stream<NodeResult> addLayerOfType(
            @Name("name") String name,
            @Name("type") String type,
            @Name("encoderConfig") String encoderConfig) {
        SpatialDatabaseService sdb = wrap(db);
        Layer layer = sdb.getLayer(name);
        if (layer == null) {
            Map<String, String> knownTypes = sdb.getRegisteredLayerTypes();
            if (knownTypes.containsKey(type.toLowerCase())) {
                return streamNode(sdb.getOrCreateRegisteredTypeLayer(name, type, encoderConfig).getLayerNode());
            } else {
                throw new IllegalArgumentException("Cannot create layer '" + name + "': unknown type '" + type + "' - supported types are " + knownTypes.toString());
            }
        } else {
            throw new IllegalArgumentException("Cannot create existing layer: " + name);
        }
    }

    private Stream<NodeResult> streamNode(Node node) {
        return Stream.of(new NodeResult(node));
    }

    @Procedure(value="spatial.addWKTLayer", mode=WRITE)
    @Description("Adds a new WKT layer with the given node property to hold the WKT string, returns the layer root node")
    public Stream<NodeResult> addWKTLayer(@Name("name") String name,
                                          @Name("nodePropertyName") String nodePropertyName) {
        return addLayerOfType(name, "WKT", nodePropertyName);
    }

    @Procedure(value="spatial.layer", mode=WRITE)
    @Description("Returns the layer root node for the given layer name")
    public Stream<NodeResult> getLayer(@Name("name") String name) {
        return streamNode(getLayerOrThrow(name).getLayerNode());
    }

    @Procedure(value="spatial.getFeatureAttributes", mode=WRITE)
    @Description("Returns feature attributes of the given layer")
    public Stream<StringResult> getFeatureAttributes(@Name("name") String name) {
        Layer layer = this.getLayerOrThrow(name);
        return Arrays.asList(layer.getExtraPropertyNames()).stream().map(StringResult::new);
    }

    @Procedure(value="spatial.setFeatureAttributes", mode=WRITE)
    @Description("Sets the feature attributes of the given layer")
    public Stream<NodeResult> setFeatureAttributes(@Name("name") String name,
                                                   @Name("attributeNames") List<String> attributeNames) {
        EditableLayerImpl layer = this.getEditableLayerOrThrow(name);
        layer.setExtraPropertyNames(attributeNames.toArray(new String[attributeNames.size()]));
        return streamNode(layer.getLayerNode());
    }

    @Procedure(value="spatial.removeLayer", mode=WRITE)
    @Description("Removes the given layer")
    public void removeLayer(@Name("name") String name) {
        wrap(db).deleteLayer(name, new ProgressLoggingListener("Deleting layer '" + name + "'", log.infoLogger()));
    }

    @Procedure(value="spatial.addNode", mode=WRITE)
    @Description("Adds the given node to the layer, returns the geometry-node")
    public Stream<NodeResult> addNodeToLayer(@Name("layerName") String name, @Name("node") Node node) {
        EditableLayer layer = getEditableLayerOrThrow(name);
        return streamNode(layer.add(node).getGeomNode());
    }

    @Procedure(value="spatial.addNodes", mode=WRITE)
    @Description("Adds the given nodes list to the layer, returns the count")
    public Stream<CountResult> addNodesToLayer(@Name("layerName") String name, @Name("nodes") List<Node> nodes) {
        EditableLayer layer = getEditableLayerOrThrow(name);
        return Stream.of(new CountResult(layer.addAll(nodes)));
    }

    @Procedure(value="spatial.addWKT", mode=WRITE)
    @Description("Adds the given WKT string to the layer, returns the created geometry node")
    public Stream<NodeResult> addGeometryWKTToLayer(@Name("layerName") String name, @Name("geometry") String geometryWKT) throws ParseException {
        EditableLayer layer = getEditableLayerOrThrow(name);
        WKTReader reader = new WKTReader(layer.getGeometryFactory());
        return streamNode(addGeometryWkt(layer, reader, geometryWKT));
    }

    @Procedure(value="spatial.addWKTs", mode=WRITE)
    @Description("Adds the given WKT string list to the layer, returns the created geometry nodes")
    public Stream<NodeResult> addGeometryWKTsToLayer(@Name("layerName") String name, @Name("geometry") List<String> geometryWKTs) throws ParseException {
        EditableLayer layer = getEditableLayerOrThrow(name);
        WKTReader reader = new WKTReader(layer.getGeometryFactory());
        return geometryWKTs.stream().map(geometryWKT -> addGeometryWkt(layer, reader, geometryWKT)).map(NodeResult::new);
    }

    private Node addGeometryWkt(EditableLayer layer, WKTReader reader, String geometryWKT) {
        try {
            Geometry geometry = reader.read(geometryWKT);
            return layer.add(geometry).getGeomNode();
        } catch (ParseException e) {
            throw new RuntimeException("Error parsing geometry: " + geometryWKT, e);
        }
    }

    @Procedure(value="spatial.importShapefileToLayer", mode=WRITE)
    @Description("Imports the the provided shape-file from URI to the given layer, returns the count of data added")
    public Stream<CountResult> importShapefile(
            @Name("layerName") String name,
            @Name("uri") String uri) throws IOException {
        EditableLayerImpl layer = getEditableLayerOrThrow(name);
        return Stream.of(new CountResult(importShapefileToLayer(uri, layer, 1000).size()));
    }

    @Procedure(value="spatial.importShapefile", mode=WRITE)
    @Description("Imports the the provided shape-file from URI to a layer of the same name, returns the count of data added")
    public Stream<CountResult> importShapefile(
            @Name("uri") String uri) throws IOException {
        return Stream.of(new CountResult(importShapefileToLayer(uri, null, 1000).size()));
    }

    private List<Node> importShapefileToLayer(String shpPath, EditableLayerImpl layer, int commitInterval) throws IOException {
        if (shpPath.toLowerCase().endsWith(".shp")) {
            // remove extension
            shpPath = shpPath.substring(0, shpPath.lastIndexOf("."));
        }

        ShapefileImporter importer = new ShapefileImporter(db, new ProgressLoggingListener("Importing " + shpPath, log.debugLogger()), commitInterval);
        if (layer == null) {
            String layerName = shpPath.substring(shpPath.lastIndexOf(File.separator) + 1);
            return importer.importFile(shpPath, layerName);
        } else {
            return importer.importFile(shpPath, layer, Charset.defaultCharset());
        }
    }

    @Procedure(value="spatial.importOSMToLayer", mode=WRITE)
    @Description("Imports the the provided osm-file from URI to a layer, returns the count of data added")
    public Stream<CountResult> importOSM(
            @Name("layerName") String name,
            @Name("uri") String uri) throws IOException, XMLStreamException {
        EditableLayerImpl layer = getEditableLayerOrThrow(name);
        return Stream.of(new CountResult(importOSMToLayer(uri, layer, 1000)));
    }

    @Procedure(value="spatial.importOSM", mode=WRITE)
    @Description("Imports the the provided osm-file from URI to a layer of the same name, returns the count of data added")
    public Stream<CountResult> importOSM(
            @Name("uri") String uri) throws IOException, XMLStreamException {
        return Stream.of(new CountResult(importOSMToLayer(uri, null, 1000)));
    }

    private long importOSMToLayer(String osmPath, EditableLayerImpl layer, int commitInterval) throws IOException, XMLStreamException {
        if (!osmPath.toLowerCase().endsWith(".osm")) {
            // add extension
            osmPath = osmPath + ".osm";
        }

        String layerName = (layer == null) ? osmPath.substring(osmPath.lastIndexOf(File.separator) + 1) : layer.getName();
        OSMImporter importer = new OSMImporter(layerName, new ProgressLoggingListener("Importing " + osmPath, log.debugLogger()));
        importer.importFile( db, osmPath, false, commitInterval, true );
        return importer.reIndex( db, commitInterval );
    }

    @Procedure(value="spatial.bbox", mode=WRITE)
    @Description("Finds all geometry nodes in the given layer within the lower left and upper right coordinates of a box")
    public Stream<NodeResult> findGeometriesInBBox(
            @Name("layerName") String name,
            @Name("min") Object min,
            @Name("max") Object max) {
        Layer layer = getLayerOrThrow(name);
        // TODO why a SearchWithin and not a SearchIntersectWindow?
        Envelope envelope = new Envelope(toCoordinate(min), toCoordinate(max));
        return GeoPipeline
                .startWithinSearch(layer, layer.getGeometryFactory().toGeometry(envelope))
                .stream().map(GeoPipeFlow::getGeomNode).map(NodeResult::new);
    }

    @Procedure(value="spatial.closest", mode=WRITE)
    @Description("Finds all geometry nodes in the layer within the distance to the given coordinate")
    public Stream<NodeResult> findClosestGeometries(
            @Name("layerName") String name,
            @Name("coordinate") Object coordinate,
            @Name("distanceInKm") double distanceInKm) {
        Layer layer = getLayerOrThrow(name);
        GeometryFactory factory = layer.getGeometryFactory();
        Point point = factory.createPoint(toCoordinate(coordinate));
        List<SpatialTopologyUtils.PointResult> edgeResults = SpatialTopologyUtils.findClosestEdges(point, layer, distanceInKm);
        return edgeResults.stream().map(e -> e.getValue().getGeomNode()).map(NodeResult::new);
    }

    @Procedure(value="spatial.withinDistance", mode=WRITE)
    @Description("Returns all geometry nodes and their ordered distance in the layer within the distance to the given coordinate")
    public Stream<NodeDistanceResult> findGeometriesWithinDistance(
            @Name("layerName") String name,
            @Name("coordinate") Object coordinate,
            @Name("distanceInKm") double distanceInKm) {

        Layer layer = getLayerOrThrow(name);
        return GeoPipeline
                .startNearestNeighborLatLonSearch(layer, toCoordinate(coordinate), distanceInKm)
                .sort(DISTANCE)
                .stream().map(r -> {
                    double distance = r.hasProperty(DISTANCE) ? ((Number) r.getProperty(DISTANCE)).doubleValue() : -1;
                    return new NodeDistanceResult(r.getGeomNode(), distance);
                });
    }

    @Procedure("spatial.decodeGeometry")
    @Description("Returns a geometry of a layer node as internal cypher geometry type, to be passed to other procedures but not returned to a client")
    // TODO: This currently returns an internal Cypher type, in order to be able to pass back into
    // other procedures that only accept internal cypher types due to a bug in Neo4j 3.0
    // If you need to return Geometries outside (eg. RETURN geometry), then consider spatial.asExternalGeometry(geometry)
    public Stream<GeometryResult> decodeGeometry(
            @Name("layerName") String name,
            @Name("node") Node node) {

        Layer layer = getLayerOrThrow(name);
        return Stream.of(layer.getGeometryEncoder().decodeGeometry(node)).map(geom -> new GeometryResult(toCypherGeometry(layer, geom)));
    }

    @Procedure("spatial.asGeometry")
    @Description("Returns a geometry object as an internal cypher geometry type, to be passed to other procedures but not returned to a client")
    // TODO: This currently returns an internal Cypher type, in order to be able to pass back into
    // other procedures that only accept internal cypher types due to a bug in Neo4j 3.0
    // If you need to return Geometries outside (eg. RETURN geometry), then consider spatial.asExternalGeometry(geometry)
    public Stream<GeometryResult> asGeometry(
            @Name("geometry") Object geometry) {

        return Stream.of(geometry).map(geom -> new GeometryResult(toCypherGeometry(null, geom)));
    }

    @Procedure("spatial.asExternalGeometry")
    @Description("Returns a geometry object as an external geometry type to be returned to a client")
    // TODO: This method only exists (and differs from spatial.asGeometry()) because of a bug in Cypher 3.0
    // Cypher will emit external geometry types but can only consume internal types. Once that bug is fixed,
    // We can make both asGeometry() and asExternalGeometry() return the same public type, and deprecate this procedure.
    public Stream<GeometryResult> asExternalGeometry(
            @Name("geometry") Object geometry) {

        return Stream.of(geometry).map(geom -> new GeometryResult(toNeo4jGeometry(null, geom)));
    }

    @Procedure(value="spatial.intersects", mode=WRITE)
    @Description("Returns all geometry nodes that intersect the given geometry (shape, polygon) in the layer")
    public Stream<NodeResult> findGeometriesIntersecting(
            @Name("layerName") String name,
            @Name("geometry") Object geometry) {

        Layer layer = getLayerOrThrow(name);
        return GeoPipeline
                .startIntersectSearch(layer, toJTSGeometry(layer, geometry))
                .stream().map(GeoPipeFlow::getGeomNode).map(NodeResult::new);
    }

    private Geometry toJTSGeometry(Layer layer, Object value) {
        GeometryFactory factory = layer.getGeometryFactory();
        if (value instanceof org.neo4j.graphdb.spatial.Point) {
            org.neo4j.graphdb.spatial.Point point = (org.neo4j.graphdb.spatial.Point) value;
            List<Double> coord = point.getCoordinate().getCoordinate();
            return factory.createPoint(new Coordinate(coord.get(0), coord.get(1)));
        }
        if (value instanceof String) {
            WKTReader reader = new WKTReader(factory);
            try {
                return reader.read((String) value);
            } catch (ParseException e) {
                throw new IllegalArgumentException("Invalid WKT: " + e.getMessage());
            }
        }
        Map<String, Object> latLon = null;
        if (value instanceof PropertyContainer) {
            latLon = ((PropertyContainer) value).getProperties("latitude", "longitude", "lat", "lon");
        }
        if (value instanceof Map) latLon = (Map<String, Object>) value;
        Coordinate coord = toCoordinate(latLon);
        if (coord != null) return factory.createPoint(coord);
        throw new RuntimeException("Can't convert " + value + " to a geometry");
    }

    public static class Neo4jGeometry implements org.neo4j.graphdb.spatial.Geometry {
        protected final String geometryType;
        protected final CRS crs;
        protected final List<org.neo4j.graphdb.spatial.Coordinate> coordinates;

        public Neo4jGeometry(String geometryType, List<org.neo4j.graphdb.spatial.Coordinate> coordinates, CRS crs) {
            this.geometryType = geometryType;
            this.coordinates = coordinates;
            this.crs = crs;
        }

        public String getGeometryType() {
            return this.geometryType;
        }

        public List<org.neo4j.graphdb.spatial.Coordinate> getCoordinates() {
            return this.coordinates;
        }

        public CRS getCRS() {
            return this.crs;
        }

        public static String coordinateString(List<org.neo4j.graphdb.spatial.Coordinate> coordinates) {
            return coordinates.stream().map(c -> c.getCoordinate().stream().map(v -> v.toString()).collect(Collectors.joining(", "))).collect(Collectors.joining(", "));
        }

        public String toString() {
            return geometryType + "(" + coordinateString(coordinates) + ")[" + crs + "]";
        }
    }

    public static class Neo4jPoint extends Neo4jGeometry implements org.neo4j.graphdb.spatial.Point {
        private final org.neo4j.graphdb.spatial.Coordinate coordinate;

        public Neo4jPoint(double x, double y, CRS crs) {
            super("Point", new ArrayList(), crs);
            this.coordinate = new org.neo4j.graphdb.spatial.Coordinate(new double[]{x, y});
            this.coordinates.add(this.coordinate);
        }
    }

    private org.neo4j.graphdb.spatial.Coordinate toNeo4jCoordinate(Coordinate coordinate) {
        if (coordinate.z == Coordinate.NULL_ORDINATE) {
            return new org.neo4j.graphdb.spatial.Coordinate(coordinate.x, coordinate.y);
        } else {
            return new org.neo4j.graphdb.spatial.Coordinate(coordinate.x, coordinate.y, coordinate.z);
        }
    }

    private List<org.neo4j.graphdb.spatial.Coordinate> toNeo4jCoordinates(Coordinate[] coordinates) {
        ArrayList<org.neo4j.graphdb.spatial.Coordinate> converted = new ArrayList<>();
        for (Coordinate coordinate : coordinates) {
            converted.add(toNeo4jCoordinate(coordinate));
        }
        return converted;
    }

    private org.neo4j.graphdb.spatial.Geometry toNeo4jGeometry(Layer layer, Object value) {
        if (value instanceof org.neo4j.graphdb.spatial.Geometry) {
            return (org.neo4j.graphdb.spatial.Geometry) value;
        }
        CRS crs = findCRS("Cartesian");
        if (layer != null) {
            CoordinateReferenceSystem layerCRS = layer.getCoordinateReferenceSystem();
            if (layerCRS != null) {
                ReferenceIdentifier crsRef = layer.getCoordinateReferenceSystem().getName();
                crs = findCRS(crsRef.toString());
            }
        }
        if (value instanceof Point) {
            Point point = (Point) value;
            return new Neo4jPoint(point.getX(), point.getY(), crs);
        }
        if (value instanceof Geometry) {
            Geometry geometry = (Geometry) value;
            return new Neo4jGeometry(geometry.getGeometryType(), toNeo4jCoordinates(geometry.getCoordinates()), crs);
        }
        if (value instanceof String && layer != null) {
            GeometryFactory factory = layer.getGeometryFactory();
            WKTReader reader = new WKTReader(factory);
            try {
                Geometry geometry = reader.read((String) value);
                return new Neo4jGeometry(geometry.getGeometryType(), toNeo4jCoordinates(geometry.getCoordinates()), crs);
            } catch (ParseException e) {
                throw new IllegalArgumentException("Invalid WKT: " + e.getMessage());
            }
        }
        Map<String, Object> latLon = null;
        if (value instanceof PropertyContainer) {
            latLon = ((PropertyContainer) value).getProperties("latitude", "longitude", "lat", "lon");
        }
        if (value instanceof Map) latLon = (Map<String, Object>) value;
        Coordinate coord = toCoordinate(latLon);
        if (coord != null) return new Neo4jPoint(coord.x, coord.y, crs);
        throw new RuntimeException("Can't convert " + value + " to a geometry");
    }

    private org.neo4j.cypher.internal.compiler.v3_2.Geometry makeCypherGeometry(double x, double y, org.neo4j.cypher.internal.compiler.v3_2.CRS crs) {
        if (crs.equals(org.neo4j.cypher.internal.compiler.v3_2.CRS.Cartesian())) {
            return new org.neo4j.cypher.internal.compiler.v3_2.CartesianPoint(x, y, crs);
        } else {
            return new org.neo4j.cypher.internal.compiler.v3_2.GeographicPoint(x, y, crs);
        }
    }

    private org.neo4j.cypher.internal.compiler.v3_2.Geometry makeCypherGeometry(Geometry geometry, org.neo4j.cypher.internal.compiler.v3_2.CRS crs) {
        if (geometry.getGeometryType().toLowerCase().equals("point")) {
            Coordinate coordinate = geometry.getCoordinates()[0];
            return makeCypherGeometry(coordinate.getOrdinate(0), coordinate.getOrdinate(1), crs);
        } else {
            throw new RuntimeException("Cypher only accepts POINT geometries, not " + geometry.getGeometryType());
        }
    }

    private org.neo4j.cypher.internal.compiler.v3_2.Geometry toCypherGeometry(Layer layer, Object value) {
        if (value instanceof org.neo4j.cypher.internal.compiler.v3_2.Geometry) {
            return (org.neo4j.cypher.internal.compiler.v3_2.Geometry) value;
        }
        if (value instanceof org.neo4j.graphdb.spatial.Point) {
            org.neo4j.graphdb.spatial.Point point = (org.neo4j.graphdb.spatial.Point) value;
            List<Double> coord = point.getCoordinate().getCoordinate();
            return makeCypherGeometry(coord.get(0), coord.get(1), org.neo4j.cypher.internal.compiler.v3_2.CRS.fromSRID(point.getCRS().getCode()));
        }
        org.neo4j.cypher.internal.compiler.v3_2.CRS crs = org.neo4j.cypher.internal.compiler.v3_2.CRS.Cartesian();
        if (layer != null) {
            CoordinateReferenceSystem layerCRS = layer.getCoordinateReferenceSystem();
            if (layerCRS != null) {
                ReferenceIdentifier crsRef = layer.getCoordinateReferenceSystem().getName();
                crs = org.neo4j.cypher.internal.compiler.v3_2.CRS.fromName(crsRef.toString());
            }
        }
        if (value instanceof Geometry) {
            Geometry geometry = (Geometry) value;
            if (geometry.getSRID() > 0) {
                crs = org.neo4j.cypher.internal.compiler.v3_2.CRS.fromSRID(geometry.getSRID());
            }
            if (geometry instanceof Point) {
                Point point = (Point) geometry;
                return makeCypherGeometry(point.getX(), point.getY(), crs);
            }
            return makeCypherGeometry(geometry, crs);
        }
        if (value instanceof String) {
            GeometryFactory factory = (layer == null) ? new GeometryFactory() : layer.getGeometryFactory();
            WKTReader reader = new WKTReader(factory);
            try {
                Geometry geometry = reader.read((String) value);
                return makeCypherGeometry(geometry, crs);
            } catch (ParseException e) {
                throw new IllegalArgumentException("Invalid WKT: " + e.getMessage());
            }
        }
        Map<String, Object> latLon = null;
        if (value instanceof PropertyContainer) {
            latLon = ((PropertyContainer) value).getProperties("latitude", "longitude", "lat", "lon");
            if (layer == null) {
                crs = org.neo4j.cypher.internal.compiler.v3_2.CRS.WGS84();
            }
        }
        if (value instanceof Map) latLon = (Map<String, Object>) value;
        Coordinate coord = toCoordinate(latLon);
        if (coord != null) return makeCypherGeometry(coord.x, coord.y, crs);
        throw new RuntimeException("Can't convert " + value + " to a geometry");
    }

    private static CRS findCRS(String crs) {
        switch (crs) {
            case "WGS-84":
                return makeCRS(4326, "WGS-84", "http://spatialreference.org/ref/epsg/4326/");
            case "Cartesian":
                return makeCRS(7203, "cartesian", "http://spatialreference.org/ref/sr-org/7203/");
            default:
                throw new IllegalArgumentException("Cypher type system does not support CRS: " + crs);
        }
    }

    private static CRS makeCRS(final int code, final String type, final String href) {
        return new CRS() {
            public int getCode() {
                return code;
            }

            public String getType() {
                return type;
            }

            public String getHref() {
                return href;
            }
        };
    }

    private Coordinate toCoordinate(Object value) {
        if (value instanceof Coordinate) {
            return (Coordinate) value;
        }
        if (value instanceof GeographicPoint) {
            GeographicPoint point = (GeographicPoint) value;
            return new Coordinate(point.x(), point.y());
        }
        if (value instanceof PropertyContainer) {
            return toCoordinate(((PropertyContainer) value).getProperties("latitude", "longitude", "lat", "lon"));
        }
        if (value instanceof Map) {
            return toCoordinate((Map<String, Object>) value);
        }
        throw new RuntimeException("Can't convert " + value + " to a coordinate");
    }

    private Coordinate toCoordinate(Map<String, Object> map) {
        if (map == null) return null;
        Coordinate coord = toCoordinate(map, "longitude", "latitude");
        if (coord == null) return toCoordinate(map, "lon", "lat");
        return coord;
    }

    private Coordinate toCoordinate(Map map, String xName, String yName) {
        if (map.containsKey(xName) && map.containsKey(yName))
            return new Coordinate(((Number) map.get(xName)).doubleValue(), ((Number) map.get(yName)).doubleValue());
        return null;
    }

    private EditableLayerImpl getEditableLayerOrThrow(String name) {
        return (EditableLayerImpl) getLayerOrThrow(wrap(db), name);
    }

    private Layer getLayerOrThrow(String name) {
        return getLayerOrThrow(wrap(db), name);
    }

    private Layer getLayerOrThrow(SpatialDatabaseService spatialService, String name) {
        EditableLayer layer = (EditableLayer) spatialService.getLayer(name);
        if (layer != null) {
            return layer;
        } else {
            throw new IllegalArgumentException("No such layer '" + name + "'");
        }
    }

    private SpatialDatabaseService wrap(GraphDatabaseService db) {
        return new SpatialDatabaseService(db);
    }
}
