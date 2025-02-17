/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law
 * or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.apache.jackrabbit.oak.query;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Lists.newArrayList;
import static org.apache.jackrabbit.oak.query.ast.AstElementFactory.copyElementAndCheckReference;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;

import com.google.common.base.Strings;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;

import org.apache.jackrabbit.oak.api.PropertyValue;
import org.apache.jackrabbit.oak.api.Tree;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.api.Result.SizePrecision;
import org.apache.jackrabbit.oak.namepath.JcrPathParser;
import org.apache.jackrabbit.oak.namepath.NamePathMapper;
import org.apache.jackrabbit.oak.query.ast.AndImpl;
import org.apache.jackrabbit.oak.query.ast.AstVisitorBase;
import org.apache.jackrabbit.oak.query.ast.BindVariableValueImpl;
import org.apache.jackrabbit.oak.query.ast.ChildNodeImpl;
import org.apache.jackrabbit.oak.query.ast.ChildNodeJoinConditionImpl;
import org.apache.jackrabbit.oak.query.ast.ColumnImpl;
import org.apache.jackrabbit.oak.query.ast.ComparisonImpl;
import org.apache.jackrabbit.oak.query.ast.ConstraintImpl;
import org.apache.jackrabbit.oak.query.ast.DescendantNodeImpl;
import org.apache.jackrabbit.oak.query.ast.DescendantNodeJoinConditionImpl;
import org.apache.jackrabbit.oak.query.ast.DynamicOperandImpl;
import org.apache.jackrabbit.oak.query.ast.EquiJoinConditionImpl;
import org.apache.jackrabbit.oak.query.ast.FullTextSearchImpl;
import org.apache.jackrabbit.oak.query.ast.FullTextSearchScoreImpl;
import org.apache.jackrabbit.oak.query.ast.InImpl;
import org.apache.jackrabbit.oak.query.ast.JoinConditionImpl;
import org.apache.jackrabbit.oak.query.ast.JoinImpl;
import org.apache.jackrabbit.oak.query.ast.JoinType;
import org.apache.jackrabbit.oak.query.ast.LengthImpl;
import org.apache.jackrabbit.oak.query.ast.LiteralImpl;
import org.apache.jackrabbit.oak.query.ast.LowerCaseImpl;
import org.apache.jackrabbit.oak.query.ast.NativeFunctionImpl;
import org.apache.jackrabbit.oak.query.ast.NodeLocalNameImpl;
import org.apache.jackrabbit.oak.query.ast.NodeNameImpl;
import org.apache.jackrabbit.oak.query.ast.NotImpl;
import org.apache.jackrabbit.oak.query.ast.OrImpl;
import org.apache.jackrabbit.oak.query.ast.OrderingImpl;
import org.apache.jackrabbit.oak.query.ast.PropertyExistenceImpl;
import org.apache.jackrabbit.oak.query.ast.PropertyInexistenceImpl;
import org.apache.jackrabbit.oak.query.ast.PropertyValueImpl;
import org.apache.jackrabbit.oak.query.ast.SameNodeImpl;
import org.apache.jackrabbit.oak.query.ast.SameNodeJoinConditionImpl;
import org.apache.jackrabbit.oak.query.ast.SelectorImpl;
import org.apache.jackrabbit.oak.query.ast.SimilarImpl;
import org.apache.jackrabbit.oak.query.ast.SourceImpl;
import org.apache.jackrabbit.oak.query.ast.SpellcheckImpl;
import org.apache.jackrabbit.oak.query.ast.SuggestImpl;
import org.apache.jackrabbit.oak.query.ast.UpperCaseImpl;
import org.apache.jackrabbit.oak.query.index.FilterImpl;
import org.apache.jackrabbit.oak.query.index.TraversingIndex;
import org.apache.jackrabbit.oak.query.plan.ExecutionPlan;
import org.apache.jackrabbit.oak.query.plan.SelectorExecutionPlan;
import org.apache.jackrabbit.oak.spi.query.Filter;
import org.apache.jackrabbit.oak.spi.query.PropertyValues;
import org.apache.jackrabbit.oak.spi.query.QueryConstants;
import org.apache.jackrabbit.oak.spi.query.QueryIndex;
import org.apache.jackrabbit.oak.spi.query.QueryIndex.AdvancedQueryIndex;
import org.apache.jackrabbit.oak.spi.query.QueryIndex.IndexPlan;
import org.apache.jackrabbit.oak.spi.query.QueryIndex.OrderEntry;
import org.apache.jackrabbit.oak.spi.query.QueryIndex.OrderEntry.Order;
import org.apache.jackrabbit.oak.spi.query.QueryIndexProvider;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a parsed query.
 */
public class QueryImpl implements Query {
    
    /**
     * The "jcr:path" pseudo-property.
     */
    // TODO jcr:path isn't an official feature, support it?
    public static final String JCR_PATH = "jcr:path";

