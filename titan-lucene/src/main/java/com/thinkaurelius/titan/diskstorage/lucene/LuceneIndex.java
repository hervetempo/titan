package com.thinkaurelius.titan.diskstorage.lucene;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.spatial4j.core.context.SpatialContext;
import com.spatial4j.core.shape.Shape;
import com.thinkaurelius.titan.core.Cardinality;
import com.thinkaurelius.titan.core.schema.Mapping;
import com.thinkaurelius.titan.core.Order;
import com.thinkaurelius.titan.core.attribute.*;
import com.thinkaurelius.titan.diskstorage.*;
import com.thinkaurelius.titan.diskstorage.configuration.Configuration;
import com.thinkaurelius.titan.diskstorage.indexing.*;
import com.thinkaurelius.titan.graphdb.configuration.GraphDatabaseConfiguration;
import com.thinkaurelius.titan.graphdb.database.serialize.AttributeUtil;
import com.thinkaurelius.titan.graphdb.query.TitanPredicate;
import com.thinkaurelius.titan.graphdb.query.condition.*;
import com.thinkaurelius.titan.util.system.IOUtils;
import com.tinkerpop.pipes.util.structures.Pair;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queries.BooleanFilter;
import org.apache.lucene.queries.TermsFilter;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.spatial.SpatialStrategy;
import org.apache.lucene.spatial.query.SpatialArgs;
import org.apache.lucene.spatial.query.SpatialOperation;
import org.apache.lucene.spatial.vector.PointVectorStrategy;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author Matthias Broecheler (me@matthiasb.com)
 */

public class LuceneIndex implements IndexProvider {
    private static final Logger log = LoggerFactory.getLogger(LuceneIndex.class);


    private static final String DOCID = "_____elementid";
    private static final String GEOID = "_____geo";
    private static final int MAX_STRING_FIELD_LEN = 256;

    private static final Version LUCENE_VERSION = Version.LUCENE_41;
    private static final IndexFeatures LUCENE_FEATURES = new IndexFeatures.Builder().supportedStringMappings(Mapping.TEXT, Mapping.STRING).build();

    private static final int GEO_MAX_LEVELS = 11;

    private final Analyzer analyzer = new StandardAnalyzer(LUCENE_VERSION);

    private final Map<String, IndexWriter> writers = new HashMap<String, IndexWriter>(4);
    private final ReentrantLock writerLock = new ReentrantLock();

    private Map<String, SpatialStrategy> spatial = new ConcurrentHashMap<String, SpatialStrategy>(12);
    private SpatialContext ctx = SpatialContext.GEO;

    private final String basePath;

    public LuceneIndex(Configuration config) {
        String dir = config.get(GraphDatabaseConfiguration.INDEX_DIRECTORY);
        File directory = new File(dir);
        if (!directory.exists()) directory.mkdirs();
        if (!directory.exists() || !directory.isDirectory() || !directory.canWrite())
            throw new IllegalArgumentException("Cannot access or write to directory: " + dir);
        basePath = directory.getAbsolutePath();
        log.debug("Configured Lucene to use base directory [{}]", basePath);
    }

    private Directory getStoreDirectory(String store) throws BackendException {
        Preconditions.checkArgument(StringUtils.isAlphanumeric(store), "Invalid store name: %s", store);
        String dir = basePath + File.separator + store;
        try {
            File path = new File(dir);
            if (!path.exists()) path.mkdirs();
            if (!path.exists() || !path.isDirectory() || !path.canWrite())
                throw new PermanentBackendException("Cannot access or write to directory: " + dir);
            log.debug("Opening store directory [{}]", path);
            return FSDirectory.open(path);
        } catch (IOException e) {
            throw new PermanentBackendException("Could not open directory: " + dir, e);
        }
    }

    private IndexWriter getWriter(String store) throws BackendException {
        Preconditions.checkArgument(writerLock.isHeldByCurrentThread());
        IndexWriter writer = writers.get(store);
        if (writer == null) {
            IndexWriterConfig iwc = new IndexWriterConfig(LUCENE_VERSION, analyzer);
            iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            try {
                writer = new IndexWriter(getStoreDirectory(store), iwc);
                writers.put(store, writer);
            } catch (IOException e) {
                throw new PermanentBackendException("Could not create writer", e);
            }
        }
        return writer;
    }

