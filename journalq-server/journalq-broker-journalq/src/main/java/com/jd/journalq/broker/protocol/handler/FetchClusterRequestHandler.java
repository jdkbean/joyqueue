package com.jd.journalq.broker.protocol.handler;

import com.alibaba.fastjson.JSON;
import com.google.common.collect.Maps;
import com.jd.journalq.broker.BrokerContext;
import com.jd.journalq.broker.BrokerContextAware;
import com.jd.journalq.broker.config.BrokerConfig;
import com.jd.journalq.broker.helper.SessionHelper;
import com.jd.journalq.broker.protocol.JournalqCommandHandler;
import com.jd.journalq.broker.protocol.converter.BrokerNodeConverter;
import com.jd.journalq.broker.protocol.converter.PolicyConverter;
import com.jd.journalq.domain.Broker;
import com.jd.journalq.domain.Consumer;
import com.jd.journalq.domain.DataCenter;
import com.jd.journalq.domain.PartitionGroup;
import com.jd.journalq.domain.Producer;
import com.jd.journalq.domain.TopicConfig;
import com.jd.journalq.domain.TopicName;
import com.jd.journalq.domain.TopicType;
import com.jd.journalq.exception.JournalqCode;
import com.jd.journalq.network.command.BooleanAck;
import com.jd.journalq.network.command.FetchClusterRequest;
import com.jd.journalq.network.command.FetchClusterResponse;
import com.jd.journalq.network.command.JournalqCommandType;
import com.jd.journalq.network.command.Topic;
import com.jd.journalq.network.command.TopicPartition;
import com.jd.journalq.network.command.TopicPartitionGroup;
import com.jd.journalq.network.domain.BrokerNode;
import com.jd.journalq.network.session.Connection;
import com.jd.journalq.network.transport.Transport;
import com.jd.journalq.network.transport.command.Command;
import com.jd.journalq.network.transport.command.Type;
import com.jd.journalq.nsr.NameService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;

/**
 * FetchClusterRequestHandler
 * author: gaohaoxiang
 * email: gaohaoxiang@jd.com
 * date: 2018/11/30
 */
public class FetchClusterRequestHandler implements JournalqCommandHandler, Type, BrokerContextAware {

    protected static final Logger logger = LoggerFactory.getLogger(FetchClusterRequestHandler.class);

    private BrokerConfig brokerConfig;
    private NameService nameService;
    private BrokerContext brokerContext;

    @Override
    public void setBrokerContext(BrokerContext brokerContext) {
        this.brokerConfig = brokerContext.getBrokerConfig();
        this.nameService = brokerContext.getNameService();
        this.brokerContext = brokerContext;
    }

    @Override
    public Command handle(Transport transport, Command command) {
        FetchClusterRequest fetchClusterRequest = (FetchClusterRequest) command.getPayload();
        Connection connection = SessionHelper.getConnection(transport);

        if (connection == null || !connection.isAuthorized(fetchClusterRequest.getApp())) {
            logger.warn("connection is not exists, transport: {}, app: {}", transport, fetchClusterRequest.getApp());
            return BooleanAck.build(JournalqCode.FW_CONNECTION_NOT_EXISTS.getCode());
        }

        Map<String, Topic> topics = Maps.newHashMapWithExpectedSize(fetchClusterRequest.getTopics().size());
        Map<Integer, BrokerNode> brokers = Maps.newHashMap();

        for (String topicId : fetchClusterRequest.getTopics()) {
            Topic topic = getTopicMetadata(connection, topicId, fetchClusterRequest.getApp(), brokers);
            topics.put(topicId, topic);
        }

        FetchClusterResponse fetchClusterResponse = new FetchClusterResponse();
        fetchClusterResponse.setTopics(topics);
        fetchClusterResponse.setBrokers(brokers);

        // TODO 临时日志
        logger.debug("fetch cluster, address: {}, topics: {}, app: {}, metadata: {}",
                transport, fetchClusterRequest.getTopics(), fetchClusterRequest.getApp(), JSON.toJSONString(fetchClusterResponse));

        return new Command(fetchClusterResponse);
    }