    /**
     * The "jcr:score" pseudo-property.
     */
    public static final String JCR_SCORE = "jcr:score";

    /**
     * The "rep:excerpt" pseudo-property.
     */
    public static final String REP_EXCERPT = "rep:excerpt";

    /**
     * The "rep:spellcheck" pseudo-property.
     */
    public static final String REP_SPELLCHECK = "rep:spellcheck()";

    /**
     * The "rep:suggest" pseudo-property.
     */
    public static final String REP_SUGGEST = "rep:suggest()";

    private static final Logger LOG = LoggerFactory.getLogger(QueryImpl.class);

    private static final Ordering<QueryIndex> MINIMAL_COST_ORDERING = new Ordering<QueryIndex>() {
        @Override
        public int compare(QueryIndex left, QueryIndex right) {
            return Double.compare(left.getMinimumCost(), right.getMinimumCost());
        }
    };

    SourceImpl source;
    private String statement;
    final HashMap<String, PropertyValue> bindVariableMap = new HashMap<String, PropertyValue>();
    final HashMap<String, Integer> selectorIndexes = new HashMap<String, Integer>();
    final ArrayList<SelectorImpl> selectors = new ArrayList<SelectorImpl>();
    ConstraintImpl constraint;

    /**
     * Whether fallback to the traversing index is supported if no other index
     * is available. This is enabled by default and can be disabled for testing
     * purposes.
     */
    private boolean traversalEnabled = true;

    private OrderingImpl[] orderings;
    private ColumnImpl[] columns;
    
    /**
     * The columns that make a row distinct. This is all columns
     * except for "jcr:score".
     */
    private boolean[] distinctColumns;
    
    private boolean explain, measure;
    private boolean distinct;
    private long limit = Long.MAX_VALUE;
    private long offset;
    private long size = -1;
    private boolean prepared;
    private ExecutionContext context;
    
    /**
     * whether the object has been initialised or not
     */
    private boolean init;
    
    /**
     * whether the query is a result of optimisation or original one.
     */
    private boolean optimised;

    private boolean isSortedByIndex;

    private final NamePathMapper namePathMapper;
    
    private double estimatedCost;

    private final QueryEngineSettings settings;

    private boolean warnedHidden;

    private boolean isInternal;

    QueryImpl(String statement, SourceImpl source, ConstraintImpl constraint,
            ColumnImpl[] columns, NamePathMapper mapper, QueryEngineSettings settings) {
        this(statement, source, constraint, columns, mapper, settings, false);
    }

    QueryImpl(String statement, SourceImpl source, ConstraintImpl constraint,
        ColumnImpl[] columns, NamePathMapper mapper, QueryEngineSettings settings, 
        final boolean optimised) {
        this.statement = statement;
        this.source = source;
        this.constraint = constraint;
        this.columns = columns;
        this.namePathMapper = mapper;
        this.settings = settings;
        this.optimised = optimised;
    }