    private SpatialStrategy getSpatialStrategy(String key) {
        SpatialStrategy strategy = spatial.get(key);
        if (strategy == null) {
            synchronized (spatial) {
                if (!spatial.containsKey(key)) {
//                    SpatialPrefixTree grid = new GeohashPrefixTree(ctx, GEO_MAX_LEVELS);
//                    strategy = new RecursivePrefixTreeStrategy(grid, key);
                    strategy = new PointVectorStrategy(ctx, key);
                    spatial.put(key, strategy);
                } else return spatial.get(key);
            }
        }
        return strategy;
    }

    @Override
    public void register(String store, String key, KeyInformation information, BaseTransaction tx) throws BackendException {
        Class<?> dataType = information.getDataType();
        Mapping map = Mapping.getMapping(information);
        Preconditions.checkArgument(map==Mapping.DEFAULT || AttributeUtil.isString(dataType),
                "Specified illegal mapping [%s] for data type [%s]",map,dataType);    }

    @Override
    public void mutate(Map<String, Map<String, IndexMutation>> mutations, KeyInformation.IndexRetriever informations, BaseTransaction tx) throws BackendException {
        Transaction ltx = (Transaction) tx;
        writerLock.lock();
        try {
            for (Map.Entry<String, Map<String, IndexMutation>> stores : mutations.entrySet()) {
                mutateStores(stores, informations);
            }
            ltx.postCommit();
        } catch (IOException e) {
            throw new TemporaryBackendException("Could not update Lucene index", e);
        } finally {
            writerLock.unlock();
        }
    }

    private void mutateStores(Map.Entry<String, Map<String, IndexMutation>> stores, KeyInformation.IndexRetriever informations) throws IOException, BackendException {
        IndexReader reader = null;
        try {
            String storename = stores.getKey();
            IndexWriter writer = getWriter(storename);
            reader = DirectoryReader.open(writer, true);
            IndexSearcher searcher = new IndexSearcher(reader);
            for (Map.Entry<String, IndexMutation> entry : stores.getValue().entrySet()) {
                String docid = entry.getKey();
                IndexMutation mutation = entry.getValue();

                if (mutation.isDeleted()) {
                    if (log.isTraceEnabled())
                        log.trace("Deleted entire document [{}]", docid);

                    writer.deleteDocuments(new Term(DOCID, docid));
                    continue;
                }

                Pair<Document, Map<String, Shape>> docAndGeo = retrieveOrCreate(docid, searcher);
                Document doc = docAndGeo.getA();
                Map<String, Shape> geofields = docAndGeo.getB();

                Preconditions.checkNotNull(doc);
                for (IndexEntry del : mutation.getDeletions()) {
                    Preconditions.checkArgument(!del.hasMetaData(), "Lucene index does not support indexing meta data: %s", del);
                    String key = del.field;
                    if (doc.getField(key) != null) {
                        if (log.isTraceEnabled())
                            log.trace("Removing field [{}] on document [{}]", key, docid);

                        doc.removeFields(key);
                        geofields.remove(key);
                    }
                }

                addToDocument(storename, docid, doc, mutation.getAdditions(), geofields, informations);

                //write the old document to the index with the modifications
                writer.updateDocument(new Term(DOCID, docid), doc);
            }
            writer.commit();
        } finally {
            IOUtils.closeQuietly(reader);
        }
    }

    @Override
    public void restore(Map<String, Map<String, List<IndexEntry>>> documents, KeyInformation.IndexRetriever informations, BaseTransaction tx) throws BackendException {
        writerLock.lock();
        try {
            for (Map.Entry<String, Map<String, List<IndexEntry>>> stores : documents.entrySet()) {
                String store = stores.getKey();
                IndexWriter writer = getWriter(store);
                IndexReader reader = DirectoryReader.open(writer, true);
                IndexSearcher searcher = new IndexSearcher(reader);

                for (Map.Entry<String, List<IndexEntry>> entry : stores.getValue().entrySet()) {
                    String docID = entry.getKey();
                    List<IndexEntry> content = entry.getValue();

                    if (content == null || content.isEmpty()) {
                        if (log.isTraceEnabled())
                            log.trace("Deleting document [{}]", docID);

                        writer.deleteDocuments(new Term(DOCID, docID));
                        continue;
                    }

                    Pair<Document, Map<String, Shape>> docAndGeo = retrieveOrCreate(docID, searcher);
                    addToDocument(store, docID, docAndGeo.getA(), content, docAndGeo.getB(), informations);

                    //write the old document to the index with the modifications
                    writer.updateDocument(new Term(DOCID, docID), docAndGeo.getA());
                }
                writer.commit();
            }
            tx.commit();
        } catch (IOException e) {
            throw new TemporaryBackendException("Could not update Lucene index", e);
        } finally {
            writerLock.unlock();
        }
    }

