package com.jd.journalq.nsr.ignite.dao.impl;

import com.jd.journalq.model.PageResult;
import com.jd.journalq.model.QPageQuery;
import com.jd.journalq.nsr.ignite.dao.ConsumerConfigDao;
import com.jd.journalq.nsr.ignite.dao.IgniteDao;
import com.jd.journalq.nsr.ignite.model.IgniteConsumerConfig;
import com.jd.journalq.nsr.model.ConsumerQuery;
import org.apache.ignite.Ignite;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cache.QueryEntity;
import org.apache.ignite.cache.QueryIndex;
import org.apache.ignite.cache.query.SqlQuery;
import org.apache.ignite.configuration.CacheConfiguration;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static com.jd.journalq.nsr.ignite.model.IgniteBaseModel.SCHEMA;
import static com.jd.journalq.nsr.ignite.model.IgniteConsumerConfig.*;
import static com.jd.journalq.nsr.ignite.model.IgniteConsumerConfig.COLUMN_ID;

public class IgniteConsumerConfigDao implements ConsumerConfigDao {
    public static final String cacheName = "consumer_config";
    public static CacheConfiguration<String, IgniteConsumerConfig> cacheCfg;
    private IgniteDao igniteDao;

    static {
        cacheCfg = new CacheConfiguration<>();
        cacheCfg.setName(cacheName);
        cacheCfg.setSqlSchema(SCHEMA);
        cacheCfg.setCacheMode(CacheMode.REPLICATED);
        cacheCfg.setAtomicityMode(CacheAtomicityMode.TRANSACTIONAL);
        QueryEntity queryEntity = new QueryEntity();
        queryEntity.setKeyType(String.class.getName());
        queryEntity.setValueType(IgniteConsumerConfig.class.getName());
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put(COLUMN_ID, String.class.getName());
        fields.put(COLUMN_NAMESPACE, String.class.getName());
        fields.put(COLUMN_TOPIC, String.class.getName());
        fields.put(COLUMN_APP, String.class.getName());
        fields.put(COLUMN_NEAR_BY, Boolean.class.getName());
        fields.put(COLUMN_ARCHIVE, Boolean.class.getName());
        fields.put(COLUMN_RETRY, Boolean.class.getName());
        fields.put(COLUMN_CONCURRENT, Integer.class.getName());
        fields.put(COLUMN_ACK_TIMEOUT, Integer.class.getName());
        fields.put(COLUMN_BATCH_SIZE, Short.class.getName());
        fields.put(COLUMN_BLACK_LIST, String.class.getName());
        fields.put(COLUMN_DELAY, Integer.class.getName());
        fields.put(COLUMN_MAX_RETRYS, Integer.class.getName());
        fields.put(COLUMN_MAX_RETRY_DELAY, Integer.class.getName());
        fields.put(COLUMN_RETRY_DELAY, Integer.class.getName());
        fields.put(COLUMN_EXPIRE_TIME, Integer.class.getName());
        fields.put(COLUMN_PAUSED, Boolean.class.getName());
        fields.put(COLUMN_ERROR_TIMES, Integer.class.getName());
        fields.put(COLUMN_MAX_PARTITION_NUM, Integer.class.getName());
        fields.put(COLUMN_RETRY_READ_PROBABILITY, Integer.class.getName());
        queryEntity.setFields(fields);
        queryEntity.setTableName(cacheName);
        queryEntity.setIndexes(Arrays.asList(new QueryIndex(COLUMN_ID)));
        cacheCfg.setQueryEntities(Arrays.asList(queryEntity));
    }

    public IgniteConsumerConfigDao(Ignite ignite) {
        this.igniteDao = new IgniteDao(ignite, cacheCfg);
    }


    @Override
    public IgniteConsumerConfig findById(String id) {
        return igniteDao.getById(id);
    }

    @Override
    public void add(IgniteConsumerConfig model) {
        igniteDao.addOrUpdate(model);
    }

    @Override
    public void addOrUpdate(IgniteConsumerConfig model) {
        igniteDao.addOrUpdate(model);
    }

    @Override
    public void deleteById(String id) {
        igniteDao.deleteById(id);
    }

    @Override
    public PageResult<IgniteConsumerConfig> pageQuery(QPageQuery<ConsumerQuery> pageQuery) {
        return igniteDao.pageQuery(buildSqlQuery(pageQuery.getQuery()), pageQuery.getPagination());
    }

    @Override
    public List<IgniteConsumerConfig> list(ConsumerQuery query) {
        return igniteDao.query(buildSqlQuery(query));
    }

    private SqlQuery buildSqlQuery(ConsumerQuery query) {
        IgniteDao.SimpleSqlBuilder sqlBuilder = IgniteDao.SimpleSqlBuilder.create(IgniteConsumerConfig.class);
        if (query != null) {
            if (query.getTopic() != null && !query.getTopic().isEmpty()) {
                sqlBuilder.and(COLUMN_TOPIC, query.getTopic());
            }
            if (query.getNamespace() != null && !query.getNamespace().isEmpty()) {
                sqlBuilder.and(COLUMN_NAMESPACE, query.getNamespace());
            }
            if (query.getApp() != null && !query.getApp().isEmpty()) {
                sqlBuilder.and(COLUMN_APP, query.getApp());
            }
        }
        return sqlBuilder.build();
    }
}