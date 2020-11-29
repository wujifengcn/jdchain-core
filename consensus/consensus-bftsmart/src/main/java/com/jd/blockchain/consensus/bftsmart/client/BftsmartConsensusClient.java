package com.jd.blockchain.consensus.bftsmart.client;

import com.jd.blockchain.consensus.MessageService;
import com.jd.blockchain.consensus.bftsmart.manage.BftsmartConsensusManageService;
import com.jd.blockchain.consensus.bftsmart.service.BftsmartClientAuthencationService;
import com.jd.blockchain.consensus.client.ClientSettings;
import com.jd.blockchain.consensus.client.ConsensusClient;
import com.jd.blockchain.consensus.manage.ConsensusManageClient;
import com.jd.blockchain.consensus.manage.ConsensusManageService;

public class BftsmartConsensusClient implements ConsensusClient, ConsensusManageClient {


    private BftsmartServiceProxyPool serviceProxyPool;

    private int gatewayId;

    private ClientSettings clientSettings;

    public BftsmartConsensusClient(ClientSettings clientSettings) {
        this.clientSettings = clientSettings;
        this.gatewayId = clientSettings.getClientId();
    }

    @Override
    public MessageService getMessageService() {
    	return new DynamicBftsmartMessageService();
    }

	@Override
	public ConsensusManageService getManageService() {
		return new DynamicBftsmartConsensusManageService();
	}

    @Override
    public ClientSettings getSettings() {
        return clientSettings;
    }

    @Override
    public boolean isConnected() {
        return this.serviceProxyPool != null;
    }

    @Override
    public synchronized void connect() {
        //consensus client pool
        BftsmartPeerProxyFactory peerProxyFactory = new BftsmartPeerProxyFactory((BftsmartClientSettings)clientSettings, gatewayId);
        this.serviceProxyPool = new BftsmartServiceProxyPool(peerProxyFactory);
        this.serviceProxyPool.setMaxTotal(BftsmartClientAuthencationService.POOL_SIZE_PEER_CLIENT);
    }

    @Override
    public void close() {
    	BftsmartServiceProxyPool serviceProxyPool = this.serviceProxyPool;
    	this.serviceProxyPool = null;
        if (serviceProxyPool != null) {
            serviceProxyPool.close();
        }
    }
    
    
    private BftsmartServiceProxyPool ensureConnected() {
    	BftsmartServiceProxyPool serviceProxyPool = this.serviceProxyPool;
    	if (serviceProxyPool == null) {
			throw new IllegalStateException("Client has not conneted to the node servers!");
		}
    	return serviceProxyPool;
    }
    
    private class DynamicBftsmartMessageService extends BftsmartMessageService{

		@Override
		protected BftsmartServiceProxyPool getServiceProxyPool() {
			return ensureConnected();
		}
    	
    }
    
    private class DynamicBftsmartConsensusManageService extends BftsmartConsensusManageService{
    	
    	@Override
    	protected BftsmartServiceProxyPool getServiceProxyPool() {
    		return ensureConnected();
    	}
    	
    }
}