    private Pair<Document, Map<String, Shape>> retrieveOrCreate(String docID, IndexSearcher searcher) throws IOException {
        Document doc;
        TopDocs hits = searcher.search(new TermQuery(new Term(DOCID, docID)), 10);
        Map<String, Shape> geofields = Maps.newHashMap();

        if (hits.scoreDocs.length > 1)
            throw new IllegalArgumentException("More than one document found for document id: " + docID);

        if (hits.scoreDocs.length == 0) {
            if (log.isTraceEnabled())
                log.trace("Creating new document for [{}]", docID);

            doc = new Document();
            doc.add(new StringField(DOCID, docID, Field.Store.YES));
        } else {
            if (log.isTraceEnabled())
                log.trace("Updating existing document for [{}]", docID);

            int docId = hits.scoreDocs[0].doc;
            //retrieve the old document
            doc = searcher.doc(docId);
            for (IndexableField field : doc.getFields()) {
                if (field.stringValue().startsWith(GEOID)) {
                    geofields.put(field.name(), ctx.readShape(field.stringValue().substring(GEOID.length())));
                }
            }
        }

        return new Pair<Document, Map<String, Shape>>(doc, geofields);
    }

    private void addToDocument(String store,
                               String docID,
                               Document doc,
                               List<IndexEntry> content,
                               Map<String, Shape> geofields,
                               KeyInformation.IndexRetriever informations) {
        Preconditions.checkNotNull(doc);
        for (IndexEntry e : content) {
            Preconditions.checkArgument(!e.hasMetaData(),"Lucene index does not support indexing meta data: %s",e);
            if (log.isTraceEnabled())
                log.trace("Adding field [{}] on document [{}]", e.field, docID);

            if (doc.getField(e.field) != null)
                doc.removeFields(e.field);

            if (e.value instanceof Number) {
                Field field;
                if (AttributeUtil.isWholeNumber((Number) e.value)) {
                    field = new LongField(e.field, ((Number) e.value).longValue(), Field.Store.YES);
                } else { //double or float
                    field = new DoubleField(e.field, ((Number) e.value).doubleValue(), Field.Store.YES);
                }
                doc.add(field);
            } else if (AttributeUtil.isString(e.value)) {
                String str = (String) e.value;
                Mapping mapping = Mapping.getMapping(store, e.field, informations);
                Field field;
                switch(mapping) {
                    case DEFAULT:
                    case TEXT:
                        field = new TextField(e.field, str, Field.Store.YES);
                        break;
                    case STRING:
                        field = new StringField(e.field, str, Field.Store.YES);
                        break;
                    default: throw new IllegalArgumentException("Illegal mapping specified: " + mapping);
                }
                doc.add(field);
            } else if (e.value instanceof Geoshape) {
                Shape shape = ((Geoshape) e.value).convert2Spatial4j();
                geofields.put(e.field, shape);
                doc.add(new StoredField(e.field, GEOID + ctx.toString(shape)));

            } else {
                throw new IllegalArgumentException("Unsupported type: " + e.value);
            }
        }

        for (Map.Entry<String, Shape> geo : geofields.entrySet()) {
            if (log.isTraceEnabled())
                log.trace("Updating geo-indexes for key {}", geo.getKey());

            for (IndexableField f : getSpatialStrategy(geo.getKey()).createIndexableFields(geo.getValue()))
                doc.add(f);
        }
    }

    private static Sort getSortOrder(IndexQuery query) {
        Sort sort = new Sort();
        List<IndexQuery.OrderEntry> orders = query.getOrder();
        if (!orders.isEmpty()) {
            SortField[] fields = new SortField[orders.size()];
            for (int i = 0; i < orders.size(); i++) {
                IndexQuery.OrderEntry order = orders.get(i);
                SortField.Type sortType = null;
                Class datatype = order.getDatatype();
                if (AttributeUtil.isString(datatype)) sortType = SortField.Type.STRING;
                else if (AttributeUtil.isWholeNumber(datatype)) sortType = SortField.Type.LONG;
                else if (AttributeUtil.isDecimal(datatype)) sortType = SortField.Type.DOUBLE;
                else
                    Preconditions.checkArgument(false, "Unsupported order specified on field [%s] with datatype [%s]", order.getKey(), datatype);

                fields[i] = new SortField(order.getKey(), sortType, order.getOrder() == Order.DESC);
            }
            sort.setSort(fields);
        }
        return sort;
    }