    @Override
    public void init() {

        final QueryImpl query = this;

        if (constraint != null) {
            // need to do this *before* the visitation below, as the
            // simplify() method does not always keep the query reference
            // passed in setQuery(). TODO: avoid that mutability concern
            constraint = constraint.simplify();
        }

        new AstVisitorBase() {

            @Override
            public boolean visit(BindVariableValueImpl node) {
                node.setQuery(query);
                bindVariableMap.put(node.getBindVariableName(), null);
                return true;
            }
            
             @Override
            public boolean visit(ChildNodeImpl node) {
                node.setQuery(query);
                node.bindSelector(source);
                return true;
            }

            @Override
            public boolean visit(ChildNodeJoinConditionImpl node) {
                node.setQuery(query);
                node.bindSelector(source);
                return true;
            }

            @Override
            public boolean visit(ColumnImpl node) {
                node.setQuery(query);
                return true;
            }

            @Override
            public boolean visit(DescendantNodeImpl node) {
                node.setQuery(query);
                node.bindSelector(source);
                return true;
            }

            @Override
            public boolean visit(DescendantNodeJoinConditionImpl node) {
                node.setQuery(query);
                node.bindSelector(source);
                return true;
            }

            @Override
            public boolean visit(EquiJoinConditionImpl node) {
                node.setQuery(query);
                node.bindSelector(source);
                return true;
            }

            @Override
            public boolean visit(FullTextSearchImpl node) {
                node.setQuery(query);
                node.bindSelector(source);
                return super.visit(node);
            }

            @Override
            public boolean visit(NativeFunctionImpl node) {
                node.setQuery(query);
                node.bindSelector(source);
                return super.visit(node);
            }
            
            @Override
            public boolean visit(SimilarImpl node) {
                node.setQuery(query);
                node.bindSelector(source);
                return super.visit(node);
            }
            
            @Override
            public boolean visit(SpellcheckImpl node) {
                node.setQuery(query);
                node.bindSelector(source);
                return super.visit(node);
            }

            @Override
            public boolean visit(SuggestImpl node) {
                node.setQuery(query);
                node.bindSelector(source);
                return super.visit(node);
            }

            @Override
            public boolean visit(FullTextSearchScoreImpl node) {
                node.setQuery(query);
                node.bindSelector(source);
                return true;
            }

            @Override
            public boolean visit(LiteralImpl node) {
                node.setQuery(query);
                return true;
            }

            @Override
            public boolean visit(NodeLocalNameImpl node) {
                node.setQuery(query);
                node.bindSelector(source);
                return true;
            }

            @Override
            public boolean visit(NodeNameImpl node) {
                node.setQuery(query);
                node.bindSelector(source);
                return true;
            }

            @Override
            public boolean visit(PropertyExistenceImpl node) {
                node.setQuery(query);
                node.bindSelector(source);
                return true;
            }
            
            @Override
            public boolean visit(PropertyInexistenceImpl node) {
                node.setQuery(query);
                node.bindSelector(source);
                return true;
            }

            @Override
            public boolean visit(PropertyValueImpl node) {
                node.setQuery(query);
                node.bindSelector(source);
                return true;
            }

            @Override
            public boolean visit(SameNodeImpl node) {
                node.setQuery(query);
                node.bindSelector(source);
                return true;
            }

            @Override
            public boolean visit(SameNodeJoinConditionImpl node) {
                node.setQuery(query);
                node.bindSelector(source);
                return true;
            }

            @Override
            public boolean visit(SelectorImpl node) {
                String name = node.getSelectorName();
                if (selectorIndexes.put(name, selectors.size()) != null) {
                    throw new IllegalArgumentException("Two selectors with the same name: " + name);
                }
                selectors.add(node);
                node.setQuery(query);
                return true;
            }

            @Override
            public boolean visit(LengthImpl node) {
                node.setQuery(query);
                return super.visit(node);
            }

            @Override
            public boolean visit(UpperCaseImpl node) {
                node.setQuery(query);
                return super.visit(node);
            }

            @Override
            public boolean visit(LowerCaseImpl node) {
                node.setQuery(query);
                return super.visit(node);
            }

            @Override
            public boolean visit(ComparisonImpl node) {
                node.setQuery(query);
                return super.visit(node);
            }

            @Override
            public boolean visit(InImpl node) {
                node.setQuery(query);
                return super.visit(node);
            }
            
            @Override
            public boolean visit(AndImpl node) {
                node.setQuery(query);
                return super.visit(node);
            }

            @Override
            public boolean visit(OrImpl node) {
                node.setQuery(query);
                return super.visit(node);
            }

            @Override
            public boolean visit(NotImpl node) {
                node.setQuery(query);
                return super.visit(node);
            }

        }.visit(this);

        source.setQueryConstraint(constraint);
        for (ColumnImpl column : columns) {
            column.bindSelector(source);
        }
        distinctColumns = new boolean[columns.length];
        for (int i = 0; i < columns.length; i++) {
            ColumnImpl c = columns[i];
            boolean distinct = true;
            if (JCR_SCORE.equals(c.getPropertyName())) {
                distinct = false;
            }
            distinctColumns[i] = distinct;
        }
        
        init = true;
    }

    @Override
    public ColumnImpl[] getColumns() {
        return columns;
    }

    public ConstraintImpl getConstraint() {
        return constraint;
    }

    public OrderingImpl[] getOrderings() {
        return orderings;
    }

    public SourceImpl getSource() {
        return source;
    }

    @Override
    public void bindValue(String varName, PropertyValue value) {
        bindVariableMap.put(varName, value);
    }

    @Override
    public void setLimit(long limit) {
        this.limit = limit;
    }

    @Override
    public void setOffset(long offset) {
        this.offset = offset;
    }

    @Override
    public void setExplain(boolean explain) {
        this.explain = explain;
    }

    @Override
    public void setMeasure(boolean measure) {
        this.measure = measure;
    }
    
    public void setDistinct(boolean distinct) {
        this.distinct = distinct;
    }

    @Override
    public ResultImpl executeQuery() {
        return new ResultImpl(this);
    }

