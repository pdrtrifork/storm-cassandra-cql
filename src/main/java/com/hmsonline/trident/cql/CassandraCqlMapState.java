package com.hmsonline.trident.cql;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.datastax.driver.core.exceptions.FunctionExecutionException;
import com.datastax.driver.core.exceptions.ReadFailureException;
import com.datastax.driver.core.exceptions.WriteFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.storm.trident.state.OpaqueValue;
import org.apache.storm.trident.state.StateFactory;
import org.apache.storm.trident.state.StateType;
import org.apache.storm.trident.state.TransactionalValue;
import org.apache.storm.trident.state.map.IBackingMap;
import org.apache.storm.Config;
import org.apache.storm.metric.api.CountMetric;
import org.apache.storm.task.IMetricsContext;
import org.apache.storm.topology.ReportedFailedException;

import com.datastax.driver.core.BatchStatement;
import com.datastax.driver.core.BatchStatement.Type;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.Statement;
import com.datastax.driver.core.exceptions.AlreadyExistsException;
import com.datastax.driver.core.exceptions.AuthenticationException;
import com.datastax.driver.core.exceptions.DriverException;
import com.datastax.driver.core.exceptions.DriverInternalError;
import com.datastax.driver.core.exceptions.InvalidConfigurationInQueryException;
import com.datastax.driver.core.exceptions.InvalidQueryException;
import com.datastax.driver.core.exceptions.InvalidTypeException;
import com.datastax.driver.core.exceptions.QueryExecutionException;
import com.datastax.driver.core.exceptions.QueryValidationException;
import com.datastax.driver.core.exceptions.ReadTimeoutException;
import com.datastax.driver.core.exceptions.SyntaxError;
import com.datastax.driver.core.exceptions.TraceRetrievalException;
import com.datastax.driver.core.exceptions.TruncateException;
import com.datastax.driver.core.exceptions.UnauthorizedException;
import com.datastax.driver.core.exceptions.UnavailableException;
import com.datastax.driver.core.exceptions.WriteTimeoutException;
import com.hmsonline.trident.cql.mappers.CqlRowMapper;

/**
 * @param <T> The generic state to back
 * @author robertlee
 */
public class CassandraCqlMapState<T> implements IBackingMap<T> {
    private static final Logger LOG = LoggerFactory.getLogger(CassandraCqlMapState.class);

    @SuppressWarnings("serial")
    public static class Options<T> implements Serializable {
        public int localCacheSize = 5000;
        public String globalKey = "globalkey";
        public String keyspace;
        public String tableName;
        public Integer ttl = 86400; // 1 day
        public Type batchType = Type.LOGGED;
		// Unique name of storm metrics. Must ne unique in topology
		public String mapStateMetricName = this.toString();
		public int maxBatchSize = 100;
    }

    /////////////////////////////////////////////
    // Static Methods For Specific State Type StateFactory
    /////////////////////////////////////////////

    @SuppressWarnings("rawtypes")
    public static StateFactory opaque(CqlRowMapper mapper) {
        Options<OpaqueValue> options = new Options<OpaqueValue>();
        return opaque(mapper, options);
    }

    @SuppressWarnings("rawtypes")
    public static StateFactory opaque(CqlRowMapper mapper, Options<OpaqueValue> opts) {
        return new CassandraCqlMapStateFactory(mapper, StateType.OPAQUE, opts);
    }

    @SuppressWarnings("rawtypes")
    public static StateFactory transactional(CqlRowMapper mapper) {
        Options<TransactionalValue> options = new Options<TransactionalValue>();
        return transactional(mapper, options);
    }

    @SuppressWarnings("rawtypes")
    public static StateFactory transactional(CqlRowMapper mapper, Options<TransactionalValue> opts) {
        return new CassandraCqlMapStateFactory(mapper, StateType.TRANSACTIONAL, opts);
    }

    @SuppressWarnings("rawtypes")
    public static StateFactory nonTransactional(CqlRowMapper mapper) {
        Options<Object> options = new Options<Object>();
        return nonTransactional(mapper, options);
    }

    @SuppressWarnings("rawtypes")
    public static StateFactory nonTransactional(CqlRowMapper mapper, Options<Object> opts) {
        return new CassandraCqlMapStateFactory(mapper, StateType.NON_TRANSACTIONAL, opts);
    }

    //////////////////////////////
    // Instance Variables
    //////////////////////////////
    // private Options<T> options;
	protected final Session session;

    @SuppressWarnings("rawtypes")
	protected CqlRowMapper mapper;

    private Options<T> options;