    protected Topic getTopicMetadata(Connection connection, String topic, String app, Map<Integer, BrokerNode> brokers) {
        TopicName topicName = TopicName.parse(topic);
        TopicConfig topicConfig = nameService.getTopicConfig(topicName);

        Topic result = new Topic();
        result.setTopic(topic);
        result.setType(TopicType.TOPIC);

        if (topicConfig == null) {
            logger.warn("topic not exist, topic: {}, app: {}", topic, app);
            result.setCode(JournalqCode.FW_TOPIC_NOT_EXIST);
            return result;
        }

        Producer producer = nameService.getProducerByTopicAndApp(topicName, app);
        Consumer consumer = nameService.getConsumerByTopicAndApp(topicName, app);

        if (producer == null && consumer == null) {
            logger.warn("topic policy not exist, topic: {}, app: {}", topic, app);
            result.setCode(JournalqCode.CN_NO_PERMISSION);
            return result;
        }

        if (producer != null) {
            if (producer.getProducerPolicy() == null) {
                result.setProducerPolicy(PolicyConverter.convertProducer(brokerContext.getProducerPolicy()));
            } else {
                result.setProducerPolicy(PolicyConverter.convertProducer(producer.getProducerPolicy()));
            }
        }

        if (consumer != null) {
            if (consumer.getConsumerPolicy() == null) {
                result.setConsumerPolicy(PolicyConverter.convertConsumer(brokerContext.getConsumerPolicy()));
            } else {
                result.setConsumerPolicy(PolicyConverter.convertConsumer(consumer.getConsumerPolicy()));
            }
            result.setType(consumer.getTopicType());
        }

        result.setCode(JournalqCode.SUCCESS);
        result.setPartitionGroups(convertTopicPartitionGroups(connection, topicConfig.getPartitionGroups().values(), brokers));
        return result;
    }

    protected Map<Integer, TopicPartitionGroup> convertTopicPartitionGroups(Connection connection, Collection<PartitionGroup> partitionGroups, Map<Integer, BrokerNode> brokers) {
        Map<Integer, TopicPartitionGroup> result = Maps.newLinkedHashMap();
        for (PartitionGroup partitionGroup : partitionGroups) {
            TopicPartitionGroup topicPartitionGroup = convertTopicPartitionGroup(connection, partitionGroup, brokers);
            if (topicPartitionGroup != null) {
                result.put(partitionGroup.getGroup(), topicPartitionGroup);
            }
        }
        return result;
    }

    protected TopicPartitionGroup convertTopicPartitionGroup(Connection connection, PartitionGroup partitionGroup, Map<Integer, BrokerNode> brokers) {
        Map<Short, TopicPartition> partitions = Maps.newLinkedHashMap();

        Broker leaderBroker = partitionGroup.getLeaderBroker();
        if (leaderBroker != null) {
            DataCenter brokerDataCenter = nameService.getDataCenter(leaderBroker.getIp());
            brokers.put(partitionGroup.getLeader(), BrokerNodeConverter.convertBrokerNode(leaderBroker, brokerDataCenter, connection.getRegion()));
        }

        for (Short partition : partitionGroup.getPartitions()) {
            partitions.put(partition, convertTopicPartition(partitionGroup, partition));
        }

        TopicPartitionGroup result = new TopicPartitionGroup();
        result.setId(partitionGroup.getGroup());
        result.setLeader(partitionGroup.getLeader());
        result.setPartitions(partitions);
        return result;
    }

    protected TopicPartition convertTopicPartition(PartitionGroup partitionGroup, short partition) {
        TopicPartition result = new TopicPartition();
        result.setId(partition);
        return result;
    }

    @Override
    public int type() {
        return JournalqCommandType.FETCH_CLUSTER_REQUEST.getCode();
    }
}