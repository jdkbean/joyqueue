package com.jd.journalq.message;

import java.util.Map;

/**
 *
 *
 * 属性 | 长度(Byte) | 说明
 -- | -- |--
 存储长度 | 4 |
 存储时间 | 4 |
 事务id | 变长 |
 事务查询id | 变长 |
 发送时间 | 8 |
 存储时间 | 4 | 保存与发送时间的偏移量
 属性长度| 2 |
 属性 | 变长 |
 *
 * @author lining11
 * Date: 2018/8/17
 */
public class BrokerPrepare implements JournalLog {
    private int size;
    //开始时间
    private long startTime;
    //存储时间
    private int storeTime;
    //事物ID
    private String txId;
    //事物查询标识
    private String queryId;

    private String topic;

    private String app;

    private long timeout;

    private Map<Object, Object> attrs;

    @Override
    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    @Override
    public byte getType() {
        return TYPE_TX_PREPARE;
    }

    @Override
    public int getStoreTime() {
        return storeTime;
    }

    @Override
    public void setStoreTime(int storeTime) {
        this.storeTime = storeTime;
    }

    public String getTxId() {
        return txId;
    }

    public void setTxId(String txId) {
        this.txId = txId;
    }

    public String getQueryId() {
        return queryId;
    }

    public void setQueryId(String queryId) {
        this.queryId = queryId;
    }

    public Map<Object, Object> getAttrs() {
        return attrs;
    }

    public void setAttrs(Map<Object, Object> attrs) {
        this.attrs = attrs;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public void setApp(String app) {
        this.app = app;
    }

    public String getApp() {
        return app;
    }

    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }

    public long getTimeout() {
        return timeout;
    }

    @Override
    public String toString() {
        return "BrokerPrepare{" +
                "size=" + size +
                ", startTime=" + startTime +
                ", storeTime=" + storeTime +
                ", txId='" + txId + '\'' +
                ", queryId='" + queryId + '\'' +
                ", topic='" + topic + '\'' +
                ", attrs=" + attrs +
                '}';
    }
}