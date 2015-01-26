/* Copyright 2014 The Johns Hopkins University Applied Physics Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.jhuapl.tinkerpop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.BatchDeleter;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.MutationsRejectedException;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.admin.TableOperations;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.commons.configuration.Configuration;
import org.apache.hadoop.io.Text;

import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Element;
import com.tinkerpop.blueprints.Features;
import com.tinkerpop.blueprints.Graph;
import com.tinkerpop.blueprints.GraphFactory;
import com.tinkerpop.blueprints.GraphQuery;
import com.tinkerpop.blueprints.Index;
import com.tinkerpop.blueprints.IndexableGraph;
import com.tinkerpop.blueprints.KeyIndexableGraph;
import com.tinkerpop.blueprints.Parameter;
import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.blueprints.util.DefaultGraphQuery;
import com.tinkerpop.blueprints.util.ExceptionFactory;

import edu.jhuapl.tinkerpop.cache.ElementCaches;
import edu.jhuapl.tinkerpop.tables.keyindex.VertexKeyIndexTableWrapper;

/**
 * This is an implementation of the TinkerPop Blueprints 2.6 API using
 * Apache Accumulo as the backend. This combines the many benefits and flexibility
 * of Blueprints with the scalability and performance of Accumulo.
 * 
 * <p/>In addition to the basic Blueprints functionality, we provide a number of
 * enhanced features, including:
 * <ol>
 * <li>Indexing implementations via IndexableGraph and KeyIndexableGraph</li>
 * <li>Support for mock, mini, and distributed instances of Accumulo</li>
 * <li>Numerous performance tweaks and configuration parameters</li>
 * <li>Support for high speed ingest</li>
 * <li>Hadoop integration</li>
 * </ol>
 */
public class AccumuloGraph implements Graph, KeyIndexableGraph, IndexableGraph {

  private GlobalInstances globals;

  /**
   * @deprecated Remove when vertex functionality is gone.
   */
  @Deprecated
  private BatchWriter vertexBW;

  /**
   * Factory method for {@link GraphFactory}.
   */
  public static AccumuloGraph open(Configuration properties) throws AccumuloException {
    return new AccumuloGraph(properties);
  }

  /**
   * Instantiate from a generic {@link Configuration} populated
   * with appropriate AccumuloGraph parameters.
   * @param cfg
   */
  public AccumuloGraph(Configuration cfg) {
    this(new AccumuloGraphConfiguration(cfg));
  }

  /**
   * Main constructor.
   * @param config
   */
  public AccumuloGraph(AccumuloGraphConfiguration config) {
    config.validate();

    AccumuloGraphUtils.handleCreateAndClear(config);

    try {
      globals = new GlobalInstances(this, config,
          config.getConnector().createMultiTableBatchWriter(config
              .getBatchWriterConfig()), new ElementCaches(config));
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    }

    try {
      setupWriters();
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    }
  }

  /**
   * @deprecated This will go away along with {@link #vertexBW}.
   * @throws Exception
   */
  private void setupWriters() throws Exception {
    vertexBW = globals.getMtbw().getBatchWriter(globals.getConfig().getVertexTableName());
  }

  /**
   * @deprecated Move this somewhere appropriate
   * @param type
   * @return
   */
  @Deprecated
  protected Scanner getElementScanner(Class<? extends Element> type) {
    try {
      String tableName = globals.getConfig().getEdgeTableName();
      if (type.equals(Vertex.class))
        tableName = globals.getConfig().getVertexTableName();
      return globals.getConfig().getConnector().createScanner(tableName, globals.getConfig().getAuthorizations());
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    }
  }

  /**
   * @deprecated Move this somewhere appropriate
   * @param tablename
   * @return
   */
  @Deprecated
  protected Scanner getScanner(String tablename) {
    try {
      return globals.getConfig().getConnector().createScanner(tablename,
          globals.getConfig().getAuthorizations());
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    }
  }

  // Aliases for the lazy
  /**
   * @deprecated Move this somewhere appropriate
   * @return
   */
  @Deprecated
  private Scanner getMetadataScanner() {
    return getScanner(globals.getConfig().getIndexNamesTableName());
  }

  /**
   * @deprecated This is used in a unit test that
   * needs to be updated to work with
   * {@link VertexKeyIndexTableWrapper}.
   * @return
   */
  @Deprecated
  public Scanner getVertexIndexScanner() {
    return getScanner(globals.getConfig().getVertexKeyIndexTableName());
  }

