/*
 * Copyright (c) 2010-2013 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.gis.spatial.index;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Point;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.gis.spatial.SimplePointLayer;
import org.neo4j.gis.spatial.SpatialDatabaseRecord;
import org.neo4j.gis.spatial.SpatialDatabaseService;
import org.neo4j.gis.spatial.encoders.SimplePointEncoder;
import org.neo4j.gis.spatial.pipes.GeoPipeFlow;
import org.neo4j.gis.spatial.rtree.filter.SearchAll;
import org.neo4j.gis.spatial.rtree.filter.SearchResults;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.test.TestGraphDatabaseFactory;

import java.util.List;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

public abstract class LayerIndexTestBase {

    protected GraphDatabaseService graph;
    protected SpatialDatabaseService spatial;
    protected GeometryFactory geometryFactory = new GeometryFactory();
    protected SimplePointEncoder encoder = new SimplePointEncoder();

    protected abstract SimplePointLayer createSimplePointLayer(String name);

    protected abstract SpatialIndexWriter mockLayerIndex();

    protected void addSimplePoint(SpatialIndexWriter index, double x, double y) {
        try (Transaction tx = graph.beginTx()) {
            Node geomNode = graph.createNode();
            Point point = geometryFactory.createPoint(new Coordinate(x, y));
            geomNode.setProperty("x", 1.0D);
            geomNode.setProperty("y", 1.0D);
            encoder.encodeGeometry(point, geomNode);
            index.add(geomNode);
            tx.success();
        }
    }

    @Before
    public void setup() {
        graph = new TestGraphDatabaseFactory().newImpermanentDatabase();
        spatial = new SpatialDatabaseService(graph);
    }

    @Test
    public void shouldCreateAndFindIndexViaLayer() {
        SimplePointLayer layer = createSimplePointLayer("test");
        LayerIndexReader index = layer.getIndex();
        assertThat("Should find the same index", index.getLayer().getName(), equalTo(spatial.getLayer("test").getName()));
        assertThat("Index should be of right type", spatial.getLayer("test").getIndex().getClass(), equalTo(LayerRTreeIndex.class));
    }

    @Test
    public void shouldFindNodeAddedToIndexViaLayer() {
        SimplePointLayer layer = createSimplePointLayer("test");
        SpatialDatabaseRecord added = layer.add(1.0, 1.0);
        try (Transaction tx = graph.beginTx()) {
            List<GeoPipeFlow> found = layer.findClosestPointsTo(new Coordinate(1.0, 1.0), 0.5);
            assertThat("Should find one geometry node", found.size(), equalTo(1));
            assertThat("Should find same geometry node", added.getGeomNode(), equalTo(found.get(0).getGeomNode()));
            tx.success();
        }
    }

    @Test
    public void shouldFindNodeAddedDirectlyToIndex() {
        SpatialIndexWriter index = mockLayerIndex();
        addSimplePoint(index, 1.0, 1.0);
        try (Transaction tx = graph.beginTx()) {
            SearchResults results = index.searchIndex(new SearchAll());
            assertThat("Index should contain one result", results.count(), equalTo(1));
            assertThat("Should find correct Geometry", encoder.decodeGeometry(results.iterator().next()), equalTo(geometryFactory.createPoint(new Coordinate(1.0, 1.0))));
            tx.success();
        }
    }
}