    @Override
    public Iterator<ResultRowImpl> getRows() {
        prepare();
        if (explain) {
            String plan = getPlan();
            if (measure) {
                plan += " cost: { " + getIndexCostInfo() + " }";
            }
            columns = new ColumnImpl[] { new ColumnImpl("explain", "plan", "plan")};
            ResultRowImpl r = new ResultRowImpl(this,
                    Tree.EMPTY_ARRAY,
                    new PropertyValue[] { PropertyValues.newString(plan)},
                    null, null);
            return Arrays.asList(r).iterator();
        }
        if (LOG.isDebugEnabled()) {
            logDebug("query execute " + statement);
            logDebug("query plan " + getPlan());
        }
        final RowIterator rowIt = new RowIterator(context.getBaseState());
        Comparator<ResultRowImpl> orderBy;
        if (isSortedByIndex) {
            orderBy = null;
        } else {
            orderBy = ResultRowImpl.getComparator(orderings);
        }
        Iterator<ResultRowImpl> it =
                FilterIterators.newCombinedFilter(rowIt, distinct, limit, offset, orderBy, settings);
        if (orderBy != null) {
            // this will force the rows to be read, so that the size is known
            it.hasNext();
            // we need the size, and there is no other way to get it right now
            // but we also have to take limit and offset into account
            long read = rowIt.getReadCount();
            // we will ignore whatever is behind 'limit+offset'
            read = Math.min(saturatedAdd(limit, offset), read);
            // and we will skip 'offset' entries
            read = Math.max(0, read - offset);
            size = read;
        }
        if (measure) {
            // return the measuring iterator delegating the readCounts to the rowIterator
            it = new MeasuringIterator(this, it) {
                @Override
                protected void setColumns(ColumnImpl[] col) {
                    columns = col;
                }

                @Override
                protected long getReadCount() {
                    return rowIt.getReadCount();
                }

                @Override
                protected Map<String, Long> getSelectorScanCount() {
                    Map<String, Long> selectorReadCounts = Maps.newHashMap();
                    for (SelectorImpl selector : selectors) {
                        selectorReadCounts.put(selector.getSelectorName(), selector.getScanCount());
                    }
                    return  selectorReadCounts;
                }
            };
        }
        return it;
    }

    @Override
    public boolean isSortedByIndex() {
        return isSortedByIndex;
    }

    private boolean canSortByIndex() {
        boolean canSortByIndex = false;
        // TODO add issue about order by optimization for multiple selectors
        if (orderings != null && selectors.size() == 1) {
            IndexPlan plan = selectors.get(0).getExecutionPlan().getIndexPlan();
            if (plan != null) {
                List<OrderEntry> list = plan.getSortOrder();
                if (list != null && list.size() == orderings.length) {
                    canSortByIndex = true;
                    for (int i = 0; i < list.size(); i++) {
                        OrderEntry e = list.get(i);
                        OrderingImpl o = orderings[i];
                        DynamicOperandImpl op = o.getOperand();
                        if (!(op instanceof PropertyValueImpl)) {
                            // ordered by a function: currently not supported
                            canSortByIndex = false;
                            break;
                        }
                        // we only have one selector, so no need to check that
                        // TODO support joins
                        String pn = ((PropertyValueImpl) op).getPropertyName();
                        if (!pn.equals(e.getPropertyName())) {
                            // ordered by another property
                            canSortByIndex = false;
                            break;
                        }
                        if (o.isDescending() != (e.getOrder() == Order.DESCENDING)) {
                            // ordered ascending versus descending
                            canSortByIndex = false;
                            break;
                        }
                    }
                }
            }
        }
        return canSortByIndex;
    }

    @Override
    public String getPlan() {
        return source.getPlan(context.getBaseState());
    }
    
    @Override
    public String getIndexCostInfo() {
        return source.getIndexCostInfo(context.getBaseState());
    }

    @Override
    public double getEstimatedCost() {
        return estimatedCost;
    }

    @Override
    public void prepare() {
        if (prepared) {
            return;
        }
        prepared = true;
        List<SourceImpl> sources = source.getInnerJoinSelectors();
        List<JoinConditionImpl> conditions = source.getInnerJoinConditions();

        if (sources.size() <= 1) {
            // simple case (no join)
            estimatedCost = source.prepare().getEstimatedCost();
            isSortedByIndex = canSortByIndex();
            return;
        }

        // use a greedy algorithm
        SourceImpl result = null;
        Set<SourceImpl> available = new HashSet<SourceImpl>();
        while (sources.size() > 0) {
            int bestIndex = 0;
            double bestCost = Double.POSITIVE_INFINITY;
            ExecutionPlan bestPlan = null;
            SourceImpl best = null;
            for (int i = 0; i < sources.size(); i++) {
                SourceImpl test = buildJoin(result, sources.get(i), conditions);
                if (test == null) {
                    // no join condition
                    continue;
                }
                ExecutionPlan testPlan = test.prepare();
                double cost = testPlan.getEstimatedCost();
                if (best == null || cost < bestCost) {
                    bestPlan = testPlan;
                    bestCost = cost;
                    bestIndex = i;
                    best = test;
                }
                test.unprepare();
            }
            available.add(sources.remove(bestIndex));
            result = best;
            best.prepare(bestPlan);
        }
        estimatedCost = result.prepare().getEstimatedCost();
        source = result;
        isSortedByIndex = canSortByIndex();
    }
    