  /**
   * @deprecated Move this somewhere appropriate
   * @return
   */
  @Deprecated
  private BatchWriter getVertexIndexWriter() {
    return getWriter(globals.getConfig().getVertexKeyIndexTableName());
  }

  /**
   * @deprecated Move this somewhere appropriate
   * @return
   */
  @Deprecated
  private BatchWriter getEdgeIndexWriter() {
    return getWriter(globals.getConfig().getEdgeKeyIndexTableName());
  }

  /**
   * @deprecated Move this somewhere appropriate
   * @return
   */
  @Deprecated
  public BatchWriter getWriter(String tablename) {
    try {
      return globals.getMtbw().getBatchWriter(tablename);
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    }
  }

  /**
   * @deprecated Move this somewhere appropriate
   * @return
   */
  @Deprecated
  private BatchScanner getElementBatchScanner(Class<? extends Element> type) {
    try {
      String tableName = globals.getConfig().getVertexTableName();
      if (type.equals(Edge.class))
        tableName = globals.getConfig().getEdgeTableName();
      BatchScanner x = globals.getConfig().getConnector().createBatchScanner(tableName,
          globals.getConfig().getAuthorizations(), globals.getConfig().getQueryThreads());
      x.setRanges(Collections.singletonList(new Range()));
      return x;
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    }
  }

  // End Aliases

  @Override
  public Features getFeatures() {
    return AccumuloFeatures.get();
  }

  @Override
  public Vertex addVertex(Object id) {
    if (id == null) {
      id = AccumuloGraphUtils.generateId();
    }

    String myID = id.toString();

    Vertex vert = null;
    if (!globals.getConfig().getSkipExistenceChecks()) {
      vert = getVertex(myID);
      if (vert != null) {
        throw ExceptionFactory.vertexWithIdAlreadyExists(myID);
      }
    }

    vert = new AccumuloVertex(globals, myID);

    globals.getVertexWrapper().writeVertex(vert);
    globals.checkedFlush();

    globals.getCaches().cache(vert, Vertex.class);

    return vert;
  }

  @Override
  public Vertex getVertex(Object id) {
    if (id == null) {
      throw ExceptionFactory.vertexIdCanNotBeNull();
    }
    String myID = id.toString();

    Vertex vertex = globals.getCaches().retrieve(myID, Vertex.class);
    if (vertex != null) {
      return vertex;
    }

    vertex = new AccumuloVertex(globals, myID);
    if (!globals.getConfig().getSkipExistenceChecks()) {
      // In addition to just an "existence" check, we will also load
      // any "preloaded" properties now, which saves us a round-trip
      // to Accumulo later.
      String[] preload = globals.getConfig().getPreloadedProperties();
      if (preload == null) {
        preload = new String[]{};
      }

      Map<String, Object> props = globals.getVertexWrapper().readProperties(vertex, preload);
      if (props == null) {
        return null;
      }

      for (String key : props.keySet()) {
        ((AccumuloElement) vertex).setPropertyInMemory(key, props.get(key));
      }
    }

    globals.getCaches().cache(vertex, Vertex.class);

    return vertex;
  }