    @Override
    public List<String> query(IndexQuery query, KeyInformation.IndexRetriever informations, BaseTransaction tx) throws BackendException {
        //Construct query
        Filter q = convertQuery(query.getCondition(),informations.get(query.getStore()));

        try {
            IndexSearcher searcher = ((Transaction) tx).getSearcher(query.getStore());
            if (searcher == null) return ImmutableList.of(); //Index does not yet exist
            long time = System.currentTimeMillis();
            TopDocs docs = searcher.search(new MatchAllDocsQuery(), q, query.hasLimit() ? query.getLimit() : Integer.MAX_VALUE - 1, getSortOrder(query));
            log.debug("Executed query [{}] in {} ms", q, System.currentTimeMillis() - time);
            List<String> result = new ArrayList<String>(docs.scoreDocs.length);
            for (int i = 0; i < docs.scoreDocs.length; i++) {
                result.add(searcher.doc(docs.scoreDocs[i].doc).getField(DOCID).stringValue());
            }
            return result;
        } catch (IOException e) {
            throw new TemporaryBackendException("Could not execute Lucene query", e);
        }
    }

    private static final Filter numericFilter(String key, Cmp relation, Number value) {
        switch (relation) {
            case EQUAL:
                return AttributeUtil.isWholeNumber(value) ?
                        NumericRangeFilter.newLongRange(key, value.longValue(), value.longValue(), true, true) :
                        NumericRangeFilter.newDoubleRange(key, value.doubleValue(), value.doubleValue(), true, true);
            case NOT_EQUAL:
                BooleanFilter q = new BooleanFilter();
                if (AttributeUtil.isWholeNumber(value)) {
                    q.add(NumericRangeFilter.newLongRange(key, Long.MIN_VALUE, value.longValue(), true, false), BooleanClause.Occur.SHOULD);
                    q.add(NumericRangeFilter.newLongRange(key, value.longValue(), Long.MAX_VALUE, false, true), BooleanClause.Occur.SHOULD);
                } else {
                    q.add(NumericRangeFilter.newDoubleRange(key, Double.MIN_VALUE, value.doubleValue(), true, false), BooleanClause.Occur.SHOULD);
                    q.add(NumericRangeFilter.newDoubleRange(key, value.doubleValue(), Double.MAX_VALUE, false, true), BooleanClause.Occur.SHOULD);
                }
                return q;
            case LESS_THAN:
                return (AttributeUtil.isWholeNumber(value)) ?
                        NumericRangeFilter.newLongRange(key, Long.MIN_VALUE, value.longValue(), true, false) :
                        NumericRangeFilter.newDoubleRange(key, Double.MIN_VALUE, value.doubleValue(), true, false);
            case LESS_THAN_EQUAL:
                return (AttributeUtil.isWholeNumber(value)) ?
                        NumericRangeFilter.newLongRange(key, Long.MIN_VALUE, value.longValue(), true, true) :
                        NumericRangeFilter.newDoubleRange(key, Double.MIN_VALUE, value.doubleValue(), true, true);
            case GREATER_THAN:
                return (AttributeUtil.isWholeNumber(value)) ?
                        NumericRangeFilter.newLongRange(key, value.longValue(), Long.MAX_VALUE, false, true) :
                        NumericRangeFilter.newDoubleRange(key, value.doubleValue(), Double.MAX_VALUE, false, true);
            case GREATER_THAN_EQUAL:
                return (AttributeUtil.isWholeNumber(value)) ?
                        NumericRangeFilter.newLongRange(key, value.longValue(), Long.MAX_VALUE, true, true) :
                        NumericRangeFilter.newDoubleRange(key, value.doubleValue(), Double.MAX_VALUE, true, true);
            default:
                throw new IllegalArgumentException("Unexpected relation: " + relation);
        }
    }

