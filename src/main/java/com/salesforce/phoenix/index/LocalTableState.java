/*******************************************************************************
 * Copyright (c) 2013, Salesforce.com, Inc.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 *     Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *     Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *     Neither the name of Salesforce.com nor the names of its contributors may 
 *     be used to endorse or promote products derived from this software without 
 *     specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE 
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL 
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR 
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER 
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, 
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE 
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/
package com.salesforce.phoenix.index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.filter.BinaryComparator;
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.filter.FamilyFilter;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.QualifierFilter;
import org.apache.hadoop.hbase.regionserver.ExposedMemStore;
import org.apache.hadoop.hbase.regionserver.KeyValueScanner;
import org.apache.hadoop.hbase.util.Bytes;

import com.salesforce.hbase.index.builder.covered.ColumnReference;
import com.salesforce.hbase.index.builder.covered.TableState;
import com.salesforce.hbase.index.builder.covered.util.FilteredKeyValueScanner;

/**
 * Manage the state of the HRegion's view of the table, for the single row.
 * <p>
 * Currently, this is a single-use object - you need to create a new one for each row that you need
 * to manage. In the future, we could make this object reusable, but for the moment its easier to
 * manage as a throw-away object.
 */
public class LocalTableState implements TableState {

  private long ts;
  private RegionCoprocessorEnvironment env;
  private Map<String, byte[]> attributes;
  private ExposedMemStore memstore;
  private LocalTable table;
  private Mutation update;

  public LocalTableState(RegionCoprocessorEnvironment environment, LocalTable table, Mutation update) {
    this.env = environment;
    this.attributes = update.getAttributesMap();
    this.table = table;
    this.update = update;
  }

  public void addUpdate(KeyValue ...kvs){
    for (KeyValue kv : kvs) {
      this.memstore.add(kv);
    }
  }

  public void addUpdate(Collection<KeyValue> list) {
    for (KeyValue kv : list) {
      this.memstore.add(kv);
    }
  }

  @Override
  public RegionCoprocessorEnvironment getEnvironment() {
    return this.env;
  }

  @Override
  public long getCurrentTimestamp() {
    return this.ts;
  }

  @Override
  public void setCurrentTimestamp(long timestamp) {
    this.ts = timestamp;
  }

  @Override
  public Iterator<KeyValue> getTableState(List<ColumnReference> columns) throws IOException {
    ensureLocalStateInitialized();
    // create a filter that matches just the given column references
    FilterList filters = new FilterList();
    for (ColumnReference ref : columns) {
      Filter columnFilter = getColumnFilter(ref);
      filters.addFilter(columnFilter);
    }

    // create a scanner on those columns
    final FilteredKeyValueScanner kvScanner = new FilteredKeyValueScanner(filters, memstore);
    // return the scanner as an iterator
    return new Iterator<KeyValue>() {

      @Override
      public boolean hasNext() {
        return kvScanner.peek() == null;
      }

      @Override
      public KeyValue next() {
        try {
          return kvScanner.next();
        } catch (IOException e) {
          throw new RuntimeException("Error reading kvs from local memstore!");
        }
      }

      @Override
      public void remove() {
        throw new UnsupportedOperationException("Cannot remove kvs from this iterator!");
      }

    };
  }
  
  private Filter getColumnFilter(ColumnReference ref) {
    Filter filter = new FamilyFilter(CompareOp.EQUAL, new BinaryComparator(ref.getFamily()));
    // combine with a match for the qualifier, if the qualifier is a specific qualifier
    if (!Bytes.equals(ColumnReference.ALL_QUALIFIERS, ref.getQualifier())) {
      filter = new FilterList(filter, new QualifierFilter(CompareOp.EQUAL, new BinaryComparator(ref.getQualifier())));
    }
    return filter;
  }

  /**
   * Initialize the managed local state. Generally, this will only be called by
   * {@link #getTableState(List)}, which is unlikely to be called concurrently from the outside.
   * Even then, there is still fairly low contention as each new Put/Delete will have its own table
   * state.
   */
  private synchronized void ensureLocalStateInitialized() throws IOException {
    // check the local memstore - is it initialized?
    if (this.memstore == null) {
      this.memstore = new ExposedMemStore(this.env.getConfiguration(), KeyValue.COMPARATOR);
      // get the current state of the row
      this.memstore.upsert(this.table.getCurrentRowState(update).list());
    }
  }

  @Override
  public Map<String, byte[]> getUpdateAttributes() {
    return this.attributes;
  }

  public Result getCurrentRowState() {
    KeyValueScanner scanner = this.memstore.getScanners().get(0);
    List<KeyValue> kvs = new ArrayList<KeyValue>();
    while (scanner.peek() != null) {
      try {
        kvs.add(scanner.next());
      } catch (IOException e) {
        // this should never happen - something has gone terribly arwy if it has
        throw new RuntimeException("Local MemStore threw IOException!");
      }
    }
    return new Result(kvs);
  }

  /**
   * Helper to add a {@link Mutation} to the values stored for the current row
   * @param pendingUpdate update to apply
   */
  public void addUpdateForTesting(Mutation pendingUpdate) {
    for (Map.Entry<byte[], List<KeyValue>> e : pendingUpdate.getFamilyMap().entrySet()) {
      List<KeyValue> edits = e.getValue();
      addUpdate(edits);
    }
  }
}