    private static SourceImpl buildJoin(SourceImpl result, SourceImpl last, List<JoinConditionImpl> conditions) {
        if (result == null) {
            return last;
        }
        List<SourceImpl> selectors = result.getInnerJoinSelectors();
        Set<SourceImpl> oldSelectors = new HashSet<SourceImpl>();
        oldSelectors.addAll(selectors);
        Set<SourceImpl> newSelectors = new HashSet<SourceImpl>();
        newSelectors.addAll(selectors);
        newSelectors.add(last);
        for (JoinConditionImpl j : conditions) {
            // only join conditions can now be evaluated,
            // but couldn't be evaluated before
            if (!j.canEvaluate(oldSelectors) && j.canEvaluate(newSelectors)) {
                JoinImpl join = new JoinImpl(result, last, JoinType.INNER, j);
                return join;
            }
        }
        // no join condition was found
        return null;
    }
 
    /**
     * <b>!Test purpose only! <b>
     * 
     * this creates a filter for the given query
     * 
     */
    Filter createFilter(boolean preparing) {
        return source.createFilter(preparing);
    }


    /**
     * Abstract decorating iterator for measure queries. The iterator delegates to the underlying actual
     * query iterator to lazily execute and return counts.
     */
    abstract static class MeasuringIterator extends AbstractIterator<ResultRowImpl> {
        private Iterator<ResultRowImpl> delegate;
        private Query query;
        private List<ResultRowImpl> results;
        private boolean init;

        MeasuringIterator(Query query, Iterator<ResultRowImpl> delegate) {
            this.query = query;
            this.delegate = delegate;
            results = Lists.newArrayList();
        }

        @Override
        protected ResultRowImpl computeNext() {
            if (!init) {
                getRows();
            }

            if (!results.isEmpty()) {
                return results.remove(0);
            } else {
                return endOfData();
            }
        }

        void getRows() {
            // run the query
            while (delegate.hasNext()) {
                delegate.next();
            }

            ColumnImpl[] columns = new ColumnImpl[] {
                new ColumnImpl("measure", "selector", "selector"),
                new ColumnImpl("measure", "scanCount", "scanCount")
            };
            setColumns(columns);

            ResultRowImpl r = new ResultRowImpl(query,
                Tree.EMPTY_ARRAY,
                new PropertyValue[] {
                    PropertyValues.newString("query"),
                    PropertyValues.newLong(getReadCount())
                },
                null, null);
            results.add(r);

            Map<String, Long> selectorScanCount = getSelectorScanCount();
            for (String selector : selectorScanCount.keySet()) {
                r = new ResultRowImpl(query,
                    Tree.EMPTY_ARRAY,
                    new PropertyValue[] {
                        PropertyValues.newString(selector),
                        PropertyValues.newLong(selectorScanCount.get(selector)),
                    },
                    null, null);
                results.add(r);
            }
            init = true;
        }

        /**
         * Set the measure specific columns in the query object
         * @param columns the measure specific columns
         */
        protected abstract void setColumns(ColumnImpl[] columns);

        /**
         * Retrieve the selector scan count
         * @return map of selector to scan count
         */
        protected abstract Map<String, Long> getSelectorScanCount();

        /**
         * Retrieve the query read count
         * @return count
         */
        protected abstract long getReadCount();

        /**
         * Retrieves the actual query iterator
         * @return the delegate
         */
        protected Iterator<ResultRowImpl> getDelegate() {
            return delegate;
        }
    }

    /**
     * An iterator over result rows.
     */
    class RowIterator implements Iterator<ResultRowImpl> {

        private final NodeState rootState;
        private ResultRowImpl current;
        private boolean started, end;
        private long rowIndex;

        RowIterator(NodeState rootState) {
            this.rootState = rootState;
        }

        public long getReadCount() {
            return rowIndex;
        }

        private void fetchNext() {
            if (end) {
                return;
            }
            if (!started) {
                source.execute(rootState);
                started = true;
            }
            while (true) {
                if (source.next()) {
                    if (constraint == null || constraint.evaluate()) {
                        current = currentRow();
                        rowIndex++;
                        break;
                    }
                    if (constraint != null && constraint.evaluateStop()) {
                        current = null;
                        end = true;
                        break;
                    }
                } else {
                    current = null;
                    end = true;
                    break;
                }
            }
        }

        @Override
        public boolean hasNext() {
            if (end) {
                return false;
            }
            if (current == null) {
                fetchNext();
            }
            return !end;
        }

        @Override
        public ResultRowImpl next() {
            if (end) {
                return null;
            }
            if (current == null) {
                fetchNext();
            }
            ResultRowImpl r = current;
            current = null;
            return r;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

    }

