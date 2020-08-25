package com.jd.blockchain.ledger.proof;

import com.jd.blockchain.binaryproto.DataContract;
import com.jd.blockchain.binaryproto.DataField;
import com.jd.blockchain.binaryproto.NumberEncoding;
import com.jd.blockchain.binaryproto.PrimitiveType;
import com.jd.blockchain.consts.DataCodes;
import com.jd.blockchain.crypto.HashDigest;
import com.jd.blockchain.utils.Bytes;

/**
 * Merk
 * 
 * @author huanghaiquan
 *
 */
@DataContract(code = DataCodes.MERKLE_TRIE_DATA)
public interface MerkleTrieData extends MerkleTrieEntry, MerkleDataEntry{

	/**
	 * 键；
	 */
	@DataField(order = 1, primitiveType = PrimitiveType.BYTES)
	Bytes getKey();

	/**
	 * 键的版本；
	 */
	@DataField(order = 2, primitiveType = PrimitiveType.INT64, numberEncoding = NumberEncoding.LONG)
	long getVersion();

	/**
	 * 值的哈希；
	 */
	@DataField(order = 3, primitiveType = PrimitiveType.BYTES)
	Bytes getValue();
	
	/**
	 * 前一版本的数据节点哈希；
	 */
	@DataField(order = 4, primitiveType = PrimitiveType.BYTES)
	HashDigest getPreviousEntryHash();

}