    private final Filter convertQuery(Condition<?> condition, KeyInformation.StoreRetriever informations) {
        if (condition instanceof PredicateCondition) {
            PredicateCondition<String, ?> atom = (PredicateCondition) condition;
            Object value = atom.getValue();
            String key = atom.getKey();
            TitanPredicate titanPredicate = atom.getPredicate();
            if (value instanceof Number) {
                Preconditions.checkArgument(titanPredicate instanceof Cmp, "Relation not supported on numeric types: " + titanPredicate);
                Preconditions.checkArgument(value instanceof Number);
                return numericFilter(key, (Cmp) titanPredicate, (Number) value);
            } else if (value instanceof String) {
                Mapping map = Mapping.getMapping(informations.get(key));
                if ((map==Mapping.DEFAULT || map==Mapping.TEXT) && !titanPredicate.toString().startsWith("CONTAINS"))
                    throw new IllegalArgumentException("Text mapped string values only support CONTAINS queries and not: " + titanPredicate);
                if (map==Mapping.STRING && titanPredicate.toString().startsWith("CONTAINS"))
                    throw new IllegalArgumentException("String mapped string values do not support CONTAINS queries: " + titanPredicate);



                if (titanPredicate == Text.CONTAINS) {
                    value = ((String) value).toLowerCase();
                    BooleanFilter b = new BooleanFilter();
                    for (String term : Text.tokenize((String)value)) {
                        b.add(new TermsFilter(new Term(key, term)), BooleanClause.Occur.MUST);
                    }
                    return b;
                } else if (titanPredicate == Text.CONTAINS_PREFIX) {
                    value = ((String) value).toLowerCase();
                    return new PrefixFilter(new Term(key, (String) value));
                } else if (titanPredicate == Text.PREFIX) {
                    return new PrefixFilter(new Term(key, (String) value));
//                } else if (titanPredicate == Text.CONTAINS_REGEX) {
//                    value = ((String) value).toLowerCase();
//                    return new RegexpQuery(new Term(key,(String)value));
                } else if (titanPredicate == Cmp.EQUAL) {
                    return new TermsFilter(new Term(key,(String)value));
                } else if (titanPredicate == Cmp.NOT_EQUAL) {
                    BooleanFilter q = new BooleanFilter();
                    q.add(new TermsFilter(new Term(key,(String)value)), BooleanClause.Occur.MUST_NOT);
                    return q;
                } else
                    throw new IllegalArgumentException("Relation is not supported for string value: " + titanPredicate);
            } else if (value instanceof Geoshape) {
                Preconditions.checkArgument(titanPredicate == Geo.WITHIN, "Relation is not supported for geo value: " + titanPredicate);
                Shape shape = ((Geoshape) value).convert2Spatial4j();
                SpatialArgs args = new SpatialArgs(SpatialOperation.IsWithin, shape);
                return getSpatialStrategy(key).makeFilter(args);
            } else throw new IllegalArgumentException("Unsupported type: " + value);
        } else if (condition instanceof Not) {
            BooleanFilter q = new BooleanFilter();
            q.add(convertQuery(((Not) condition).getChild(),informations), BooleanClause.Occur.MUST_NOT);
            return q;
        } else if (condition instanceof And) {
            BooleanFilter q = new BooleanFilter();
            for (Condition c : condition.getChildren()) {
                q.add(convertQuery(c,informations), BooleanClause.Occur.MUST);
            }
            return q;
        } else if (condition instanceof Or) {
            BooleanFilter q = new BooleanFilter();
            for (Condition c : condition.getChildren()) {
                q.add(convertQuery(c,informations), BooleanClause.Occur.SHOULD);
            }
            return q;
        } else throw new IllegalArgumentException("Invalid condition: " + condition);
    }

    @Override
    public Iterable<RawQuery.Result<String>> query(RawQuery query, KeyInformation.IndexRetriever informations, BaseTransaction tx) throws BackendException {
        Query q;
        try {
            q = new QueryParser(LUCENE_VERSION,"_all",analyzer).parse(query.getQuery());
        } catch (ParseException e) {
            throw new PermanentBackendException("Could not parse raw query: "+query.getQuery(),e);
        }

        try {
            IndexSearcher searcher = ((Transaction) tx).getSearcher(query.getStore());
            if (searcher == null) return ImmutableList.of(); //Index does not yet exist

            long time = System.currentTimeMillis();
            //TODO: can we make offset more efficient in Lucene?
            final int offset = query.getOffset();
            int adjustedLimit = query.hasLimit() ? query.getLimit() : Integer.MAX_VALUE - 1;
            if (adjustedLimit < Integer.MAX_VALUE-1-offset) adjustedLimit+=offset;
            else adjustedLimit = Integer.MAX_VALUE-1;
            TopDocs docs = searcher.search(q, adjustedLimit);
            log.debug("Executed query [{}] in {} ms",q, System.currentTimeMillis() - time);
            List<RawQuery.Result<String>> result = new ArrayList<RawQuery.Result<String>>(docs.scoreDocs.length);
            for (int i = offset; i < docs.scoreDocs.length; i++) {
                result.add(new RawQuery.Result<String>(searcher.doc(docs.scoreDocs[i].doc).getField(DOCID).stringValue(),docs.scoreDocs[i].score));
            }
            return result;
        } catch (IOException e) {
            throw new TemporaryBackendException("Could not execute Lucene query", e);
        }
    }