    ResultRowImpl currentRow() {
        int selectorCount = selectors.size();
        Tree[] trees = new Tree[selectorCount];
        for (int i = 0; i < selectorCount; i++) {
            SelectorImpl s = selectors.get(i);
            trees[i] = s.currentTree();
        }
        int columnCount = columns.length;
        PropertyValue[] values = new PropertyValue[columnCount];
        for (int i = 0; i < columnCount; i++) {
            ColumnImpl c = columns[i];
            values[i] = c.currentProperty();
        }
        PropertyValue[] orderValues;
        if (orderings == null) {
            orderValues = null;
        } else {
            int size = orderings.length;
            orderValues = new PropertyValue[size];
            for (int i = 0; i < size; i++) {
                orderValues[i] = orderings[i].getOperand().currentProperty();
            }
        }
        return new ResultRowImpl(this, trees, values, distinctColumns, orderValues);
    }

    @Override
    public int getSelectorIndex(String selectorName) {
        Integer index = selectorIndexes.get(selectorName);
        if (index == null) {
            throw new IllegalArgumentException("Unknown selector: " + selectorName);
        }
        return index;
    }

    @Override
    public int getColumnIndex(String columnName) {
        return getColumnIndex(columns, columnName);
    }
    
    static int getColumnIndex(ColumnImpl[] columns, String columnName) {
        for (int i = 0, size = columns.length; i < size; i++) {
            ColumnImpl c = columns[i];
            String cn = c.getColumnName();
            if (cn != null && cn.equals(columnName)) {
                return i;
            }
        }
        return -1;
    }

    public PropertyValue getBindVariableValue(String bindVariableName) {
        PropertyValue v = bindVariableMap.get(bindVariableName);
        if (v == null) {
            throw new IllegalArgumentException("Bind variable value not set: " + bindVariableName);
        }
        return v;
    }

    @Override
    public String[] getSelectorNames() {
        String[] list = new String[selectors.size()];
        for (int i = 0; i < list.length; i++) {
            list[i] = selectors.get(i).getSelectorName();
        }
        return list;
    }

    @Override
    public List<String> getBindVariableNames() {
        return new ArrayList<String>(bindVariableMap.keySet());
    }

    @Override
    public void setTraversalEnabled(boolean traversalEnabled) {
        this.traversalEnabled = traversalEnabled;
    }

    public SelectorExecutionPlan getBestSelectorExecutionPlan(FilterImpl filter) {
        return getBestSelectorExecutionPlan(context.getBaseState(), filter,
                context.getIndexProvider(), traversalEnabled);
    }

    private SelectorExecutionPlan getBestSelectorExecutionPlan(
            NodeState rootState, FilterImpl filter,
            QueryIndexProvider indexProvider, boolean traversalEnabled) {
        QueryIndex bestIndex = null;
        if (LOG.isDebugEnabled()) {
            logDebug("cost using filter " + filter);
        }

        double bestCost = Double.POSITIVE_INFINITY;
        IndexPlan bestPlan = null;

        // Sort the indexes according to their minimum cost to be able to skip the remaining indexes if the cost of the
        // current index is below the minimum cost of the next index.
        List<? extends QueryIndex> queryIndexes = MINIMAL_COST_ORDERING
                .sortedCopy(indexProvider.getQueryIndexes(rootState));
        for (int i = 0; i < queryIndexes.size(); i++) {
            QueryIndex index = queryIndexes.get(i);
            double minCost = index.getMinimumCost();
            if (minCost > bestCost) {
                // Stop looking if the minimum cost is higher than the current best cost
                break;
            }

            double cost;
            String indexName = index.getIndexName();
            IndexPlan indexPlan = null;
            if (index instanceof AdvancedQueryIndex) {
                AdvancedQueryIndex advIndex = (AdvancedQueryIndex) index;
                List<OrderEntry> sortOrder = null;
                if (orderings != null) {
                    sortOrder = new ArrayList<OrderEntry>();
                    for (OrderingImpl o : orderings) {
                        DynamicOperandImpl op = o.getOperand();
                        if (!(op instanceof PropertyValueImpl)) {
                            // ordered by a function: currently not supported
                            break;
                        }
                        PropertyValueImpl p = (PropertyValueImpl) op;
                        SelectorImpl s = p.getSelectors().iterator().next();
                        if (!s.equals(filter.getSelector())) {
                            // ordered by a different selector
                            continue;
                        }
                        OrderEntry e = new OrderEntry(
                                p.getPropertyName(), 
                                Type.UNDEFINED, 
                                o.isDescending() ? 
                                OrderEntry.Order.DESCENDING : OrderEntry.Order.ASCENDING);
                        sortOrder.add(e);
                    }
                    if (sortOrder.size() == 0) {
                        sortOrder = null;
                    }
                }
                long maxEntryCount = limit;
                if (offset > 0) {
                    if (offset + limit < 0) {
                        // long overflow
                        maxEntryCount = Long.MAX_VALUE;
                    } else {
                        maxEntryCount = offset + limit;
                    }
                }
                List<IndexPlan> ipList = advIndex.getPlans(
                        filter, sortOrder, rootState);
                cost = Double.POSITIVE_INFINITY;
                for (IndexPlan p : ipList) {
                    // TODO limit is after all conditions
                    long entryCount = Math.min(maxEntryCount, p.getEstimatedEntryCount());
                    double c = p.getCostPerExecution() + entryCount * p.getCostPerEntry();
                    if (c < cost) {
                        cost = c;
                        if (p.getPlanName() != null) {
                            indexName += "[" + p.getPlanName() + "]";
                        }
                        indexPlan = p;
                    }
                }
            } else {
                cost = index.getCost(filter, rootState);
            }
            if (LOG.isDebugEnabled()) {
                logDebug("cost for " + indexName + " is " + cost);
            }
            if (cost < 0) {
                LOG.error("cost below 0 for " + indexName + " is " + cost);
            }
            if (cost < bestCost) {
                bestCost = cost;
                bestIndex = index;
                bestPlan = indexPlan;
            }
        }

        if (traversalEnabled) {
            QueryIndex traversal = new TraversingIndex();
            double cost = traversal.getCost(filter, rootState);
            if (LOG.isDebugEnabled()) {
                logDebug("cost for " + traversal.getIndexName() + " is " + cost);
            }
            if (cost < bestCost || bestCost == Double.POSITIVE_INFINITY) {
                bestCost = cost;
                bestPlan = null;
                bestIndex = traversal;
            }
        }
        return new SelectorExecutionPlan(filter.getSelector(), bestIndex, bestPlan, bestCost);
    }
    
