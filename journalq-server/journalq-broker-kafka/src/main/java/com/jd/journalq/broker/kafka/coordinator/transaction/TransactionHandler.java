package com.jd.journalq.broker.kafka.coordinator.transaction;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.jd.journalq.broker.kafka.KafkaErrorCode;
import com.jd.journalq.broker.kafka.coordinator.Coordinator;
import com.jd.journalq.broker.kafka.coordinator.transaction.domain.TransactionMetadata;
import com.jd.journalq.broker.kafka.coordinator.transaction.domain.TransactionPrepare;
import com.jd.journalq.broker.kafka.coordinator.transaction.domain.TransactionState;
import com.jd.journalq.broker.kafka.coordinator.transaction.exception.TransactionException;
import com.jd.journalq.broker.kafka.coordinator.transaction.synchronizer.TransactionSynchronizer;
import com.jd.journalq.broker.kafka.model.PartitionMetadataAndError;
import com.jd.journalq.domain.Broker;
import com.jd.journalq.domain.PartitionGroup;
import com.jd.journalq.domain.TopicConfig;
import com.jd.journalq.domain.TopicName;
import com.jd.journalq.exception.JournalqCode;
import com.jd.journalq.exception.JournalqException;
import com.jd.journalq.nsr.NameService;
import com.jd.journalq.toolkit.service.Service;
import com.jd.journalq.toolkit.time.SystemClock;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TransactionHandler
 * author: gaohaoxiang
 * email: gaohaoxiang@jd.com
 * date: 2019/4/10
 */
public class TransactionHandler extends Service {

    protected static final Logger logger = LoggerFactory.getLogger(TransactionHandler.class);

    private Coordinator coordinator;
    private TransactionMetadataManager transactionMetadataManager;
    private ProducerIdManager producerIdManager;
    private TransactionSynchronizer transactionSynchronizer;
    private NameService nameService;

    public TransactionHandler(Coordinator coordinator, TransactionMetadataManager transactionMetadataManager, ProducerIdManager producerIdManager,
                              TransactionSynchronizer transactionSynchronizer, NameService nameService) {
        this.coordinator = coordinator;
        this.transactionMetadataManager = transactionMetadataManager;
        this.producerIdManager = producerIdManager;
        this.transactionSynchronizer = transactionSynchronizer;
        this.nameService = nameService;
    }

    public TransactionMetadata initProducer(String clientId, String transactionId, int transactionTimeout) {
        checkCoordinatorState(clientId, transactionId);

        TransactionMetadata transactionMetadata = transactionMetadataManager.getTransaction(transactionId);
        if (transactionMetadata == null) {
            transactionMetadata = transactionMetadataManager.getOrCreateTransaction(new TransactionMetadata(transactionId, clientId,
                    producerIdManager.generateId(), transactionTimeout, SystemClock.now()));
        }

        synchronized (transactionMetadata) {
            return doInitProducer(transactionMetadata, transactionTimeout);
        }
    }

    protected TransactionMetadata doInitProducer(TransactionMetadata transactionMetadata, int transactionTimeout) {
        transactionMetadata.clear();
        transactionMetadata.nextProducerEpoch();
        transactionMetadata.nextEpoch();
        transactionMetadata.setTimeout(transactionTimeout);
        transactionMetadata.updateLastTime();
        transactionMetadata.transitionStateTo(TransactionState.EMPTY);
        return transactionMetadata;
    }

