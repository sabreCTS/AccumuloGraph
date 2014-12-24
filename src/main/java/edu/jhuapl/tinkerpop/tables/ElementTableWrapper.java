/******************************************************************************
 *                              COPYRIGHT NOTICE                              *
 * Copyright (c) 2014 The Johns Hopkins University/Applied Physics Laboratory *
 *                            All rights reserved.                            *
 *                                                                            *
 * This material may only be used, modified, or reproduced by or for the      *
 * U.S. Government pursuant to the license rights granted under FAR clause    *
 * 52.227-14 or DFARS clauses 252.227-7013/7014.                              *
 *                                                                            *
 * For any other permissions, please contact the Legal Office at JHU/APL.     *
 ******************************************************************************/
package edu.jhuapl.tinkerpop.tables;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import org.apache.accumulo.core.client.MultiTableBatchWriter;
import org.apache.accumulo.core.client.MutationsRejectedException;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.hadoop.io.Text;

import com.tinkerpop.blueprints.Element;
import com.tinkerpop.blueprints.util.StringFactory;

import edu.jhuapl.tinkerpop.AccumuloByteSerializer;
import edu.jhuapl.tinkerpop.AccumuloGraph;
import edu.jhuapl.tinkerpop.AccumuloGraphConfiguration;
import edu.jhuapl.tinkerpop.AccumuloGraphException;

/**
 * Wrapper around tables with operations
 * common to {@link Element}s.
 */
public abstract class ElementTableWrapper extends BaseTableWrapper {

  public ElementTableWrapper(AccumuloGraphConfiguration config,
      MultiTableBatchWriter writer, String tableName) {
    super(config, writer, tableName);
  }

  /**
   * Read the given property from the backing table
   * for the given element id.
   * @param id
   * @param key
   * @return
   */
  public <V> V readProperty(String id, String key) {
    Scanner s = getScanner();

    s.setRange(new Range(id));

    Text colf = null;
    if (StringFactory.LABEL.equals(key)) {
      colf = AccumuloGraph.TLABEL;
    } else {
      colf = new Text(key);
    }
    s.fetchColumnFamily(colf);

    V value = null;

    Iterator<Entry<Key, Value>> iter = s.iterator();
    if (iter.hasNext()) {
      value = AccumuloByteSerializer.deserialize(iter.next().getValue().get());
    }
    s.close();

    return value;
  }

  /**
   * Read the given properties for the given element id.
   * This may return an empty map for elements with no properties.
   * If the element does not exist, return null.
   * @param id
   * @param propertyKeys
   * @return
   */
  public Map<String, Object> readProperties(String id, String... propertyKeys) {
    Scanner s = getScanner();
    s.setRange(new Range(id));
    s.fetchColumnFamily(AccumuloGraph.TLABEL);

    for (String key : propertyKeys) {
      s.fetchColumnFamily(new Text(key));
    }

    Map<String, Object> props = parseProperties(s);
    s.close();

    return props;
  }

  /**
   * Parse raw Accumulo entries into a property map.
   * If there are no entries, return null.
   * @param entries
   * @return
   */
  private Map<String, Object> parseProperties(Iterable<Entry<Key, Value>> entries) {
    Map<String, Object> props = null;

    for (Entry<Key, Value> entry : entries) {
      if (props == null) {
        props = new HashMap<String, Object>();
      }

      Key key = entry.getKey();

      if (!isExistenceKey(key)) {
        String attr = key.getColumnFamily().toString();
        Object value = AccumuloByteSerializer.deserialize(entry.getValue().get());
        props.put(attr, value);
      }
    }

    return props;
  }

  /**
   * Test whether the given Accumulo key represents an
   * element's existence (i.e. not a property).
   * @param key
   * @return
   */
  private static boolean isExistenceKey(Key key) {
    return AccumuloGraph.TLABEL.equals(key.getColumnFamily()) &&
        AccumuloGraph.TEXISTS.equals(key.getColumnQualifier());
  }

  /**
   * Get all property keys for the given element id.
   * @param id
   * @return
   */
  public Set<String> readPropertyKeys(String id) {
    Scanner s = getScanner();

    s.setRange(new Range(id));

    Set<String> keys = new HashSet<String>();

    for (Entry<Key, Value> entry : s) {
      String cf = entry.getKey().getColumnFamily().toString();
      keys.add(cf);
    }

    s.close();

    // Remove some special keys.
    keys.remove(AccumuloGraph.TINEDGE.toString());
    keys.remove(AccumuloGraph.TLABEL.toString());
    keys.remove(AccumuloGraph.TOUTEDGE.toString());

    return keys;
  }

  /**
   * Delete the property entry from property table.
   * @param id
   * @param key
   */
  public void clearProperty(String id, String key) {
    try {
      Mutation m = new Mutation(id);
      m.putDelete(key.getBytes(), AccumuloGraph.EMPTY);
      getWriter().addMutation(m);

    } catch (MutationsRejectedException e) {
      throw new AccumuloGraphException(e);
    }
  }

  /**
   * Write the given property to the property table.
   * @param id
   * @param key
   * @param value
   */
  public void writeProperty(String id, String key, Object value) {
    byte[] bytes = AccumuloByteSerializer.serialize(value);
    Mutation m = new Mutation(id);
    m.put(key.getBytes(), AccumuloGraph.EMPTY, bytes);
    try {
      getWriter().addMutation(m);
    } catch (MutationsRejectedException e) {
      throw new AccumuloGraphException(e);
    }
  }

  public void close() {
    // TODO?
  }
}