  @Override
  public void removeVertex(Vertex vertex) {
    globals.getCaches().remove(vertex.getId(), Vertex.class);

    if (!globals.getConfig().getIndexableGraphDisabled())
      removeElementFromNamedIndexes(vertex);

    Scanner scan = getElementScanner(Vertex.class);
    scan.setRange(new Range(vertex.getId().toString()));

    BatchDeleter edgedeleter = null;
    BatchDeleter vertexdeleter = null;
    BatchWriter indexdeleter = getVertexIndexWriter();
    try {
      // Set up Deleters
      edgedeleter = globals.getConfig().getConnector().createBatchDeleter(globals.getConfig().getEdgeTableName(),
          globals.getConfig().getAuthorizations(), globals.getConfig().getQueryThreads(),
          globals.getConfig().getBatchWriterConfig());
      vertexdeleter = globals.getConfig().getConnector().createBatchDeleter(globals.getConfig().getVertexTableName(),
          globals.getConfig().getAuthorizations(), globals.getConfig().getQueryThreads(),
          globals.getConfig().getBatchWriterConfig());
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    }
    Iterator<Entry<Key,Value>> iter = scan.iterator();
    List<Range> ranges = new ArrayList<Range>();
    if (!iter.hasNext()) {
      throw ExceptionFactory.vertexWithIdDoesNotExist(vertex.getId());
    }
    try {
      // Search for edges
      while (iter.hasNext()) {
        Entry<Key,Value> e = iter.next();
        Key k = e.getKey();

        if (k.getColumnFamily().toString().equals(Constants.OUT_EDGE) ||
            k.getColumnFamily().toString().equals(Constants.IN_EDGE)) {
          ranges.add(new Range(k.getColumnQualifier().toString().split(Constants.ID_DELIM)[1]));

          Mutation vm = new Mutation(k.getColumnQualifier().toString().split(Constants.ID_DELIM)[0]);
          vm.putDelete(invert(k.getColumnFamily()),
              new Text(vertex.getId().toString() + Constants.ID_DELIM + k.getColumnQualifier()
                  .toString().split(Constants.ID_DELIM)[1]));
          vertexBW.addMutation(vm);
        } else {
          Mutation m = new Mutation(e.getValue().get());
          m.putDelete(k.getColumnFamily(), k.getRow());
          indexdeleter.addMutation(m);
        }

      }
      globals.checkedFlush();
      scan.close();

      // If Edges are found, delete the whole row
      if (!ranges.isEmpty()) {
        // TODO don't we also have to propagate these deletes to the
        // vertex index table?
        edgedeleter.setRanges(ranges);
        edgedeleter.delete();
        ranges.clear();
      }
      // Delete the whole vertex row
      ranges.add(new Range(vertex.getId().toString()));
      vertexdeleter.setRanges(ranges);
      vertexdeleter.delete();
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    } finally {
      if (edgedeleter != null)
        edgedeleter.close();
      if (vertexdeleter != null)
        vertexdeleter.close();
    }
  }

  // Maybe an Custom Iterator could make this better.
  /**
   * @deprecated Move to appropriate location.
   * @param element
   */
  private void removeElementFromNamedIndexes(Element element) {
    for (Index<? extends Element> index : getIndices()) {
      ((AccumuloIndex<? extends Element>) index).getWrapper().removeElementFromIndex(element);
    }
  }

  private Text invert(Text columnFamily) {
    return columnFamily.toString().equals(Constants.IN_EDGE) ?
        new Text(Constants.OUT_EDGE) : new Text(Constants.IN_EDGE);
  }

  @Override
  public Iterable<Vertex> getVertices() {
    return globals.getVertexWrapper().getVertices();
  }

  @Override
  public Iterable<Vertex> getVertices(String key, Object value) {
    AccumuloGraphUtils.validateProperty(key, value);
    if (globals.getConfig().getAutoIndex() || getIndexedKeys(Vertex.class).contains(key)) {
      return globals.getVertexKeyIndexWrapper().getVertices(key, value);
    } else {
      return globals.getVertexWrapper().getVertices(key, value);
    }
  }

  @Override
  public Edge addEdge(Object id, Vertex outVertex, Vertex inVertex, String label) {
    if (label == null) {
      throw ExceptionFactory.edgeLabelCanNotBeNull();
    }
    if (id == null) {
      id = AccumuloGraphUtils.generateId();
    }

    String myID = id.toString();

    AccumuloEdge edge = new AccumuloEdge(globals, myID, inVertex, outVertex, label);

    // TODO we arent suppose to make sure the given edge ID doesn't already
    // exist?

    globals.getEdgeWrapper().writeEdge(edge);
    globals.getVertexWrapper().writeEdgeEndpoints(edge);

    globals.checkedFlush();

    globals.getCaches().cache(edge, Edge.class);

    return edge;
  }

  @Override
  public Edge getEdge(Object id) {
    if (id == null) {
      throw ExceptionFactory.edgeIdCanNotBeNull();
    }
    String myID = id.toString();

    Edge edge = globals.getCaches().retrieve(myID, Edge.class);
    if (edge != null) {
      return edge;
    }

    edge = new AccumuloEdge(globals, myID);

    if (!globals.getConfig().getSkipExistenceChecks()) {
      // In addition to just an "existence" check, we will also load
      // any "preloaded" properties now, which saves us a round-trip
      // to Accumulo later.
      String[] preload = globals.getConfig().getPreloadedProperties();
      if (preload == null) {
        preload = new String[]{};
      }

      Map<String, Object> props = globals.getEdgeWrapper()
          .readProperties(edge, preload);
      if (props == null) {
        return null;
      }

      for (String key : props.keySet()) {
        ((AccumuloElement) edge).setPropertyInMemory(key, props.get(key));
      }
    }

    globals.getCaches().cache(edge, Edge.class);

    return edge;
  }

