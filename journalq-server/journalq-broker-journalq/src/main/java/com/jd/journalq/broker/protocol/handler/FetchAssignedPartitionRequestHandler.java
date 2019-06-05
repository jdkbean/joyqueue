package com.jd.journalq.broker.protocol.handler;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.jd.journalq.broker.protocol.JournalqCommandHandler;
import com.jd.journalq.broker.protocol.JournalqContext;
import com.jd.journalq.broker.protocol.JournalqContextAware;
import com.jd.journalq.broker.protocol.coordinator.Coordinator;
import com.jd.journalq.broker.protocol.coordinator.assignment.PartitionAssignmentHandler;
import com.jd.journalq.broker.protocol.coordinator.domain.PartitionAssignment;
import com.jd.journalq.broker.helper.SessionHelper;
import com.jd.journalq.domain.DataCenter;
import com.jd.journalq.domain.PartitionGroup;
import com.jd.journalq.domain.TopicConfig;
import com.jd.journalq.domain.TopicName;
import com.jd.journalq.exception.JournalqCode;
import com.jd.journalq.network.command.BooleanAck;
import com.jd.journalq.network.command.FetchAssignedPartitionAckData;
import com.jd.journalq.network.command.FetchAssignedPartitionData;
import com.jd.journalq.network.command.FetchAssignedPartitionRequest;
import com.jd.journalq.network.command.FetchAssignedPartitionResponse;
import com.jd.journalq.network.command.JournalqCommandType;
import com.jd.journalq.network.session.Connection;
import com.jd.journalq.network.transport.Transport;
import com.jd.journalq.network.transport.command.Command;
import com.jd.journalq.network.transport.command.Type;
import com.jd.journalq.nsr.NameService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * FetchAssignedPartitionRequestHandler
 * author: gaohaoxiang
 * email: gaohaoxiang@jd.com
 * date: 2018/12/4
 */
// TODO 部分逻辑移到partitionAssignmentHandler
public class FetchAssignedPartitionRequestHandler implements JournalqCommandHandler, Type, JournalqContextAware {

    protected static final Logger logger = LoggerFactory.getLogger(FetchAssignedPartitionRequestHandler.class);

    private Coordinator coordinator;
    private PartitionAssignmentHandler partitionAssignmentHandler;
    private NameService nameService;

    @Override
    public void setJournalqContext(JournalqContext journalqContext) {
        this.coordinator = journalqContext.getCoordinator();
        this.partitionAssignmentHandler = journalqContext.getPartitionAssignmentHandler();
        this.nameService = journalqContext.getBrokerContext().getNameService();
    }

    @Override
    public Command handle(Transport transport, Command command) {
        FetchAssignedPartitionRequest fetchAssignedPartitionRequest = (FetchAssignedPartitionRequest) command.getPayload();
        Connection connection = SessionHelper.getConnection(transport);

        // 客户端是临时连接，用ip作为唯一标识
        String connectionId = ((InetSocketAddress) transport.remoteAddress()).getHostString();

        if (connection == null || !connection.isAuthorized(fetchAssignedPartitionRequest.getApp())) {
            logger.warn("connection is not exists, transport: {}, app: {}", transport, fetchAssignedPartitionRequest.getApp());
            return BooleanAck.build(JournalqCode.FW_CONNECTION_NOT_EXISTS.getCode());
        }

        if (!coordinator.isCurrentGroup(fetchAssignedPartitionRequest.getApp())) {
            logger.warn("coordinator is not current, app: {}, topics: {}, transport: {}", fetchAssignedPartitionRequest.getApp(), fetchAssignedPartitionRequest.getData(), transport);
            return BooleanAck.build(JournalqCode.FW_COORDINATOR_NOT_AVAILABLE.getCode());
        }

        Map<String, FetchAssignedPartitionAckData> topicPartitions = Maps.newHashMapWithExpectedSize(fetchAssignedPartitionRequest.getData().size());
        for (FetchAssignedPartitionData fetchAssignedPartitionData : fetchAssignedPartitionRequest.getData()) {
            FetchAssignedPartitionAckData fetchAssignedPartitionAckData = assignPartition(fetchAssignedPartitionData,
                    fetchAssignedPartitionRequest.getApp(), connection.getRegion(), connectionId, connection.getAddressStr());

            if (fetchAssignedPartitionAckData == null) {
                logger.warn("partitionAssignment is null, topic: {}, app: {}, transport: {}", fetchAssignedPartitionData, fetchAssignedPartitionRequest.getApp(), transport);
                fetchAssignedPartitionAckData = new FetchAssignedPartitionAckData(JournalqCode.FW_COORDINATOR_PARTITION_ASSIGNOR_ERROR);
            }
            topicPartitions.put(fetchAssignedPartitionData.getTopic(), fetchAssignedPartitionAckData);
        }

        FetchAssignedPartitionResponse fetchAssignedPartitionResponse = new FetchAssignedPartitionResponse();
        fetchAssignedPartitionResponse.setTopicPartitions(topicPartitions);
        return new Command(fetchAssignedPartitionResponse);
    }

    protected FetchAssignedPartitionAckData assignPartition(FetchAssignedPartitionData fetchAssignedPartitionData, String app, String region, String connectionId, String connectionHost) {
        TopicName topicName = TopicName.parse(fetchAssignedPartitionData.getTopic());
        TopicConfig topicConfig = nameService.getTopicConfig(topicName);
        if (topicConfig == null) {
            return null;
        }
        List<PartitionGroup> topicPartitionGroups = null;
        if (fetchAssignedPartitionData.isNearby()) {
            topicPartitionGroups = getTopicRegionPartitionGroup(topicConfig, region);
        } else {
            topicPartitionGroups = Lists.newArrayList(topicConfig.getPartitionGroups().values());
        }
        if (CollectionUtils.isEmpty(topicPartitionGroups)) {
            return new FetchAssignedPartitionAckData(JournalqCode.FW_COORDINATOR_PARTITION_ASSIGNOR_NO_PARTITIONS);
        }

        PartitionAssignment partitionAssignment = partitionAssignmentHandler.assign(fetchAssignedPartitionData.getTopic(),
                app, connectionId, connectionHost, fetchAssignedPartitionData.getSessionTimeout(), topicPartitionGroups);
        return new FetchAssignedPartitionAckData(partitionAssignment.getPartitions(), JournalqCode.SUCCESS);
    }

    protected List<PartitionGroup> getTopicRegionPartitionGroup(TopicConfig topicConfig, String region) {
        Collection<PartitionGroup> partitionGroups = topicConfig.getPartitionGroups().values();
        List<PartitionGroup> result = Lists.newArrayListWithCapacity(partitionGroups.size());
        for (PartitionGroup partitionGroup : partitionGroups) {
            if (partitionGroup.getLeaderBroker() != null) {
                DataCenter brokerDataCenter = nameService.getDataCenter(partitionGroup.getLeaderBroker().getIp());
                if (StringUtils.isBlank(region) || brokerDataCenter == null || StringUtils.equals(brokerDataCenter.getRegion(), region)) {
                    result.add(partitionGroup);
                }
            }
        }
        return result;
    }

    @Override
    public int type() {
        return JournalqCommandType.FETCH_ASSIGNED_PARTITION_REQUEST.getCode();
    }
}