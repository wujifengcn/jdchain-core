package com.jd.blockchain.gateway.web;

import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import com.jd.blockchain.contract.ContractProcessor;
import com.jd.blockchain.contract.OnLineContractProcessor;
import com.jd.blockchain.gateway.exception.BlockNonExistentException;
import com.jd.blockchain.gateway.service.PeerConnectionManager;
import com.jd.blockchain.gateway.service.settings.LedgerBaseSettings;
import com.jd.blockchain.ledger.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jd.blockchain.crypto.AddressEncoding;
import com.jd.blockchain.crypto.HashDigest;
import com.jd.blockchain.crypto.KeyGenUtils;
import com.jd.blockchain.crypto.PubKey;
import com.jd.blockchain.gateway.service.DataRetrievalService;
import com.jd.blockchain.gateway.service.GatewayQueryService;
import com.jd.blockchain.sdk.BlockchainExtendQueryService;
import com.jd.blockchain.sdk.ContractSettings;
import com.jd.blockchain.utils.BaseConstant;
import com.jd.blockchain.utils.ConsoleUtils;

@RestController
@RequestMapping(path = "/")
public class BlockBrowserController implements BlockchainExtendQueryService {

	private static final ContractProcessor CONTRACT_PROCESSOR = OnLineContractProcessor.getInstance();

	private final Logger logger = LoggerFactory.getLogger(getClass());

	@Autowired
	private PeerConnectionManager peerService;

	@Autowired
	private GatewayQueryService gatewayQueryService;

	@Autowired
	private DataRetrievalService dataRetrievalService;

	private String dataRetrievalUrl;
	private String schemaRetrievalUrl;

	private static final long BLOCK_MAX_DISPLAY = 3L;

	private static final long GENESIS_BLOCK_HEIGHT = 0L;