    public Map<String, List<PartitionMetadataAndError>> addPartitionsToTxn(String clientId, String transactionId, long producerId, short producerEpoch, Map<String, List<Integer>> partitions) {
        checkCoordinatorState(clientId, transactionId);

        TransactionMetadata transactionMetadata = transactionMetadataManager.getTransaction(transactionId);
        if (transactionMetadata == null || transactionMetadata.getProducerId() != producerId || !StringUtils.equals(transactionMetadata.getApp(), clientId)) {
            throw new TransactionException(KafkaErrorCode.INVALID_PRODUCER_ID_MAPPING.getCode());
        }
        if (transactionMetadata.getProducerEpoch() != producerEpoch) {
            throw new TransactionException(KafkaErrorCode.INVALID_PRODUCER_EPOCH.getCode());
        }
        if (transactionMetadata.isExpired()) {
            throw new TransactionException(KafkaErrorCode.INVALID_PRODUCER_EPOCH.getCode());
        }
        if (transactionMetadata.isPrepared()) {
            throw new TransactionException(KafkaErrorCode.CONCURRENT_TRANSACTIONS.getCode());
        }

        synchronized (transactionMetadata) {
            return doAddPartitionsToTxn(transactionMetadata, partitions);
        }
    }

    protected Map<String, List<PartitionMetadataAndError>> doAddPartitionsToTxn(TransactionMetadata transactionMetadata, Map<String, List<Integer>> partitions) {
        transactionMetadata.transitionStateTo(TransactionState.ONGOING);
        transactionMetadata.updateLastTime();

        Set<TransactionPrepare> prepareSet = Sets.newHashSet();

        Map<String, List<PartitionMetadataAndError>> result = Maps.newHashMapWithExpectedSize(partitions.size());
        for (Map.Entry<String, List<Integer>> entry : partitions.entrySet()) {
            List<PartitionMetadataAndError> partitionMetadataAndErrors = Lists.newArrayListWithCapacity(entry.getValue().size());
            result.put(entry.getKey(), partitionMetadataAndErrors);

            TopicName topic = TopicName.parse(entry.getKey());
            TopicConfig topicConfig = nameService.getTopicConfig(topic);

            for (Integer partition : entry.getValue()) {
                PartitionGroup partitionGroup = null;
                if (topicConfig != null) {
                    partitionGroup = topicConfig.fetchPartitionGroupByPartition((short) partition.intValue());
                }
                if (partitionGroup == null) {
                    partitionMetadataAndErrors.add(new PartitionMetadataAndError(partition, KafkaErrorCode.UNKNOWN_TOPIC_OR_PARTITION.getCode()));
                } else if (partitionGroup.getLeaderBroker() == null) {
                    partitionMetadataAndErrors.add(new PartitionMetadataAndError(partition, KafkaErrorCode.NOT_LEADER_FOR_PARTITION.getCode()));
                } else {
                    Broker broker = partitionGroup.getLeaderBroker();
                    TransactionPrepare prepare = new TransactionPrepare(topic.getFullName(), (short) partition.intValue(),
                            transactionMetadata.getApp(), broker.getId(), broker.getIp(), broker.getPort(),
                            transactionMetadata.getId(), transactionMetadata.getProducerId(), transactionMetadata.getProducerEpoch(),
                            transactionMetadata.getEpoch(), transactionMetadata.getTimeout(), SystemClock.now());
                    prepareSet.add(prepare);
                }
            }
        }

        if (CollectionUtils.isNotEmpty(prepareSet)) {
            try {
                transactionSynchronizer.prepare(transactionMetadata, prepareSet);
                transactionMetadata.addPrepare(prepareSet);

                for (TransactionPrepare transactionPrepare : prepareSet) {
                    result.get(transactionPrepare.getTopic()).add(new PartitionMetadataAndError(transactionPrepare.getPartition(), KafkaErrorCode.NONE.getCode()));
                }
            } catch (Exception e) {
                logger.error("transaction prepare exception, metadata:{}, prepare: {}", transactionMetadata, prepareSet, e);
                for (TransactionPrepare transactionPrepare : prepareSet) {
                    result.get(transactionPrepare.getTopic()).add(new PartitionMetadataAndError(transactionPrepare.getPartition(), KafkaErrorCode.COORDINATOR_NOT_AVAILABLE.getCode()));
                }
            }
        }

        return result;
    }

