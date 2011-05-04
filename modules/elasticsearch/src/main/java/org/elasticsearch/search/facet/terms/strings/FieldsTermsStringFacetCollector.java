/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.facet.terms.strings;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.Scorer;
import org.elasticsearch.common.CacheRecycler;
import org.elasticsearch.common.collect.BoundedTreeSet;
import org.elasticsearch.common.collect.ImmutableList;
import org.elasticsearch.common.collect.ImmutableSet;
import org.elasticsearch.common.trove.iterator.TObjectIntIterator;
import org.elasticsearch.common.trove.map.hash.TObjectIntHashMap;
import org.elasticsearch.index.cache.field.data.FieldDataCache;
import org.elasticsearch.index.field.data.FieldData;
import org.elasticsearch.index.field.data.FieldDataType;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.script.SearchScript;
import org.elasticsearch.search.facet.AbstractFacetCollector;
import org.elasticsearch.search.facet.Facet;
import org.elasticsearch.search.facet.FacetPhaseExecutionException;
import org.elasticsearch.search.facet.terms.support.EntryPriorityQueue;
import org.elasticsearch.search.internal.SearchContext;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author kimchy (shay.banon)
 */
public class FieldsTermsStringFacetCollector extends AbstractFacetCollector {

    private final FieldDataCache fieldDataCache;

    private final String[] indexFieldsNames;

    private final InternalStringTermsFacet.ComparatorType comparatorType;

    private final int size;

    private final int numberOfShards;

    private final FieldDataType[] fieldsDataType;

    private FieldData[] fieldsData;

    private final StaticAggregatorValueProc aggregator;

    private final SearchScript script;

    public FieldsTermsStringFacetCollector(String facetName, String[] fieldsNames, int size, InternalStringTermsFacet.ComparatorType comparatorType, boolean allTerms, SearchContext context,
                                           ImmutableSet<String> excluded, Pattern pattern, String scriptLang, String script, Map<String, Object> params) {
        super(facetName);
        this.fieldDataCache = context.fieldDataCache();
        this.size = size;
        this.comparatorType = comparatorType;
        this.numberOfShards = context.numberOfShards();

        fieldsDataType = new FieldDataType[fieldsNames.length];
        fieldsData = new FieldData[fieldsNames.length];
        indexFieldsNames = new String[fieldsNames.length];

        for (int i = 0; i < fieldsNames.length; i++) {
            MapperService.SmartNameFieldMappers smartMappers = context.mapperService().smartName(fieldsNames[i]);
            if (smartMappers == null || !smartMappers.hasMapper()) {
                this.indexFieldsNames[i] = fieldsNames[i];
                this.fieldsDataType[i] = FieldDataType.DefaultTypes.STRING;
            } else {
                this.indexFieldsNames[i] = smartMappers.mapper().names().indexName();
                this.fieldsDataType[i] = smartMappers.mapper().fieldDataType();
            }

        }

        if (script != null) {
            this.script = context.scriptService().search(context.lookup(), scriptLang, script, params);
        } else {
            this.script = null;
        }

        if (excluded.isEmpty() && pattern == null && this.script == null) {
            aggregator = new StaticAggregatorValueProc(CacheRecycler.<String>popObjectIntMap());
        } else {
            aggregator = new AggregatorValueProc(CacheRecycler.<String>popObjectIntMap(), excluded, pattern, this.script);
        }

        if (allTerms) {
            try {
                for (int i = 0; i < fieldsNames.length; i++) {
                    for (IndexReader reader : context.searcher().subReaders()) {
                        FieldData fieldData = fieldDataCache.cache(fieldsDataType[i], reader, indexFieldsNames[i]);
                        fieldData.forEachValue(aggregator);
                    }
                }
            } catch (Exception e) {
                throw new FacetPhaseExecutionException(facetName, "failed to load all terms", e);
            }
        }
    }

    @Override public void setScorer(Scorer scorer) throws IOException {
        if (script != null) {
            script.setScorer(scorer);
        }
    }