  @Override
  public void removeEdge(Edge edge) {
    edge.remove();
  }

  @Override
  public Iterable<Edge> getEdges() {
    return globals.getEdgeWrapper().getEdges();
  }

  @Override
  public Iterable<Edge> getEdges(String key, Object value) {
    AccumuloGraphUtils.nullCheckProperty(key, value);
    if (key.equalsIgnoreCase("label")) {
      key = Constants.LABEL;
    }

    if (globals.getConfig().getAutoIndex() || getIndexedKeys(Edge.class).contains(key)) {
      return globals.getEdgeKeyIndexWrapper().getEdges(key, value);
    } else {
      return globals.getEdgeWrapper().getEdges(key, value);
    }
  }

  // TODO Eventually
  @Override
  public GraphQuery query() {
    return new DefaultGraphQuery(this);
  }

  @Override
  public void shutdown() {
    try {
      globals.getMtbw().close();
      globals.getVertexWrapper().close();
      globals.getEdgeWrapper().close();
    } catch (MutationsRejectedException e) {
      throw new AccumuloGraphException(e);
    }
    globals.getCaches().clear(Vertex.class);
    globals.getCaches().clear(Edge.class);
  }

  /**
   * @deprecated Move this somewhere appropriate
   * @param type
   * @return
   */
  @Deprecated
  private BatchWriter getIndexBatchWriter(Class<? extends Element> type) {
    if (type.equals(Edge.class))
      return getEdgeIndexWriter();
    return getVertexIndexWriter();
  }

  @Override
  public String toString() {
    return AccumuloGraphConfiguration.ACCUMULO_GRAPH_CLASS.getSimpleName().toLowerCase();
  }

  @SuppressWarnings("rawtypes")
  @Override
  public <T extends Element> Index<T> createIndex(String indexName,
      Class<T> indexClass, Parameter... indexParameters) {
    if (indexClass == null) {
      throw ExceptionFactory.classForElementCannotBeNull();
    }
    if (globals.getConfig().getIndexableGraphDisabled())
      throw new UnsupportedOperationException("IndexableGraph is disabled via the configuration");

    Scanner s = getMetadataScanner();
    try {
      s.setRange(new Range(indexName, indexName));
      if (s.iterator().hasNext())
        throw ExceptionFactory.indexAlreadyExists(indexName);

      globals.getNamedIndexListWrapper().writeIndexNameEntry(indexName, indexClass);

      return new AccumuloIndex<T>(globals, indexName, indexClass);
    } finally {
      s.close();
    }
  }

  @Override
  public <T extends Element> Index<T> getIndex(String indexName, Class<T> indexClass) {
    if (indexClass == null) {
      throw ExceptionFactory.classForElementCannotBeNull();
    }
    if (globals.getConfig().getIndexableGraphDisabled())
      throw new UnsupportedOperationException("IndexableGraph is disabled via the configuration");

    Scanner scan = getScanner(globals.getConfig().getIndexNamesTableName());
    try {
      scan.setRange(new Range(indexName, indexName));
      Iterator<Entry<Key,Value>> iter = scan.iterator();

      while (iter.hasNext()) {
        Key k = iter.next().getKey();
        if (k.getColumnFamily().toString().equals(indexClass.getSimpleName())) {
          return new AccumuloIndex<T>(globals, indexName, indexClass);
        } else {
          throw ExceptionFactory.indexDoesNotSupportClass(indexName, indexClass);
        }
      }
      return null;
    } finally {
      scan.close();
    }
  }

  @Override
  public Iterable<Index<? extends Element>> getIndices() {
    // TODO Move the below into the suitable index metadata wrapper.

    if (globals.getConfig().getIndexableGraphDisabled()) {
      throw new UnsupportedOperationException("IndexableGraph is disabled via the configuration");
    }

    List<Index<? extends Element>> toRet = new ArrayList<Index<? extends Element>>();
    Scanner scan = getScanner(globals.getConfig().getIndexNamesTableName());
    try {
      Iterator<Entry<Key,Value>> iter = scan.iterator();

      while (iter.hasNext()) {
        Key k = iter.next().getKey();
        toRet.add(new AccumuloIndex(globals, k.getRow().toString(),
            getClass(k.getColumnFamily().toString())));
      }
      return toRet;
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    } finally {
      scan.close();
    }
  }