    private void logDebug(String msg) {
        if (isInternal) {
            LOG.trace(msg);
        } else {
            LOG.debug(msg);
        }
    }

    @Override
    public void setExecutionContext(ExecutionContext context) {
        this.context = context;
    }

    @Override
    public void setOrderings(OrderingImpl[] orderings) {
        this.orderings = orderings;
    }

    public NamePathMapper getNamePathMapper() {
        return namePathMapper;
    }

    @Override
    public Tree getTree(String path) {
        if (NodeStateUtils.isHiddenPath(path)) {
            if (!warnedHidden) {
                warnedHidden = true;
                LOG.warn("Hidden tree traversed: {}", path);
            }
            return null;
        }
        return context.getRoot().getTree(path);
    }

    @Override
    public boolean isMeasureOrExplainEnabled() {
        return explain || measure;
    }

    /**
     * Validate the path is syntactically correct, and convert it to an Oak
     * internal path (including namespace remapping if needed).
     * 
     * @param path the path
     * @return the the converted path
     */
    public String getOakPath(String path) {
        if (path == null) {
            return null;
        }
        if (!JcrPathParser.validate(path)) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }
        String p = namePathMapper.getOakPath(path);
        if (p == null) {
            throw new IllegalArgumentException("Invalid path or namespace prefix: " + path);
        }
        return p;
    }

    @Override
    public String toString() {
        StringBuilder buff = new StringBuilder();
        buff.append("select ");
        int i = 0;
        for (ColumnImpl c : columns) {
            if (i++ > 0) {
                buff.append(", ");
            }
            buff.append(c);
        }
        buff.append(" from ").append(source);
        if (constraint != null) {
            buff.append(" where ").append(constraint);
        }
        if (orderings != null) {
            buff.append(" order by ");
            i = 0;
            for (OrderingImpl o : orderings) {
                if (i++ > 0) {
                    buff.append(", ");
                }
                buff.append(o);
            }
        }
        return buff.toString();
    }

    @Override
    public long getSize() {
        return size;
    }
    
    @Override
    public long getSize(SizePrecision precision, long max) {
        // Note: DISTINCT is ignored
        if (size != -1) {
            // "order by" was used, so we know the size
            return size;
        }
        return Math.min(limit, source.getSize(precision, max));
    }

    @Override
    public String getStatement() {
        return Strings.isNullOrEmpty(statement) ? toString() : statement;
    }

    public QueryEngineSettings getSettings() {
        return settings;
    }

    @Override
    public void setInternal(boolean isInternal) {
        this.isInternal = isInternal;
    }

    public ExecutionContext getExecutionContext() {
        return context;
    }
    
    /**
     * Add two values, but don't let it overflow or underflow.
     * 
     * @param x the first value
     * @param y the second value
     * @return the sum, or Long.MIN_VALUE for underflow, or Long.MAX_VALUE for
     *         overflow
     */
    public static long saturatedAdd(long x, long y) {
        BigInteger min = BigInteger.valueOf(Long.MIN_VALUE);
        BigInteger max = BigInteger.valueOf(Long.MAX_VALUE);
        BigInteger sum = BigInteger.valueOf(x).add(BigInteger.valueOf(y));
        return sum.min(max).max(min).longValue();
    }