    @Override protected void doSetNextReader(IndexReader reader, int docBase) throws IOException {
        for (int i = 0; i < indexFieldsNames.length; i++) {
            fieldsData[i] = fieldDataCache.cache(fieldsDataType[i], reader, indexFieldsNames[i]);
        }
        if (script != null) {
            script.setNextReader(reader);
        }
    }

    @Override protected void doCollect(int doc) throws IOException {
        for (FieldData fieldData : fieldsData) {
            fieldData.forEachValueInDoc(doc, aggregator);
        }
    }

    @Override public Facet facet() {
        TObjectIntHashMap<String> facets = aggregator.facets();
        if (facets.isEmpty()) {
            CacheRecycler.pushObjectIntMap(facets);
            return new InternalStringTermsFacet(facetName, comparatorType, size, ImmutableList.<InternalStringTermsFacet.StringEntry>of(), aggregator.missing());
        } else {
            if (size < EntryPriorityQueue.LIMIT) {
                EntryPriorityQueue ordered = new EntryPriorityQueue(size, comparatorType.comparator());
                for (TObjectIntIterator<String> it = facets.iterator(); it.hasNext();) {
                    it.advance();
                    ordered.insertWithOverflow(new InternalStringTermsFacet.StringEntry(it.key(), it.value()));
                }
                InternalStringTermsFacet.StringEntry[] list = new InternalStringTermsFacet.StringEntry[ordered.size()];
                for (int i = ordered.size() - 1; i >= 0; i--) {
                    list[i] = ((InternalStringTermsFacet.StringEntry) ordered.pop());
                }
                CacheRecycler.pushObjectIntMap(facets);
                return new InternalStringTermsFacet(facetName, comparatorType, size, Arrays.asList(list), aggregator.missing());
            } else {
                BoundedTreeSet<InternalStringTermsFacet.StringEntry> ordered = new BoundedTreeSet<InternalStringTermsFacet.StringEntry>(comparatorType.comparator(), size);
                for (TObjectIntIterator<String> it = facets.iterator(); it.hasNext();) {
                    it.advance();
                    ordered.add(new InternalStringTermsFacet.StringEntry(it.key(), it.value()));
                }
                CacheRecycler.pushObjectIntMap(facets);
                return new InternalStringTermsFacet(facetName, comparatorType, size, ordered, aggregator.missing());
            }
        }
    }

    public static class AggregatorValueProc extends StaticAggregatorValueProc {

        private final ImmutableSet<String> excluded;

        private final Matcher matcher;

        private final SearchScript script;

        public AggregatorValueProc(TObjectIntHashMap<String> facets, ImmutableSet<String> excluded, Pattern pattern, SearchScript script) {
            super(facets);
            this.excluded = excluded;
            this.matcher = pattern != null ? pattern.matcher("") : null;
            this.script = script;
        }

        @Override public void onValue(int docId, String value) {
            if (excluded != null && excluded.contains(value)) {
                return;
            }
            if (matcher != null && !matcher.reset(value).matches()) {
                return;
            }
            if (script != null) {
                script.setNextDocId(docId);
                script.setNextVar("term", value);
                Object scriptValue = script.run();
                if (scriptValue == null) {
                    return;
                }
                if (scriptValue instanceof Boolean) {
                    if (!((Boolean) scriptValue)) {
                        return;
                    }
                } else {
                    value = scriptValue.toString();
                }
            }
            super.onValue(docId, value);
        }
    }

    public static class StaticAggregatorValueProc implements FieldData.StringValueInDocProc, FieldData.StringValueProc {

        private final TObjectIntHashMap<String> facets;

        private int missing;

        public StaticAggregatorValueProc(TObjectIntHashMap<String> facets) {
            this.facets = facets;
        }

        @Override public void onValue(String value) {
            facets.putIfAbsent(value, 0);
        }

        @Override public void onValue(int docId, String value) {
            facets.adjustOrPutValue(value, 1, 1);
        }

        @Override public void onMissing(int docId) {
            missing++;
        }

        public final TObjectIntHashMap<String> facets() {
            return facets;
        }

        public final int missing() {
            return this.missing;
        }
    }
}