    // Metrics for storm metrics registering
    CountMetric _mreads;
    CountMetric _mwrites;
    CountMetric _mexceptions;

    @SuppressWarnings({"rawtypes"})
    public CassandraCqlMapState(Session session, CqlRowMapper mapper, Options<T> options, Map conf) {
        this.options = options;
        this.session = session;
        this.mapper = mapper;
    }

    ////////////////////////////////////
    // Overridden Methods for IBackingMap
    ////////////////////////////////////
    @SuppressWarnings("unchecked")
    @Override
    public List<T> multiGet(List<List<Object>> keys) {
        try {
            List<T> values = new ArrayList<T>();

            for (List<Object> rowKey : keys) {
                Statement statement = mapper.retrieve(rowKey);
                ResultSet results = session.execute(statement);
                // TODO: Better way to check for empty results besides accessing entire results list
                Iterator<Row> rowIter = results.iterator();
                Row row;
                if (results != null && rowIter.hasNext() && (row = rowIter.next()) != null) {
                    if (rowIter.hasNext()) {
                        LOG.error("Found non-unique value for key [{}]", rowKey);
                    } else {
                        values.add((T) mapper.getValue(row));
                    }
                } else {
                    values.add(null);
                }
            }

            _mreads.incrBy(values.size());
            LOG.debug("Retrieving the following keys: {} with values: {}", keys, values);
            return values;
        } catch (Exception e) {
            checkCassandraException(e);
            throw new IllegalStateException("Impossible to reach this code");
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void multiPut(List<List<Object>> keys, List<T> values) {
        LOG.debug("Putting the following keys: {} with values: {}", keys, values);
        try {
            List<Statement> statements = new ArrayList<Statement>();
            
            // Retrieve the mapping statement for the key,val pair
            for (int i = 0; i < keys.size(); i++) {
                List<Object> key = keys.get(i);
                T val = values.get(i);
                Statement retrievedStatment = mapper.map(key, val);
                if (retrievedStatment instanceof BatchStatement) { // Allows for BatchStatements to be returned by the mapper.
					BatchStatement batchedStatment = (BatchStatement) retrievedStatment;
					statements.addAll(batchedStatment.getStatements());
				} else {
					statements.add(retrievedStatment);
				}
            }

            // Execute all the statements as a batch.
            if (options.maxBatchSize == 1) {
                for (Statement statement : statements) {
                    session.execute(statement);
                }
            } else {
                BatchStatement batch = new BatchStatement(options.batchType);
                int i = 0;
                for (Statement statement : statements) {
                    batch.add(statement);
                    i++;
                    if(i >= options.maxBatchSize) {
                        session.execute(batch);
                        batch = new BatchStatement(options.batchType);
                        i = 0;
                    }
                }
                if (i > 0) {
                    session.execute(batch);
                }
            }

            _mwrites.incrBy(statements.size());
        } catch (Exception e) {
            checkCassandraException(e);
            LOG.error("Exception {} caught.", e);
        }
    }

    protected void checkCassandraException(Exception e) {
        _mexceptions.incr();
        if (e instanceof AlreadyExistsException ||
                e instanceof AuthenticationException ||
                e instanceof DriverException ||
                e instanceof DriverInternalError ||
                e instanceof InvalidConfigurationInQueryException ||
                e instanceof InvalidQueryException ||
                e instanceof InvalidTypeException ||
                e instanceof QueryExecutionException ||
                e instanceof QueryValidationException ||
                e instanceof ReadTimeoutException ||
                e instanceof SyntaxError ||
                e instanceof TraceRetrievalException ||
                e instanceof TruncateException ||
                e instanceof UnauthorizedException ||
                e instanceof UnavailableException ||
                e instanceof ReadTimeoutException ||
                e instanceof WriteTimeoutException ||
                e instanceof ReadFailureException ||
                e instanceof WriteFailureException ||
                e instanceof FunctionExecutionException) {
            throw new ReportedFailedException(e);
        } else {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("rawtypes")
    public void registerMetrics(Map conf, IMetricsContext context, String mapStateMetricName) {
        int bucketSize = ((Number)conf.get(Config.TOPOLOGY_BUILTIN_METRICS_BUCKET_SIZE_SECS)).intValue();
        String metricBaseName = "cassandra/" + mapStateMetricName;
		_mreads = context.registerMetric(metricBaseName + "/readCount", new CountMetric(), bucketSize);
        _mwrites = context.registerMetric(metricBaseName + "/writeCount", new CountMetric(), bucketSize);
        _mexceptions = context.registerMetric(metricBaseName + "/exceptionCount", new CountMetric(), bucketSize);
    }
}
