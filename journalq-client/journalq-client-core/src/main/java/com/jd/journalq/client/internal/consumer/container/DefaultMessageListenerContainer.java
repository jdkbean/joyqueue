package com.jd.journalq.client.internal.consumer.container;

import com.google.common.collect.Maps;
import com.jd.journalq.client.internal.cluster.ClusterClientManager;
import com.jd.journalq.client.internal.cluster.ClusterManager;
import com.jd.journalq.client.internal.consumer.BaseMessageListener;
import com.jd.journalq.client.internal.consumer.BatchMessageListener;
import com.jd.journalq.client.internal.consumer.MessageListener;
import com.jd.journalq.client.internal.consumer.MessageListenerContainer;
import com.jd.journalq.client.internal.consumer.config.ConsumerConfig;
import com.jd.journalq.client.internal.consumer.exception.ConsumerException;
import com.jd.journalq.client.internal.consumer.support.TopicMessageConsumer;
import com.jd.journalq.client.internal.consumer.transport.ConsumerClientManager;
import com.jd.journalq.client.internal.nameserver.NameServerConfig;
import com.jd.journalq.exception.JMQCode;
import com.jd.journalq.toolkit.lang.Preconditions;
import com.jd.journalq.toolkit.service.Service;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * DefaultMessageListenerContainer
 * author: gaohaoxiang
 * email: gaohaoxiang@jd.com
 * date: 2018/12/25
 */
public class DefaultMessageListenerContainer extends Service implements MessageListenerContainer {

    protected static final Logger logger = LoggerFactory.getLogger(DefaultMessageListenerContainer.class);

    private ConsumerConfig config;
    private NameServerConfig nameServerConfig;
    private ClusterManager clusterManager;
    private ClusterClientManager clusterClientManager;
    private ConsumerClientManager consumerClientManager;
    private Map<String, TopicMessageConsumer> topicConsumerMap = Maps.newHashMap();

    public DefaultMessageListenerContainer(ConsumerConfig config, NameServerConfig nameServerConfig, ClusterManager clusterManager, ClusterClientManager clusterClientManager, ConsumerClientManager consumerClientManager) {
        Preconditions.checkArgument(config != null, "consumer not null");
        Preconditions.checkArgument(nameServerConfig != null, "nameserver not null");
        Preconditions.checkArgument(clusterManager != null, "clusterManager not null");
        Preconditions.checkArgument(clusterClientManager != null, "clusterClientManager not null");
        Preconditions.checkArgument(consumerClientManager != null, "consumerClientManager not null");

        this.config = config;
        this.nameServerConfig = nameServerConfig;
        this.clusterManager = clusterManager;
        this.clusterClientManager = clusterClientManager;
        this.consumerClientManager = consumerClientManager;
    }

    @Override
    protected void doStart() throws Exception {
        for (Map.Entry<String, TopicMessageConsumer> entry : topicConsumerMap.entrySet()) {
            if (!entry.getValue().isStarted()) {
                entry.getValue().start();
            }
        }
//        logger.info("consumer container is started");
    }

    @Override
    protected void doStop() {
        for (Map.Entry<String, TopicMessageConsumer> entry : topicConsumerMap.entrySet()) {
            entry.getValue().stop();
        }
//        logger.info("consumer container is stopped");
    }

    @Override
    public synchronized void addListener(String topic, MessageListener messageListener) {
        doAddListener(topic, messageListener);
    }

    @Override
    public synchronized void addBatchListener(String topic, BatchMessageListener messageListener) {
        doAddListener(topic, messageListener);
    }

    protected void doAddListener(String topic, BaseMessageListener messageListener) {
        Preconditions.checkArgument(StringUtils.isNotBlank(topic), "topic not blank");

        TopicMessageConsumer topicMessageConsumer = topicConsumerMap.get(topic);
        if (topicMessageConsumer == null) {
            topicMessageConsumer = createTopicMessageConsumer(topic);
            topicConsumerMap.put(topic, topicMessageConsumer);

            if (isStarted()) {
                try {
                    topicMessageConsumer.start();
                } catch (Exception e) {
                    logger.error("start topic message consumer exception, topic : {}", topic, e);
                    throw new ConsumerException("start message consumer exception", JMQCode.CN_SERVICE_NOT_AVAILABLE.getCode());
                }
            }
        }

        topicMessageConsumer.addListener(messageListener);
    }

    protected TopicMessageConsumer createTopicMessageConsumer(String topic) {
        return new TopicMessageConsumer(topic, config, nameServerConfig, clusterManager, clusterClientManager, consumerClientManager);
    }
}