    @Override
    public Query optimise() {
        // optimising for UNION
        Query optimised = this;
        
        if (constraint != null) {
            Set<ConstraintImpl> unionList = constraint.simplifyForUnion();
            if (unionList.size() > 1) {
                // there are some cases where multiple ORs simplify into a single one. If we get a
                // union list of just one we don't really have to UNION anything.
                QueryImpl left = null;
                Query right = null;
                // we have something to do here.
                for (ConstraintImpl c : unionList) {
                    if (right != null) {
                        right = newOptimisedUnionQuery(left, right);
                    } else {
                        // pulling left to the right
                        if (left != null) {
                            right = left;
                        }
                    }
                    
                    // cloning original query
                    left = (QueryImpl) this.copyOf(true);
                    
                    // cloning the constraints and assigning to new query
                    left.constraint = (ConstraintImpl) copyElementAndCheckReference(c);
                    // re-composing the statement for better debug messages
                    left.statement = recomposeStatement(left);
                }
                
                optimised = newOptimisedUnionQuery(left, right);
            }
        }
        
        return optimised;
    }
    
    private static String recomposeStatement(@Nonnull QueryImpl query) {
        checkNotNull(query);
        String original = query.getStatement();
        String origUpper = original.toUpperCase();
        StringBuilder recomputed = new StringBuilder();
        final String where = " WHERE ";
        final String orderBy = " ORDER BY ";
        int whereOffset = where.length();
        
        if (query.getConstraint() == null) {
            recomputed.append(original);
        } else {
            recomputed.append(original.substring(0, origUpper.indexOf(where) + whereOffset));
            recomputed.append(query.getConstraint());
            if (origUpper.indexOf(orderBy) > -1) {
                recomputed.append(original.substring(origUpper.indexOf(orderBy)));
            }
        }
        return recomputed.toString();
    }
    
    /**
     * convenience method for creating a UnionQueryImpl with proper settings.
     * 
     * @param left
     * @param right
     * @return
     */
    private UnionQueryImpl newOptimisedUnionQuery(@Nonnull Query left, @Nonnull Query right) {
        UnionQueryImpl u = new UnionQueryImpl(
            false, 
            checkNotNull(left, "`left` cannot be null"), 
            checkNotNull(right, "`right` cannot be null"),
            this.settings, 
            true);
        u.setExplain(explain);
        return u;
    }
    
    @Override
    public Query copyOf() throws IllegalStateException {
        return copyOf(false);
    }
    
    private Query copyOf(final boolean optimised) {
        if (isInit()) {
            throw new IllegalStateException("QueryImpl cannot be cloned once initialised.");
        }
        
        List<ColumnImpl> cols = newArrayList();
        for (ColumnImpl c : columns) {
            cols.add((ColumnImpl) copyElementAndCheckReference(c));
        }
                
        QueryImpl copy = new QueryImpl(
            this.statement, 
            (SourceImpl) copyElementAndCheckReference(this.source),
            this.constraint,
            cols.toArray(new ColumnImpl[0]),
            this.namePathMapper,
            this.settings,
            optimised);
        copy.explain = this.explain;
        copy.distinct = this.distinct;
        
        return copy;        
    }

    @Override
    public boolean isInit() {
        return init;
    }

    @Override
    public boolean isOptimised() {
        return optimised;
    }

    @Override
    public boolean isInternal() {
        return isInternal;
    }

    @Override
    public double getCostOverhead() {
        return oak2660CostOverhead(getConstraint());
    }

    /**
     * compute a cost overhead for the OAK-2660 use case. The query engine better perform/compute
     * the use case `(a = 'v' OR CONTAINS(b, 'v1') OR CONTAINS(c, 'v2') AND (...)` as a UNION query
     * to leverage different indexes. In this case we return an 'Infinity' overhead for make the
     * query engine choose a union query instead.
     * 
     * @param constraint the constraint to analyse. Cannot be null.
     * @return
     */
    private double oak2660CostOverhead(@Nonnull ConstraintImpl constraint) {
        if (checkNotNull(constraint) instanceof OrImpl) {
            boolean fulltext = false, plain = false;
            for (ConstraintImpl c : constraint.getConstraints()) {
                if (c instanceof FullTextSearchImpl) {
                    fulltext = true;
                } else {
                    plain = true;
                }
                
                if (fulltext && plain) {
                    return Double.MAX_VALUE;
                }
            }
        } else {
            List<ConstraintImpl> cs = constraint.getConstraints();
            if (cs == null) {
                return 0;
            } else {
                double cost = 0;
                for (ConstraintImpl c : cs) {
                    cost += oak2660CostOverhead(c);
                    if (cost == Double.MAX_VALUE) {
                        return cost;
                    }
                }
                return cost;
            }
        }
        return 0;
    }
}