	@RequestMapping(method = RequestMethod.GET, path = "ledgers")
	@Override
	public HashDigest[] getLedgerHashs() {
		return peerService.getQueryService().getLedgerHashs();
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}")
	@Override
	public LedgerInfo getLedger(@PathVariable(name = "ledgerHash") HashDigest ledgerHash) {
		return peerService.getQueryService().getLedger(ledgerHash);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/admininfo")
	@Override
	public LedgerAdminInfo getLedgerAdminInfo(@PathVariable(name = "ledgerHash") HashDigest ledgerHash) {
		return peerService.getQueryService().getLedgerAdminInfo(ledgerHash);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/participants")
	@Override
	public ParticipantNode[] getConsensusParticipants(@PathVariable(name = "ledgerHash") HashDigest ledgerHash) {
		return peerService.getQueryService().getConsensusParticipants(ledgerHash);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/metadata")
	@Override
	public LedgerMetadata getLedgerMetadata(@PathVariable(name = "ledgerHash") HashDigest ledgerHash) {
		return peerService.getQueryService().getLedgerMetadata(ledgerHash);
	}

    @RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/settings")
    public LedgerBaseSettings getLedgerInitSettings(@PathVariable(name = "ledgerHash") HashDigest ledgerHash) {
	    return gatewayQueryService.getLedgerBaseSettings(ledgerHash);
    }

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/blocks")
	public LedgerBlock[] getBlocks(@PathVariable(name = "ledgerHash") HashDigest ledgerHash) {
		LedgerInfo ledgerInfo = peerService.getQueryService().getLedger(ledgerHash);
		long maxBlockHeight = ledgerInfo.getLatestBlockHeight();
		List<LedgerBlock> ledgerBlocks = new ArrayList<>();
		for (long blockHeight = maxBlockHeight; blockHeight > GENESIS_BLOCK_HEIGHT; blockHeight--) {
			LedgerBlock ledgerBlock = peerService.getQueryService().getBlock(ledgerHash, blockHeight);
			ledgerBlocks.add(0, ledgerBlock);
			if (ledgerBlocks.size() == BLOCK_MAX_DISPLAY) {
				break;
			}
		}
		// 最后增加创世区块
		LedgerBlock genesisBlock = peerService.getQueryService().getBlock(ledgerHash, GENESIS_BLOCK_HEIGHT);
		ledgerBlocks.add(0, genesisBlock);
		LedgerBlock[] blocks = new LedgerBlock[ledgerBlocks.size()];
		ledgerBlocks.toArray(blocks);
		return blocks;
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/blocks/height/{blockHeight}")
	@Override
	public LedgerBlock getBlock(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@PathVariable(name = "blockHeight") long blockHeight) {
		// 获取最新区块高度
		LedgerBlock latestBlock = getLatestBlock(ledgerHash);
		if (blockHeight > latestBlock.getHeight()) {
			throw new BlockNonExistentException(String.format("Ledger[%s] -> height[%s] is non-existent !!!",
					ledgerHash.toBase58(), blockHeight));
		} else if (blockHeight == latestBlock.getHeight()) {
			return latestBlock;
		} else {
			return peerService.getQueryService().getBlock(ledgerHash, blockHeight);
		}
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/blocks/hash/{blockHash}")
	@Override
	public LedgerBlock getBlock(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@PathVariable(name = "blockHash") HashDigest blockHash) {
		return peerService.getQueryService().getBlock(ledgerHash, blockHash);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/blocks/height/{blockHeight}/txs/count")
	@Override
	public long getTransactionCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@PathVariable(name = "blockHeight") long blockHeight) {
		return peerService.getQueryService().getTransactionCount(ledgerHash, blockHeight);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/blocks/hash/{blockHash}/txs/count")
	@Override
	public long getTransactionCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@PathVariable(name = "blockHash") HashDigest blockHash) {
		return peerService.getQueryService().getTransactionCount(ledgerHash, blockHash);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/txs/count")
	@Override
	public long getTransactionTotalCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash) {
		return peerService.getQueryService().getTransactionTotalCount(ledgerHash);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/blocks/height/{blockHeight}/accounts/count")
	@Override
	public long getDataAccountCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@PathVariable(name = "blockHeight") long blockHeight) {
		return peerService.getQueryService().getDataAccountCount(ledgerHash, blockHeight);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/blocks/hash/{blockHash}/accounts/count")
	@Override
	public long getDataAccountCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@PathVariable(name = "blockHash") HashDigest blockHash) {
		return peerService.getQueryService().getDataAccountCount(ledgerHash, blockHash);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/accounts/count")
	@Override
	public long getDataAccountTotalCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash) {
		return peerService.getQueryService().getDataAccountTotalCount(ledgerHash);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/blocks/height/{blockHeight}/users/count")
	@Override
	public long getUserCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@PathVariable(name = "blockHeight") long blockHeight) {
		return peerService.getQueryService().getUserCount(ledgerHash, blockHeight);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/blocks/hash/{blockHash}/users/count")
	@Override
	public long getUserCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@PathVariable(name = "blockHash") HashDigest blockHash) {
		return peerService.getQueryService().getUserCount(ledgerHash, blockHash);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/users/count")
	@Override
	public long getUserTotalCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash) {
		return peerService.getQueryService().getUserTotalCount(ledgerHash);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/blocks/height/{blockHeight}/contracts/count")
	@Override
	public long getContractCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@PathVariable(name = "blockHeight") long blockHeight) {
		return peerService.getQueryService().getContractCount(ledgerHash, blockHeight);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/blocks/hash/{blockHash}/contracts/count")
	@Override
	public long getContractCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@PathVariable(name = "blockHash") HashDigest blockHash) {
		return peerService.getQueryService().getContractCount(ledgerHash, blockHash);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/contracts/count")
	@Override
	public long getContractTotalCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash) {
		return peerService.getQueryService().getContractTotalCount(ledgerHash);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/blocks/height/{blockHeight}/txs")
	@Override
	public LedgerTransaction[] getTransactions(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@PathVariable(name = "blockHeight") long blockHeight,
			@RequestParam(name = "fromIndex", required = false, defaultValue = "0") int fromIndex,
			@RequestParam(name = "count", required = false, defaultValue = "-1") int count) {
		return peerService.getQueryService().getTransactions(ledgerHash, blockHeight, fromIndex, count);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/blocks/hash/{blockHash}/txs")
	@Override
	public LedgerTransaction[] getTransactions(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@PathVariable(name = "blockHash") HashDigest blockHash,
			@RequestParam(name = "fromIndex", required = false, defaultValue = "0") int fromIndex,
			@RequestParam(name = "count", required = false, defaultValue = "-1") int count) {
		return peerService.getQueryService().getTransactions(ledgerHash, blockHash, fromIndex, count);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/txs/hash/{contentHash}")
	@Override
	public LedgerTransaction getTransactionByContentHash(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@PathVariable(name = "contentHash") HashDigest contentHash) {
		return peerService.getQueryService().getTransactionByContentHash(ledgerHash, contentHash);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/txs/state/{contentHash}")
	@Override
	public TransactionState getTransactionStateByContentHash(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@PathVariable(name = "contentHash") HashDigest contentHash) {
		return peerService.getQueryService().getTransactionStateByContentHash(ledgerHash, contentHash);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/users/address/{address}")
	@Override
	public UserInfo getUser(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@PathVariable(name = "address") String address) {
		return peerService.getQueryService().getUser(ledgerHash, address);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/accounts/address/{address}")
	@Override
	public BlockchainIdentity getDataAccount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@PathVariable(name = "address") String address) {

		return peerService.getQueryService().getDataAccount(ledgerHash, address);
	}

	@RequestMapping(method = { RequestMethod.GET,
			RequestMethod.POST }, path = "ledgers/{ledgerHash}/accounts/{address}/entries")
	@Override
	public TypedKVEntry[] getDataEntries(@PathVariable("ledgerHash") HashDigest ledgerHash,
			@PathVariable("address") String address, @RequestParam("keys") String... keys) {
		return peerService.getQueryService().getDataEntries(ledgerHash, address, keys);
	}

	@RequestMapping(method = { RequestMethod.GET,
			RequestMethod.POST }, path = "ledgers/{ledgerHash}/accounts/{address}/entries-version")
	@Override
	public TypedKVEntry[] getDataEntries(@PathVariable("ledgerHash") HashDigest ledgerHash,
			@PathVariable("address") String address, @RequestBody KVInfoVO kvInfoVO) {
		return peerService.getQueryService().getDataEntries(ledgerHash, address, kvInfoVO);
	}

	@RequestMapping(method = { RequestMethod.GET,
			RequestMethod.POST }, path = "ledgers/{ledgerHash}/accounts/address/{address}/entries")
	@Override
	public TypedKVEntry[] getDataEntries(@PathVariable("ledgerHash") HashDigest ledgerHash,
			@PathVariable("address") String address,
			@RequestParam(name = "fromIndex", required = false, defaultValue = "0") int fromIndex,
			@RequestParam(name = "count", required = false, defaultValue = "-1") int count) {
		return peerService.getQueryService().getDataEntries(ledgerHash, address, fromIndex, count);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/accounts/address/{address}/entries/count")
	@Override
	public long getDataEntriesTotalCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@PathVariable(name = "address") String address) {
		return peerService.getQueryService().getDataEntriesTotalCount(ledgerHash, address);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/contracts/address/{address}")
	public ContractSettings getContractSettings(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@PathVariable(name = "address") String address) {
		ContractInfo contractInfo = peerService.getQueryService().getContract(ledgerHash, address);
		return contractSettings(contractInfo);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/contracts/address/{address}/version/{version}")
	public ContractSettings getContractSettingsByVersion(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
														 @PathVariable(name = "address") String address, @PathVariable(name = "version") long version) {
		ContractInfo contractInfo = peerService.getQueryService().getContract(ledgerHash, address, version);
		return contractSettings(contractInfo);
	}


	private ContractSettings contractSettings(ContractInfo contractInfo) {
		ContractSettings contractSettings = new ContractSettings(contractInfo.getAddress(), contractInfo.getPubKey(),
				contractInfo.getRootHash());
		byte[] chainCodeBytes = contractInfo.getChainCode();
		try {
			// 将反编译chainCode
			String mainClassJava = CONTRACT_PROCESSOR.decompileEntranceClass(chainCodeBytes);
			contractSettings.setChainCode(mainClassJava);
			contractSettings.setChainCodeVersion(contractInfo.getChainCodeVersion());
		} catch (Exception e) {
			// 打印日志
			logger.error(String.format("Decompile contract[%s] error !!!",
					contractInfo.getAddress().toBase58()), e);
		}
		return contractSettings;
	}

//    @RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/contracts/address/{address}")
	@Override
	public ContractInfo getContract(HashDigest ledgerHash, String address) {
		return peerService.getQueryService().getContract(ledgerHash, address);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/events/system/names/{eventName}")
	@Override
	public Event[] getSystemEvents(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
								   @PathVariable(name = "eventName") String eventName,
								   @RequestParam(name = "fromSequence", required = false, defaultValue = "0") long fromSequence,
								   @RequestParam(name = "count", required = false, defaultValue = "-1") int count) {
		return peerService.getEventListener().getSystemEvents(ledgerHash, eventName, fromSequence, count);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/events/system/names/count")
	@Override
	public long getSystemEventNameTotalCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash) {
		return peerService.getQueryService().getSystemEventNameTotalCount(ledgerHash);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/events/system/names")
	@Override
	public String[] getSystemEventNames(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
										@RequestParam(name = "fromIndex", required = false, defaultValue = "0") int fromIndex,
										@RequestParam(name = "maxCount", required = false, defaultValue = "-1") int count) {
		return peerService.getQueryService().getSystemEventNames(ledgerHash, fromIndex, count);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/events/system/names/{eventName}/latest")
	@Override
	public Event getLatestEvent(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
								@PathVariable(name = "eventName") String eventName) {
		return peerService.getQueryService().getLatestEvent(ledgerHash, eventName);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/events/system/names/{eventName}/count")
	@Override
	public long getSystemEventsTotalCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
										  @PathVariable(name = "eventName") String eventName) {
		return peerService.getQueryService().getSystemEventsTotalCount(ledgerHash, eventName);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/events/user/accounts")
	@Override
	public BlockchainIdentity[] getUserEventAccounts(@PathVariable(name = "ledgerHash")  HashDigest ledgerHash,
													 @RequestParam(name = "fromIndex", required = false, defaultValue = "0") int fromIndex,
													 @RequestParam(name = "count", required = false, defaultValue = "-1") int count) {
		return peerService.getQueryService().getUserEventAccounts(ledgerHash, fromIndex, count);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/events/user/accounts/{address}")
	@Override
	public BlockchainIdentity getUserEventAccount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
												  @PathVariable(name = "address") String address) {
		return peerService.getQueryService().getUserEventAccount(ledgerHash, address);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/events/user/accounts/count")
	@Override
	public long getUserEventAccountTotalCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash) {
		return peerService.getQueryService().getUserEventAccountTotalCount(ledgerHash);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/events/user/accounts/{address}/names/count")
	@Override
	public long getUserEventNameTotalCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
										   @PathVariable(name = "address") String address) {
		return peerService.getQueryService().getUserEventNameTotalCount(ledgerHash, address);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/events/user/accounts/{address}/names")
	@Override
	public String[] getUserEventNames(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
									  @PathVariable(name = "address") String address,
									 @RequestParam(name = "fromIndex", required = false, defaultValue = "0") int fromIndex,
									 @RequestParam(name = "count", required = false, defaultValue = "-1") int count) {
		return peerService.getQueryService().getUserEventNames(ledgerHash, address, fromIndex, count);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/events/user/accounts/{address}/names/{eventName}/latest")
	@Override
	public Event getLatestEvent(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
								@PathVariable(name = "address") String address,
								@PathVariable(name = "eventName") String eventName) {
		return peerService.getQueryService().getLatestEvent(ledgerHash, address, eventName);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/events/user/accounts/{address}/names/{eventName}/count")
	@Override
	public long getUserEventsTotalCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
										@PathVariable(name = "address") String address,
										@PathVariable(name = "eventName") String eventName) {
		return peerService.getQueryService().getUserEventsTotalCount(ledgerHash, address, eventName);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/events/user/accounts/{address}/names/{eventName}")
	@Override
	public Event[] getUserEvents(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
								 @PathVariable(name = "address") String address,
								 @PathVariable(name = "eventName") String eventName,
								 @RequestParam(name = "fromSequence", required = false, defaultValue = "0") long fromSequence,
								 @RequestParam(name = "count", required = false, defaultValue = "-1") int count) {
		return peerService.getEventListener().getUserEvents(ledgerHash, address, eventName, fromSequence, count);
	}

	@Override
	public ContractInfo getContract(HashDigest ledgerHash, String address, long version) {
		return peerService.getQueryService().getContract(ledgerHash, address, version);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/blocks/latest")
	@Override
	public LedgerBlock getLatestBlock(@PathVariable(name = "ledgerHash") HashDigest ledgerHash) {
		long latestBlockHeight = peerService.getQueryService().getLedger(ledgerHash).getLatestBlockHeight();
		return peerService.getQueryService().getBlock(ledgerHash, latestBlockHeight);
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/blocks/height/{blockHeight}/txs/additional-count")
	@Override
	public long getAdditionalTransactionCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@PathVariable(name = "blockHeight") long blockHeight) {
		// 获取某个区块的交易总数
		long currentBlockTxCount = peerService.getQueryService().getTransactionCount(ledgerHash, blockHeight);
		if (blockHeight == GENESIS_BLOCK_HEIGHT) {
			return currentBlockTxCount;
		}
		long lastBlockHeight = blockHeight - 1;
		long lastBlockTxCount = peerService.getQueryService().getTransactionCount(ledgerHash, lastBlockHeight);
		// 当前区块交易数减上个区块交易数
		return currentBlockTxCount - lastBlockTxCount;
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/blocks/hash/{blockHash}/txs/additional-count")
	@Override
	public long getAdditionalTransactionCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@PathVariable(name = "blockHash") HashDigest blockHash) {
		LedgerBlock currentBlock = peerService.getQueryService().getBlock(ledgerHash, blockHash);
		long currentBlockTxCount = peerService.getQueryService().getTransactionCount(ledgerHash, blockHash);
		if (currentBlock.getHeight() == GENESIS_BLOCK_HEIGHT) {
			return currentBlockTxCount;
		}
		HashDigest previousHash = currentBlock.getPreviousHash();
		long lastBlockTxCount = peerService.getQueryService().getTransactionCount(ledgerHash, previousHash);
		// 当前区块交易数减上个区块交易数
		return currentBlockTxCount - lastBlockTxCount;
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/txs/additional-count")
	@Override
	public long getAdditionalTransactionCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash) {
		LedgerInfo ledgerInfo = peerService.getQueryService().getLedger(ledgerHash);
		long maxBlockHeight = ledgerInfo.getLatestBlockHeight();
		long totalCount = peerService.getQueryService().getTransactionTotalCount(ledgerHash);
		if (maxBlockHeight == GENESIS_BLOCK_HEIGHT) { // 只有一个创世区块
			return totalCount;
		}
		long lastTotalCount = peerService.getQueryService().getTransactionCount(ledgerHash, maxBlockHeight - 1);
		return totalCount - lastTotalCount;
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/blocks/height/{blockHeight}/accounts/additional-count")
	@Override
	public long getAdditionalDataAccountCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@PathVariable(name = "blockHeight") long blockHeight) {
		long currentDaCount = peerService.getQueryService().getDataAccountCount(ledgerHash, blockHeight);
		if (blockHeight == GENESIS_BLOCK_HEIGHT) {
			return currentDaCount;
		}
		long lastBlockHeight = blockHeight - 1;
		long lastDaCount = peerService.getQueryService().getDataAccountCount(ledgerHash, lastBlockHeight);
		return currentDaCount - lastDaCount;
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/blocks/hash/{blockHash}/accounts/additional-count")
	@Override
	public long getAdditionalDataAccountCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@PathVariable(name = "blockHash") HashDigest blockHash) {
		LedgerBlock currentBlock = peerService.getQueryService().getBlock(ledgerHash, blockHash);
		long currentBlockDaCount = peerService.getQueryService().getDataAccountCount(ledgerHash, blockHash);
		if (currentBlock.getHeight() == GENESIS_BLOCK_HEIGHT) {
			return currentBlockDaCount;
		}
		HashDigest previousHash = currentBlock.getPreviousHash();
		long lastBlockDaCount = peerService.getQueryService().getDataAccountCount(ledgerHash, previousHash);
		// 当前区块数据账户数量减上个区块数据账户数量
		return currentBlockDaCount - lastBlockDaCount;
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/accounts/additional-count")
	@Override
	public long getAdditionalDataAccountCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash) {
		LedgerInfo ledgerInfo = peerService.getQueryService().getLedger(ledgerHash);
		long maxBlockHeight = ledgerInfo.getLatestBlockHeight();
		long totalCount = peerService.getQueryService().getDataAccountTotalCount(ledgerHash);
		if (maxBlockHeight == GENESIS_BLOCK_HEIGHT) { // 只有一个创世区块
			return totalCount;
		}
		long lastTotalCount = peerService.getQueryService().getDataAccountCount(ledgerHash, maxBlockHeight - 1);
		return totalCount - lastTotalCount;
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/blocks/height/{blockHeight}/users/additional-count")
	@Override
	public long getAdditionalUserCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@PathVariable(name = "blockHeight") long blockHeight) {
		long currentUserCount = peerService.getQueryService().getUserCount(ledgerHash, blockHeight);
		if (blockHeight == GENESIS_BLOCK_HEIGHT) {
			return currentUserCount;
		}
		long lastBlockHeight = blockHeight - 1;
		long lastUserCount = peerService.getQueryService().getUserCount(ledgerHash, lastBlockHeight);
		return currentUserCount - lastUserCount;
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/blocks/hash/{blockHash}/users/additional-count")
	@Override
	public long getAdditionalUserCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@PathVariable(name = "blockHash") HashDigest blockHash) {
		LedgerBlock currentBlock = peerService.getQueryService().getBlock(ledgerHash, blockHash);
		long currentBlockUserCount = peerService.getQueryService().getUserCount(ledgerHash, blockHash);
		if (currentBlock.getHeight() == GENESIS_BLOCK_HEIGHT) {
			return currentBlockUserCount;
		}
		HashDigest previousHash = currentBlock.getPreviousHash();
		long lastBlockUserCount = peerService.getQueryService().getUserCount(ledgerHash, previousHash);
		// 当前区块用户数量减上个区块用户数量
		return currentBlockUserCount - lastBlockUserCount;
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/users/additional-count")
	@Override
	public long getAdditionalUserCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash) {
		LedgerInfo ledgerInfo = peerService.getQueryService().getLedger(ledgerHash);
		long maxBlockHeight = ledgerInfo.getLatestBlockHeight();
		long totalCount = peerService.getQueryService().getUserTotalCount(ledgerHash);
		if (maxBlockHeight == GENESIS_BLOCK_HEIGHT) { // 只有一个创世区块
			return totalCount;
		}
		long lastTotalCount = peerService.getQueryService().getUserCount(ledgerHash, maxBlockHeight - 1);
		return totalCount - lastTotalCount;
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/blocks/height/{blockHeight}/contracts/additional-count")
	@Override
	public long getAdditionalContractCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@PathVariable(name = "blockHeight") long blockHeight) {
		long currentContractCount = peerService.getQueryService().getContractCount(ledgerHash, blockHeight);
		if (blockHeight == GENESIS_BLOCK_HEIGHT) {
			return currentContractCount;
		}
		long lastBlockHeight = blockHeight - 1;
		long lastContractCount = peerService.getQueryService().getContractCount(ledgerHash, lastBlockHeight);
		return currentContractCount - lastContractCount;
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/blocks/hash/{blockHash}/contracts/additional-count")
	@Override
	public long getAdditionalContractCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@PathVariable(name = "blockHash") HashDigest blockHash) {
		LedgerBlock currentBlock = peerService.getQueryService().getBlock(ledgerHash, blockHash);
		long currentBlockContractCount = peerService.getQueryService().getContractCount(ledgerHash, blockHash);
		if (currentBlock.getHeight() == GENESIS_BLOCK_HEIGHT) {
			return currentBlockContractCount;
		}
		HashDigest previousHash = currentBlock.getPreviousHash();
		long lastBlockContractCount = peerService.getQueryService().getContractCount(ledgerHash, previousHash);
		// 当前区块合约数量减上个区块合约数量
		return currentBlockContractCount - lastBlockContractCount;
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/contracts/additional-count")
	@Override
	public long getAdditionalContractCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash) {
		LedgerInfo ledgerInfo = peerService.getQueryService().getLedger(ledgerHash);
		long maxBlockHeight = ledgerInfo.getLatestBlockHeight();
		long totalCount = peerService.getQueryService().getContractTotalCount(ledgerHash);
		if (maxBlockHeight == GENESIS_BLOCK_HEIGHT) { // 只有一个创世区块
			return totalCount;
		}
		long lastTotalCount = peerService.getQueryService().getContractCount(ledgerHash, maxBlockHeight - 1);
		return totalCount - lastTotalCount;
	}

	@RequestMapping(method = RequestMethod.GET, path = "utils/pubkey/{pubkey}/addr")
	public String getAddrByPubKey(@PathVariable(name = "pubkey") String strPubKey) {
		PubKey pubKey = KeyGenUtils.decodePubKey(strPubKey);
		return AddressEncoding.generateAddress(pubKey).toBase58();
	}

	@RequestMapping(method = RequestMethod.GET, value = "ledgers/{ledgerHash}/**/search")
	public Object dataRetrieval(@PathVariable(name = "ledgerHash") HashDigest ledgerHash, HttpServletRequest request) {
		String result;
		if (dataRetrievalUrl == null || dataRetrievalUrl.length() <= 0) {
			result = "{'message':'OK','data':'" + "data.retrieval.url is empty" + "'}";
		} else {
			String queryParams = request.getQueryString() == null ? "" : request.getQueryString();
			String fullQueryUrl = new StringBuffer(dataRetrievalUrl).append(request.getRequestURI())
					.append(BaseConstant.DELIMETER_QUESTION).append(queryParams).toString();
			try {
				result = dataRetrievalService.retrieval(fullQueryUrl);
				ConsoleUtils.info("request = {%s} \r\n result = {%s} \r\n", fullQueryUrl, result);
			} catch (Exception e) {
				result = "{'message':'OK','data':'" + e.getMessage() + "'}";
			}
		}
		return result;
	}

	/**
	 * querysql;
	 * @param request
	 * @return
	 */
	@RequestMapping(method = RequestMethod.POST, value = "schema/querysql")
	public Object queryBySql(HttpServletRequest request,@RequestBody String queryString) {
		String result;
		if (schemaRetrievalUrl == null ||  schemaRetrievalUrl.length() <= 0) {
			result = "{'message':'OK','data':'" + "schema.retrieval.url is empty" + "'}";
		} else {
			String queryParams = request.getQueryString() == null ? "": request.getQueryString();
			String fullQueryUrl = new StringBuffer(schemaRetrievalUrl)
					.append(request.getRequestURI())
					.append(BaseConstant.DELIMETER_QUESTION)
					.append(queryParams)
					.toString();
			try {
				result = dataRetrievalService.retrievalPost(fullQueryUrl,queryString);
				ConsoleUtils.info("request = {%s} \r\n result = {%s} \r\n", fullQueryUrl, result);
			} catch (Exception e) {
				result = "{'message':'error','data':'" + e.getMessage() + "'}";
			}
		}
		return result;
	}

	public void setSchemaRetrievalUrl(String schemaRetrievalUrl) {
		this.schemaRetrievalUrl = schemaRetrievalUrl;
	}

	public void setDataRetrievalUrl(String dataRetrievalUrl) {
		this.dataRetrievalUrl = dataRetrievalUrl;
	}

	/**
	 * get all ledgers count;
	 */
	@RequestMapping(method = RequestMethod.GET, path = "ledgers/count")
	@Override
	public int getLedgersCount() {
		return peerService.getQueryService().getLedgerHashs().length;
	}

	// 注： 账本的数量不会很多，不需要分页；
//	/**
//	 * get all ledgers hashs;
//	 */
//	@RequestMapping(method = RequestMethod.GET, path = "ledgers")
//	public HashDigest[] getLedgersHash(
//			@RequestParam(name = "fromIndex", required = false, defaultValue = "0") int fromIndex,
//			@RequestParam(name = "count", required = false, defaultValue = "-1") int count) {
//		return gatewayQueryService.getLedgersHash(fromIndex, count);
//	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/participants/count")
	public int getConsensusParticipantCount(@PathVariable(name = "ledgerHash") HashDigest ledgerHash) {
		return peerService.getQueryService().getConsensusParticipants(ledgerHash).length;
	}

//	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/participants")
//	public ParticipantNode[] getConsensusParticipants(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
//			@RequestParam(name = "fromIndex", required = false, defaultValue = "0") int fromIndex,
//			@RequestParam(name = "count", required = false, defaultValue = "-1") int count) {
//
//		ParticipantNode participantNode[] = peerService.getQueryService().getConsensusParticipants(ledgerHash);
//		int indexAndCount[] = QueryUtil.calFromIndexAndCount(fromIndex, count, participantNode.length);
//		ParticipantNode participantNodesNew[] = Arrays.copyOfRange(participantNode, indexAndCount[0],
//				indexAndCount[0] + indexAndCount[1]);
//		return participantNodesNew;
//	}

	/**
	 * get more users by fromIndex and count;
	 *
	 * @param ledgerHash
	 * @param fromIndex
	 * @param count
	 * @return
	 */
	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/users")
	@Override
	public BlockchainIdentity[] getUsers(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@RequestParam(name = "fromIndex", required = false, defaultValue = "0") int fromIndex,
			@RequestParam(name = "count", required = false, defaultValue = "-1") int count) {
		return revertAccountHeader(peerService.getQueryService().getUsers(ledgerHash, fromIndex, count));
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/accounts")
	@Override
	public BlockchainIdentity[] getDataAccounts(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@RequestParam(name = "fromIndex", required = false, defaultValue = "0") int fromIndex,
			@RequestParam(name = "count", required = false, defaultValue = "-1") int count) {
		return revertAccountHeader(peerService.getQueryService().getDataAccounts(ledgerHash, fromIndex, count));
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/contracts")
	@Override
	public BlockchainIdentity[] getContractAccounts(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
			@RequestParam(name = "fromIndex", required = false, defaultValue = "0") int fromIndex,
			@RequestParam(name = "count", required = false, defaultValue = "-1") int count) {
		return revertAccountHeader(peerService.getQueryService().getContractAccounts(ledgerHash, fromIndex, count));
	}

	/**
	 * reverse the BlockchainIdentity[] content; the latest record show first;
	 * @return
	 */
	private BlockchainIdentity[] revertAccountHeader(BlockchainIdentity[] accountHeaders){
		BlockchainIdentity[] accounts = new BlockchainIdentity[accountHeaders.length];
		if(accountHeaders!=null && accountHeaders.length>0){
			for (int i = 0; i < accountHeaders.length; i++) {
				accounts[accountHeaders.length-1-i] = accountHeaders[i];
			}
		}
		return accounts;
	}

	@RequestMapping(method = RequestMethod.GET, path = "ledgers/{ledgerHash}/userrole/{userAddress}")
	@Override
	public RoleSet getUserRoles(@PathVariable(name = "ledgerHash") HashDigest ledgerHash,
								@PathVariable(name = "userAddress") String userAddress) {
		return peerService.getQueryService().getUserRoles(ledgerHash, userAddress);
	}
}