  private Class<? extends Element> getClass(String e) {
    if (e.equals("Vertex")) {
      return Vertex.class;
    }
    return Edge.class;
  }

  @Override
  public void dropIndex(String indexName) {
    if (globals.getConfig().getIndexableGraphDisabled())
      throw new UnsupportedOperationException("IndexableGraph is disabled via the configuration");

    for (Index<? extends Element> index : getIndices()) {
      if (index.getIndexName().equals(indexName)) {
        globals.getNamedIndexListWrapper().clearIndexNameEntry(indexName, index.getIndexClass());

        try {
          globals.getConfig().getConnector().tableOperations().delete(globals.getConfig()
              .getNamedIndexTableName(indexName));
        } catch (Exception e) {
          throw new AccumuloGraphException(e);
        }

        return;
      }
    }

    throw new AccumuloGraphException("Index does not exist: "+indexName);
  }

  @Override
  public <T extends Element> void dropKeyIndex(String key, Class<T> elementClass) {
    if (elementClass == null) {
      throw ExceptionFactory.classForElementCannotBeNull();
    }

    globals.getIndexedKeysListWrapper().clearKeyMetadataEntry(key, elementClass);

    String table = null;
    if (elementClass.equals(Vertex.class)) {
      table = globals.getConfig().getVertexKeyIndexTableName();
    } else {
      table = globals.getConfig().getEdgeKeyIndexTableName();
    }
    BatchDeleter bd = null;
    try {
      bd = globals.getConfig().getConnector().createBatchDeleter(table, globals.getConfig().getAuthorizations(), globals.getConfig().getMaxWriteThreads(), globals.getConfig().getBatchWriterConfig());
      bd.setRanges(Collections.singleton(new Range()));
      bd.fetchColumnFamily(new Text(key));
      bd.delete();
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    } finally {
      if (bd != null)
        bd.close();
    }
    globals.checkedFlush();
  }

  @SuppressWarnings("rawtypes")
  @Override
  public <T extends Element> void createKeyIndex(String key,
      Class<T> elementClass, Parameter... indexParameters) {
    if (elementClass == null) {
      throw ExceptionFactory.classForElementCannotBeNull();
    }

    globals.getIndexedKeysListWrapper().writeKeyMetadataEntry(key, elementClass);
    globals.checkedFlush();

    // Re Index Graph
    BatchScanner scan = getElementBatchScanner(elementClass);
    try {
      scan.setRanges(Collections.singleton(new Range()));
      scan.fetchColumnFamily(new Text(key));
      Iterator<Entry<Key,Value>> iter = scan.iterator();

      BatchWriter bw = getIndexBatchWriter(elementClass);
      while (iter.hasNext()) {
        Entry<Key,Value> entry = iter.next();
        Key k = entry.getKey();
        Value v = entry.getValue();
        Mutation mu = new Mutation(v.get());
        mu.put(k.getColumnFamily().getBytes(), k.getRow().getBytes(), Constants.EMPTY);
        try {
          bw.addMutation(mu);
        } catch (MutationsRejectedException e) {
          // TODO handle this better.
          throw new AccumuloGraphException(e);
        }
      }
    } finally {
      scan.close();
    }
    globals.checkedFlush();
  }

  @Override
  public <T extends Element> Set<String> getIndexedKeys(Class<T> elementClass) {
    return globals.getIndexedKeysListWrapper().getIndexedKeys(elementClass);
  }

  /**
   * Clear out this graph. This drops and recreates the backing tables.
   */
  public void clear() {
    shutdown();

    try {
      TableOperations tableOps = globals.getConfig()
          .getConnector().tableOperations();
      for (Index<? extends Element> index : getIndices()) {
        tableOps.delete(((AccumuloIndex<? extends Element>)
            index).getTableName());
      }

      for (String table : globals.getConfig().getTableNames()) {
        if (tableOps.exists(table)) {
          tableOps.delete(table);
          tableOps.create(table);

          SortedSet<Text> splits = globals.getConfig().getSplits();
          if (splits != null) {
            tableOps.addSplits(table, splits);
          }
        }
      }
      setupWriters();
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    }
  }

  public boolean isEmpty() {
    for (String t : globals.getConfig().getTableNames()) {
      if (getScanner(t).iterator().hasNext()) {
        return false;
      }
    }

    return true;
  }
}
