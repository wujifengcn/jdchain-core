package com.jd.blockchain.consensus.mq.server;

import com.jd.blockchain.consensus.service.ConsensusMessageContext;

public class MsgQueueConsensusMessageContext implements ConsensusMessageContext {

    private String realmName;
    private String batchId;
    private Long timestamp;

    private MsgQueueConsensusMessageContext(String realmName) {
        this.realmName = realmName;
    }

    @Override
    public String getBatchId() {
        return this.batchId;
    }

    @Override
    public String getRealmName() {
        return this.realmName;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    public static MsgQueueConsensusMessageContext createInstance(String realmName) {
        return new MsgQueueConsensusMessageContext(realmName);
    }
}