    @Override
    public BaseTransactionConfigurable beginTransaction(BaseTransactionConfig config) throws BackendException {
        return new Transaction(config);
    }

    @Override
    public boolean supports(KeyInformation information, TitanPredicate titanPredicate) {
        if (information.getCardinality()!= Cardinality.SINGLE) return false;
        Class<?> dataType = information.getDataType();
        Mapping mapping = Mapping.getMapping(information);
        if (mapping!=Mapping.DEFAULT && !AttributeUtil.isString(dataType)) return false;

        if (Number.class.isAssignableFrom(dataType)) {
            if (titanPredicate instanceof Cmp) return true;
        } else if (dataType == Geoshape.class) {
            return titanPredicate == Geo.WITHIN;
        } else if (AttributeUtil.isString(dataType)) {
            switch(mapping) {
                case DEFAULT:
                case TEXT:
                    return titanPredicate == Text.CONTAINS || titanPredicate == Text.CONTAINS_PREFIX; // || titanPredicate == Text.CONTAINS_REGEX;
                case STRING:
                    return titanPredicate == Cmp.EQUAL || titanPredicate==Cmp.NOT_EQUAL || titanPredicate==Text.PREFIX; //  || titanPredicate==Text.REGEX
            }
        }
        return false;
    }

    @Override
    public boolean supports(KeyInformation information) {
        if (information.getCardinality()!= Cardinality.SINGLE) return false;
        Class<?> dataType = information.getDataType();
        Mapping mapping = Mapping.getMapping(information);
        if (Number.class.isAssignableFrom(dataType) || dataType == Geoshape.class) {
            if (mapping==Mapping.DEFAULT) return true;
        } else if (AttributeUtil.isString(dataType)) {
            if (mapping==Mapping.DEFAULT || mapping==Mapping.STRING || mapping==Mapping.TEXT) return true;
        }
        return false;
    }

    @Override
    public String mapKey2Field(String key, KeyInformation information) {
        Preconditions.checkArgument(!StringUtils.containsAny(key,new char[]{' '}),"Invalid key name provided: %s",key);
        return key;
    }

    @Override
    public IndexFeatures getFeatures() {
        return LUCENE_FEATURES;
    }

    @Override
    public void close() throws BackendException {
        try {
            for (IndexWriter w : writers.values()) w.close();
        } catch (IOException e) {
            throw new PermanentBackendException("Could not close writers", e);
        }
    }

    @Override
    public void clearStorage() throws BackendException {
        try {
            FileUtils.deleteDirectory(new File(basePath));
        } catch (IOException e) {
            throw new PermanentBackendException("Could not delete lucene directory: " + basePath, e);
        }
    }

    private class Transaction implements BaseTransactionConfigurable {

        private final BaseTransactionConfig config;
        private final Set<String> updatedStores = Sets.newHashSet();
        private final Map<String, IndexSearcher> searchers = new HashMap<String, IndexSearcher>(4);

        private Transaction(BaseTransactionConfig config) {
            this.config = config;
        }

        private synchronized IndexSearcher getSearcher(String store) throws BackendException {
            IndexSearcher searcher = searchers.get(store);
            if (searcher == null) {
                IndexReader reader = null;
                try {
                    reader = DirectoryReader.open(getStoreDirectory(store));
                    searcher = new IndexSearcher(reader);
                } catch (IndexNotFoundException e) {
                    searcher = null;
                } catch (IOException e) {
                    throw new PermanentBackendException("Could not open index reader on store: " + store, e);
                }
                searchers.put(store, searcher);
            }
            return searcher;
        }

        public void postCommit() throws BackendException {
            close();
            searchers.clear();
        }


        @Override
        public void commit() throws BackendException {
            close();
        }

        @Override
        public void rollback() throws BackendException {
            close();
        }

        private void close() throws BackendException {
            try {
                for (IndexSearcher searcher : searchers.values()) {
                    if (searcher != null) searcher.getIndexReader().close();
                }
            } catch (IOException e) {
                throw new PermanentBackendException("Could not close searcher", e);
            }
        }

        @Override
        public BaseTransactionConfig getConfiguration() {
            return config;
        }
    }

}