    public boolean endTxn(String clientId, String transactionId, long producerId, short producerEpoch, boolean isCommit) {
        checkCoordinatorState(clientId, transactionId);

        TransactionMetadata transactionMetadata = transactionMetadataManager.getTransaction(transactionId);
        if (transactionMetadata == null || transactionMetadata.getProducerId() != producerId || !StringUtils.equals(transactionMetadata.getApp(), clientId)) {
            throw new TransactionException(KafkaErrorCode.INVALID_PRODUCER_ID_MAPPING.getCode());
        }
        if (transactionMetadata.getProducerEpoch() != producerEpoch) {
            throw new TransactionException(KafkaErrorCode.INVALID_PRODUCER_EPOCH.getCode());
        }
        if (transactionMetadata.isExpired()) {
            throw new TransactionException(KafkaErrorCode.INVALID_PRODUCER_EPOCH.getCode());
        }
        if (transactionMetadata.isCompleted()) {
            throw new TransactionException(KafkaErrorCode.INVALID_PRODUCER_EPOCH.getCode());
        }

        synchronized (transactionMetadata) {
            return doEndTxn(transactionMetadata, isCommit);
        }
    }

    protected boolean doEndTxn(TransactionMetadata transactionMetadata, boolean isCommit) {
        try {
            if (isCommit) {
                doCommit(transactionMetadata);
            } else {
                doAbort(transactionMetadata);
            }

            transactionMetadata.clear();
            transactionMetadata.nextEpoch();
            return true;
        } catch (Exception e) {
            logger.error("endTxn exception, metadata: {}, isCommit: {}", transactionMetadata, isCommit, e);
            throw new TransactionException(e, KafkaErrorCode.COORDINATOR_NOT_AVAILABLE.getCode());
        }
    }

    protected void doCommit(TransactionMetadata transactionMetadata) throws Exception {
        if (!transactionMetadata.getState().equals(TransactionState.PREPARE_COMMIT)) {
            if (!transactionSynchronizer.prepareCommit(transactionMetadata, transactionMetadata.getPrepare())) {
                throw new JournalqException(String.format("prepare commit transaction failed, metadata: %s", transactionMetadata), JournalqCode.CN_UNKNOWN_ERROR.getCode());
            }
            transactionMetadata.transitionStateTo(TransactionState.PREPARE_COMMIT);
        }

        if (!transactionSynchronizer.commit(transactionMetadata, transactionMetadata.getPrepare(), transactionMetadata.getOffsets())) {
            throw new JournalqException(String.format("commit transaction failed, metadata: %s", transactionMetadata), JournalqCode.CN_UNKNOWN_ERROR.getCode());
        }
        transactionMetadata.transitionStateTo(TransactionState.COMPLETE_COMMIT);
    }

    protected void doAbort(TransactionMetadata transactionMetadata) throws Exception {
        if (!transactionMetadata.getState().equals(TransactionState.PREPARE_ABORT)) {
            if (!transactionSynchronizer.prepareAbort(transactionMetadata, transactionMetadata.getPrepare())) {
                throw new JournalqException(String.format("prepare abort transaction failed, metadata: %s", transactionMetadata), JournalqCode.CN_UNKNOWN_ERROR.getCode());
            }
            transactionMetadata.transitionStateTo(TransactionState.PREPARE_ABORT);
        }

        if (!transactionSynchronizer.abort(transactionMetadata, transactionMetadata.getPrepare())) {
            throw new JournalqException(String.format("abort transaction failed, metadata: %s", transactionMetadata), JournalqCode.CN_UNKNOWN_ERROR.getCode());
        }
        transactionMetadata.transitionStateTo(TransactionState.COMPLETE_ABORT);
    }

    protected void checkCoordinatorState(String clientId, String transactionId) {
        if (!isStarted()) {
            throw new TransactionException(KafkaErrorCode.COORDINATOR_NOT_AVAILABLE.getCode());
        }
        if (!coordinator.isCurrentTransaction(clientId)) {
            throw new TransactionException(KafkaErrorCode.NOT_COORDINATOR.getCode());
        }
    }
}