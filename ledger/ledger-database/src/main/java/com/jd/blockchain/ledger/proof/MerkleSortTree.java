package com.jd.blockchain.ledger.proof;

import java.util.ArrayList;
import java.util.List;

import com.jd.blockchain.binaryproto.BinaryProtocol;
import com.jd.blockchain.binaryproto.DataContract;
import com.jd.blockchain.binaryproto.DataField;
import com.jd.blockchain.binaryproto.NumberEncoding;
import com.jd.blockchain.binaryproto.PrimitiveType;
import com.jd.blockchain.consts.DataCodes;
import com.jd.blockchain.crypto.Crypto;
import com.jd.blockchain.crypto.HashDigest;
import com.jd.blockchain.crypto.HashFunction;
import com.jd.blockchain.ledger.MerkleProof;
import com.jd.blockchain.ledger.core.HashPathProof;
import com.jd.blockchain.ledger.core.MerkleProofException;
import com.jd.blockchain.storage.service.ExPolicy;
import com.jd.blockchain.storage.service.ExPolicyKVStorage;
import com.jd.blockchain.utils.AbstractSkippingIterator;
import com.jd.blockchain.utils.ArrayUtils;
import com.jd.blockchain.utils.Bytes;
import com.jd.blockchain.utils.MathUtils;
import com.jd.blockchain.utils.SkippingIterator;
import com.jd.blockchain.utils.Transactional;

/**
 * 默克尔排序树；
 * <p>
 * 树的level是按照倒置的方式计算，而不是以根节点的距离衡量，即叶子节点的 level 是 0； <br>
 * 所有的数据的哈希索引都以叶子节点进行记录; <br>
 * 每一个数据节点都以标记一个序列号（Sequence Number, 缩写为 SN），并按照序列号的大小统一地在 level 0
 * 上排列，并填充从根节点到数据节点的所有路径节点； <br>
 * 随着数据节点的增加，整棵树以倒置方式向上增长（根节点在上，叶子节点在下），此设计带来显著特性是已有节点的信息都可以不必修改；
 * 
 * <p>
 * <strong>注：此实现不是线程安全的；</strong><br>
 * 但由于对单个账本中的写入过程被设计为同步写入，因而非线程安全的设计并不会影响在此场景下的使用，而且由于省去了线程间同步操作，反而提升了性能；
 * 
 * @author huanghaiquan
 *
 * @param <T> 默克尔树的数据节点的类型；
 */
public class MerkleSortTree<T> implements Transactional {

	private static final BytesConverter<byte[]> BYTES_TO_BYTES_CONVERTER = new BytesToBytesConverter();

	public static final int DEFAULT_DEGREE = TreeDegree.D3.DEGREEE;

	public static final int DEFAULT_MAX_LEVEL = TreeDegree.D3.MAX_DEPTH;

	public static final long DEFAULT_MAX_COUNT = TreeDegree.D3.MAX_COUNT;

	protected final int DEGREE;

	protected final int MAX_LEVEL;

	// 正好是 2 的 60 次方，足以覆盖 long 类型的正整数，且为避免溢出预留了区间；
	protected final long MAX_COUNT;

	protected final BytesConverter<T> CONVERTER;

	protected final HashFunction DEFAULT_HASH_FUNCTION;

	protected final TreeOptions OPTIONS;

	private final Bytes KEY_PREFIX;

	private ExPolicyKVStorage kvStorage;

	private MerkleNode root;

	/**
	 * 构建空的树；
	 * 
	 * @param setting   密码参数；
	 * @param keyPrefix 键的前缀；
	 * @param kvStorage 节点的存储；
	 * @param converter 数据的转换器；
	 */
	public MerkleSortTree(TreeOptions options, String keyPrefix, ExPolicyKVStorage kvStorage,
			BytesConverter<T> converter) {
		this(TreeDegree.D3, options, Bytes.fromString(keyPrefix), kvStorage, converter);
	}

	/**
	 * 构建空的树；
	 * 
	 * @param kvStorage
	 */
	public MerkleSortTree(TreeDegree degree, TreeOptions options, String keyPrefix, ExPolicyKVStorage kvStorage,
			BytesConverter<T> converter) {
		this(degree, options, Bytes.fromString(keyPrefix), kvStorage, converter);
	}

	/**
	 * 构建空的树；
	 * 
	 * @param kvStorage
	 */
	public MerkleSortTree(TreeOptions options, Bytes keyPrefix, ExPolicyKVStorage kvStorage,
			BytesConverter<T> converter) {
		this(TreeDegree.D3, options, keyPrefix, kvStorage, converter);
	}

	/**
	 * 构建空的树；
	 * 
	 * @param kvStorage
	 */
	public MerkleSortTree(TreeDegree degree, TreeOptions options, Bytes keyPrefix, ExPolicyKVStorage kvStorage,
			BytesConverter<T> converter) {
		this.DEGREE = degree.DEGREEE;
		this.MAX_LEVEL = degree.MAX_DEPTH;
		this.MAX_COUNT = MathUtils.power(DEGREE, MAX_LEVEL);
		this.CONVERTER = converter;

		this.OPTIONS = options;
		this.KEY_PREFIX = keyPrefix;
		this.kvStorage = kvStorage;
		this.DEFAULT_HASH_FUNCTION = Crypto.getHashFunction(options.getDefaultHashAlgorithm());

		this.root = createTopRoot();
	}

	/**
	 * 创建 Merkle 树；
	 * 
	 * @param rootHash     节点的根Hash; 如果指定为 null，则实际上创建一个空的 Merkle Tree；
	 * @param verifyOnLoad 从外部存储加载节点时是否校验节点的哈希；
	 * @param kvStorage    保存 Merkle 节点的存储服务；
	 * @param readonly     是否只读；
	 */
	public MerkleSortTree(HashDigest rootHash, TreeOptions options, String keyPrefix, ExPolicyKVStorage kvStorage,
			BytesConverter<T> converter) {
		this(rootHash, options, Bytes.fromString(keyPrefix), kvStorage, converter);
	}

	/**
	 * 创建 Merkle 树；
	 * 
	 * @param rootHash     节点的根Hash; 如果指定为 null，则实际上创建一个空的 Merkle Tree；
	 * @param verifyOnLoad 从外部存储加载节点时是否校验节点的哈希；
	 * @param kvStorage    保存 Merkle 节点的存储服务；
	 * @param readonly     是否只读；
	 */
	public MerkleSortTree(HashDigest rootHash, TreeOptions options, Bytes keyPrefix, ExPolicyKVStorage kvStorage,
			BytesConverter<T> converter) {
		this.KEY_PREFIX = keyPrefix;
		this.OPTIONS = options;
		this.kvStorage = kvStorage;
		this.DEFAULT_HASH_FUNCTION = Crypto.getHashFunction(options.getDefaultHashAlgorithm());
		this.CONVERTER = converter;

		IndexEntry merkleIndex = loadMerkleEntry(rootHash);
		int subtreeCount = merkleIndex.getChildCounts().length;
		TreeDegree degree = null;
		for (TreeDegree td : TreeDegree.values()) {
			if (td.DEGREEE == subtreeCount) {
				degree = td;
			}
		}
		if (degree == null) {
			throw new MerkleProofException("The root node with hash[" + rootHash.toBase58() + "] has wrong degree!");
		}

		this.DEGREE = degree.DEGREEE;
		this.MAX_LEVEL = degree.MAX_DEPTH;
		this.MAX_COUNT = MathUtils.power(DEGREE, MAX_LEVEL);

		this.root = new PathNode(rootHash, merkleIndex, this);
	}

	/**
	 * 创建数据类型为字节数组的空的默克尔排序树；
	 * 
	 * @param setting
	 * @param keyPrefix
	 * @param kvStorage
	 * @return
	 */
	public static MerkleSortTree<byte[]> createBytesTree(TreeOptions options, String keyPrefix,
			ExPolicyKVStorage kvStorage) {
		return new MerkleSortTree<byte[]>(options, keyPrefix, kvStorage, BYTES_TO_BYTES_CONVERTER);
	}

	/**
	 * 创建数据类型为字节数组的空的默克尔排序树；
	 * 
	 * @param setting
	 * @param keyPrefix
	 * @param kvStorage
	 * @return
	 */
	public static MerkleSortTree<byte[]> createBytesTree(TreeOptions options, Bytes keyPrefix,
			ExPolicyKVStorage kvStorage) {
		return new MerkleSortTree<byte[]>(options, keyPrefix, kvStorage, BYTES_TO_BYTES_CONVERTER);
	}

	/**
	 * 创建数据类型为字节数组的空的默克尔排序树；
	 * 
	 * @param setting
	 * @param keyPrefix
	 * @param kvStorage
	 * @return
	 */
	public static MerkleSortTree<byte[]> createBytesTree(TreeDegree degree, TreeOptions options, String keyPrefix,
			ExPolicyKVStorage kvStorage) {
		return new MerkleSortTree<byte[]>(degree, options, keyPrefix, kvStorage, BYTES_TO_BYTES_CONVERTER);
	}

	/**
	 * 创建数据类型为字节数组的空的默克尔排序树；
	 * 
	 * @param setting
	 * @param keyPrefix
	 * @param kvStorage
	 * @return
	 */
	public static MerkleSortTree<byte[]> createBytesTree(TreeDegree degree, TreeOptions options, Bytes keyPrefix,
			ExPolicyKVStorage kvStorage) {
		return new MerkleSortTree<byte[]>(degree, options, keyPrefix, kvStorage, BYTES_TO_BYTES_CONVERTER);
	}

	/**
	 * 以指定哈希的节点为根节点创建数据类型为字节数组的默克尔排序树；
	 * 
	 * @param setting
	 * @param keyPrefix
	 * @param kvStorage
	 * @return
	 */
	public static MerkleSortTree<byte[]> createBytesTree(HashDigest rootHash, TreeOptions options, String keyPrefix,
			ExPolicyKVStorage kvStorage) {
		return new MerkleSortTree<byte[]>(rootHash, options, keyPrefix, kvStorage, BYTES_TO_BYTES_CONVERTER);
	}

	/**
	 * 以指定哈希的节点为根节点创建数据类型为字节数组的默克尔排序树；
	 * 
	 * @param setting
	 * @param keyPrefix
	 * @param kvStorage
	 * @return
	 */
	public static MerkleSortTree<byte[]> createBytesTree(HashDigest rootHash, TreeOptions options, Bytes keyPrefix,
			ExPolicyKVStorage kvStorage) {
		return new MerkleSortTree<byte[]>(rootHash, options, keyPrefix, kvStorage, BYTES_TO_BYTES_CONVERTER);
	}

	/**
	 * 创建顶级根节点；
	 * 
	 * @return
	 */
	protected PathNode createTopRoot() {
		long step = MAX_COUNT / DEGREE;
		return new PathNode(0, step, this);
	}

	public HashDigest getRootHash() {
		return root.getNodeHash();
	}

	public long getCount() {
		return count(root);
	}

	private void checkId(long id) {
		if (id < 0) {
			throw new IllegalArgumentException("'id' is negative!");
		}
		if (id >= MAX_COUNT) {
			throw new IllegalArgumentException("'id' is greater than or equal to the MAX_COUNT[" + MAX_COUNT + "]!");
		}
	}

	public void set(long id, T data) {
		checkId(id);
		root = mergeChildren(root.getNodeHash(), root, id, data);
	}

	public T get(long id) {
		return seekData(root, id, NullSelector.INSTANCE);
	}

	public SkippingIterator<ValueEntry> iterator() {
		// 克隆根节点的数据，避免根节点的更新影响了迭代器；
		return new MerklePathIterator(root.getOffset(), root.getStep(), root.getChildHashs().clone(),
				root.getChildCounts().clone());
	}

	/**
	 * 返回指定编码数据的默克尔证明；
	 * 
	 * @param id
	 * @return
	 */
	public MerkleProof getProof(long id) {
		MerkleProofSelector proofSelector = new MerkleProofSelector();
		T data = seekData(id, proofSelector);
		if (data == null) {
			return null;
		}
		return proofSelector.getProof();
	}

	/**
	 * 从默克尔树的根开始，搜索指定 id 的数据，并记录搜索经过的节点的哈希；如果数据不存在，则返回 null；
	 * 
	 * @param id           要查找的数据的 id；
	 * @param pathSelector
	 * @return
	 */
	public T seekData(long id, MerkleEntrySelector pathSelector) {
		pathSelector.accept(root.getNodeHash(), root);
		return seekData(root, id, pathSelector);
	}

	/**
	 * 从指定的默克尔索引开始，搜索指定 id 的数据，并记录搜索经过的节点的哈希；如果数据不存在，则返回 null；
	 * 
	 * @param merkleIndex  开始搜索的默克尔索引节点；
	 * @param id           要查找的数据的 id；
	 * @param pathSelector 路径选择器，记录搜索过程经过的默克尔节点；
	 * @return 搜索到的指定 ID 的数据；
	 */
	private T seekData(IndexEntry merkleIndex, long id, MerkleEntrySelector pathSelector) {
		int idx = index(id, merkleIndex);
		if (idx < 0) {
			return null;
		}
		if (merkleIndex.getStep() > 1) {
			IndexEntry child;
			if (merkleIndex instanceof PathNode) {
				PathNode path = (PathNode) merkleIndex;
				child = path.getChild(idx);
				if (child == null) {
					return null;
				}
				HashDigest childHash = path.getChildHashs()[idx];
				pathSelector.accept(childHash, child);
			} else {
				HashDigest[] childHashs = merkleIndex.getChildHashs();
				HashDigest childHash = childHashs[idx];
				if (childHash == null) {
					return null;
				}
				child = loadMerkleEntry(childHash);
				pathSelector.accept(childHash, child);
			}

			return seekData((IndexEntry) child, id, pathSelector);
		}
		// leaf node;
		T child;
		if (merkleIndex instanceof LeafNode) {
			@SuppressWarnings("unchecked")
			LeafNode<T> path = (LeafNode<T>) merkleIndex;
			child = path.getChild(idx);
		} else {
			HashDigest[] childHashs = merkleIndex.getChildHashs();
			HashDigest childHash = childHashs[idx];
			if (childHash == null) {
				return null;
			}

			child = CONVERTER.fromBytes(loadNodeBytes(childHash));
		}
		return child;
	}

	@Override
	public boolean isUpdated() {
		if (root == null) {
			return true;
		}
		if (root instanceof PathNode) {
			return ((PathNode) root).isModified();
		}
		return false;
	}

	@Override
	public final void commit() {
		root.commit();
	}

	@Override
	public final void cancel() {
		root.cancel();
	}

	/**
	 * 计算指定 id 在指定 step 的子树根节点的偏移量；
	 * 
	 * @param id
	 * @param step
	 * @return
	 */
	private long calculateOffset(long id, long step) {
		long count = step * DEGREE;
		return id - id % count;
	}

	/**
	 * 上一级的步长；
	 * 
	 * @param step
	 * @return
	 */
	private long upStep(long step) {
		return step * DEGREE;
	}

	private long nextOffset(long offset, long step) {
		return offset + step * DEGREE;
	}

	/**
	 * 计算指定 id 在 {@link IndexEntry} 中的偏移位置；<br>
	 * 如果 id 不属于 {@link IndexEntry} 的地址区间，则返回 -1；
	 * 
	 * @param id          编号；
	 * @param merkleIndex 默克尔索引，表示1个特定的位置区间；
	 * @return
	 */
	private int index(long id, IndexEntry merkleIndex) {
		return index(id, merkleIndex.getOffset(), merkleIndex.getStep());
	}

	/**
	 * 计算指定 id 在指定地址区间中的偏移位置；<br>
	 * 
	 * @param id     编号；
	 * @param offset 初始偏移位置；
	 * @param step   步长，1个位置包含的节点数；由“步长 * {@link #DEGREE} ”构成参与计算的位置区间范围；
	 * @return
	 */
	private int index(long id, long offset, long step) {
		if (id < offset) {
			return -1;
		}
		long nextOffset = nextOffset(offset, step);
		if (id >= nextOffset) {
			return -1;
		}
		long p = id - offset;
		long m = p % step;
		return (int) ((p - m) / step);
	}

	private static long count(IndexEntry merkleIndex) {
		long[] childCounts = merkleIndex.getChildCounts();
		// 使用此方法的上下文逻辑已经能够约束每一项的数字大小范围，不需要考虑溢出；
		return ArrayUtils.sum(childCounts);
	}

	/**
	 * 合并子节点，返回共同的父节点；
	 * 
	 * @param indexNodeHash 路径节点的哈希；
	 * @param indexNode     路径节点；
	 * @param dataNode      数据项；
	 * 
	 * @return
	 */
	private MerkleNode mergeChildren(HashDigest indexNodeHash, IndexEntry indexNode, long dataId, T data) {
		final long PATH_OFFSET = indexNode.getOffset();
		final long PATH_STEP = indexNode.getStep();

		long pathId = PATH_OFFSET;

		long dataOffset = calculateOffset(dataId, PATH_STEP);
		long pathOffset = PATH_OFFSET;
		long step = PATH_STEP;
		while (dataOffset != pathOffset) {
			step = upStep(step);
			if (step >= MAX_COUNT) {
				throw new IllegalStateException("The 'step' overflows!");
			}
			dataOffset = calculateOffset(dataId, step);
			pathOffset = calculateOffset(pathId, step);
		}

		// 判断参数指定的索引节点和数据节点是否具有从属关系，还是并列关系；
		if (step == PATH_STEP && pathOffset == PATH_OFFSET) {
			// 数据节点属于 pathNode 路径节点；
			// 把数据节点合并到 pathNode 路径节点；
			int index = index(dataId, pathOffset, step);
			if (PATH_STEP > 1) {
				PathNode parentNode;
				if (indexNode instanceof PathNode) {
					parentNode = (PathNode) indexNode;
				} else {
					parentNode = new PathNode(indexNodeHash, indexNode, this);
				}

				updateChildAtIndex(parentNode, index, dataId, data);
				return parentNode;
			} else {
				// while PATH_STEP == 1, this index node is leaf;
				if (indexNode instanceof LeafNode) {
					@SuppressWarnings("unchecked")
					LeafNode<T> parentNode = (LeafNode<T>) indexNode;
					updateChildAtIndex(parentNode, index, dataId, data);
					return parentNode;
				} else {
					LeafNode<T> parentNode = new LeafNode<T>(indexNodeHash, indexNode, this);
					updateChildAtIndex(parentNode, index, dataId, data);
					return parentNode;
				}
			}
		} else {
			// 数据节点不从属于 pathNode 路径节点，它们有共同的父节点；
			// 创建共同的父节点；
			PathNode parentPathNode = new PathNode(pathOffset, step, this);

			int dataChildIndex = parentPathNode.index(dataId);
			updateChildAtIndex(parentPathNode, dataChildIndex, dataId, data);

			int pathChildIndex = parentPathNode.index(pathId);
			updateChildAtIndex(parentPathNode, pathChildIndex, indexNodeHash, indexNode);

			return parentPathNode;
		}
	}

	/**
	 * 设置指定位置的子节点为指定的数据节点；
	 * 
	 * @param parentNode 作为父节点的默克尔索引节点；
	 * @param index      要设置的数据节点在父节点中的位置；
	 * @param dataId     要设置的数据节点的 id；
	 * @param data       要设置的字节数据；
	 * @return
	 */
	private void updateChildAtIndex(LeafNode<T> parentNode, int index, long dataId, T data) {
		T origChild = parentNode.getChild(index);

		T newChild = updateData(dataId, origChild, data);
		if (newChild != null) {
			parentNode.setChild(index, null, newChild);
		}
	}

	/**
	 * 设置指定位置的子节点为指定的子索引节点；
	 * 
	 * @param parentNode 作为父节点的默克尔索引节点；
	 * @param index      要设置的子索引节点在父节点中的位置；
	 * @param childHash  子索引节点的哈希；
	 * @param childEntry 子索引节点；
	 * @return
	 */
	private void updateChildAtIndex(PathNode parentNode, int index, HashDigest childHash, IndexEntry childEntry) {
		IndexEntry origChild = parentNode.getChild(index);
		if (origChild == null) {
			parentNode.setChild(index, childHash, childEntry);
			return;
		}

		// 合并两个子节点；
		HashDigest origChildHash = parentNode.getChildHashAtIndex(index);
		PathNode newChild = mergeChildren(origChildHash, origChild, childHash, childEntry);
		parentNode.setChild(index, null, newChild);
	}

	/**
	 * 设置指定位置的子节点为指定的数据节点；
	 * 
	 * @param parentNode 作为父节点的默克尔索引节点；
	 * @param index      要设置的数据节点在父节点中的位置；
	 * @param dataId     要设置的数据节点的 id；
	 * @param dataBytes  要设置的字节数据；
	 * @return
	 */
	private void updateChildAtIndex(PathNode parentNode, int index, long dataId, T dataBytes) {
		IndexEntry origChild = parentNode.getChild(index);
		if (origChild == null) {
			long offset = calculateOffset(dataId, 1L);
			LeafNode<T> leafNode = new LeafNode<T>(offset, this);
			updateChildAtIndex(leafNode, leafNode.index(dataId), dataId, dataBytes);

			parentNode.setChild(index, null, leafNode);
			return;
		}

		// 合并两个子节点；
		HashDigest origChildHash = parentNode.getChildHashAtIndex(index);
		IndexEntry newChild = mergeChildren(origChildHash, origChild, dataId, dataBytes);
		parentNode.setChild(index, null, newChild);
	}

	/**
	 * 合并子节点，返回共同的父节点；
	 * 
	 * @param pathNode1     数据项；
	 * @param pathNodeHash2 路径节点的哈希；
	 * @param pathNode2     路径节点；
	 * @return
	 */
	private PathNode mergeChildren(HashDigest pathNodeHash1, IndexEntry pathNode1, HashDigest pathNodeHash2,
			IndexEntry pathNode2) {
		final long PATH_OFFSET1 = pathNode1.getOffset();
		final long PATH_STEP1 = pathNode1.getStep();
		final long PATH_OFFSET2 = pathNode2.getOffset();
		final long PATH_STEP2 = pathNode2.getStep();
		if (PATH_OFFSET1 == PATH_OFFSET2 && PATH_STEP1 == PATH_STEP2) {
			throw new IllegalStateException("Can not merge two path nodes with the same index!");
		}

		long id1 = PATH_OFFSET1;
		long id2 = PATH_OFFSET2;

		long offset1 = PATH_OFFSET1;
		long offset2 = PATH_OFFSET2;

		long step = Math.max(PATH_STEP1, PATH_STEP2);
		while (offset1 != offset2) {
			step = upStep(step);
			if (step >= MAX_COUNT) {
				throw new IllegalStateException("The 'step' overlows!");
			}
			offset1 = calculateOffset(id1, step);
			offset2 = calculateOffset(id2, step);
		}

		// 判断参数指定的两个索引节点是否具有从属关系，还是并列关系；
		if (step == PATH_STEP1 && offset2 == PATH_OFFSET1) {
			// pathNode1 是父节点，pathNode2 是子节点；
			PathNode parentNode;
			if (pathNode1 instanceof PathNode) {
				parentNode = (PathNode) pathNode1;
			} else {
				parentNode = new PathNode(pathNodeHash1, pathNode1, this);
			}
			int index = parentNode.index(id2);
			updateChildAtIndex(parentNode, index, pathNodeHash2, pathNode2);

			return parentNode;
		} else if (step == PATH_STEP2 && offset2 == PATH_OFFSET2) {
			// pathNode2 是父节点，pathNode1 是子节点；
			PathNode parentNode;
			if (pathNode2 instanceof PathNode) {
				parentNode = (PathNode) pathNode2;
			} else {
				parentNode = new PathNode(pathNodeHash2, pathNode2, this);
			}
			int index = parentNode.index(id1);
			updateChildAtIndex(parentNode, index, pathNodeHash1, pathNode1);

			return parentNode;
		} else {
			// 数据节点不属于 pathNode 路径节点；
			// 创建共同的父节点；
			PathNode parentNode = new PathNode(offset2, step, this);

			int childIndex1 = parentNode.index(id1);
			updateChildAtIndex(parentNode, childIndex1, pathNodeHash1, pathNode1);

			int childIndex2 = parentNode.index(id2);
			updateChildAtIndex(parentNode, childIndex2, pathNodeHash2, pathNode2);

			return parentNode;
		}
	}

	/**
	 * 更新指定 id 的数据节点；
	 * 
	 * <p>
	 * 这是模板方法，默认实现并不允许更新相同 id 的数据，并抛出 {@link MerkleProofException} 异常;
	 * 
	 * @param origValue 原数据；如果为 null，则表明是新增数据；
	 * @param newValue  新数据；
	 * @return 更新后的新节点的数据； 如果返回 null，则忽略此次操作；
	 */
	protected T updateData(long id, T origData, T newData) {
		if (origData != null) {
			throw new MerkleTreeKeyExistException("Unsupport updating datas with the same id!");
		}
		return newData;
	}

	/**
	 * 准备提交指定 id 的数据，保存至存储服务；<br>
	 * 
	 * 此方法在对指定数据节点进行序列化并进行哈希计算之前被调用；
	 * 
	 * @param id
	 * @param data
	 */
	protected T beforeCommit(long id, T data) {
		return data;
	}

	/**
	 * 已经取消指定 id 的数据；
	 * 
	 * @param id
	 * @param child
	 */
	protected void afterCancel(long id, T data) {
	}

	/**
	 * 加载指定哈希的默克尔节点的字节；
	 * 
	 * @param nodeHash
	 * @return
	 */
	private byte[] loadNodeBytes(HashDigest nodeHash) {
		byte[] nodeBytes = loadBytes(nodeHash);
		if (OPTIONS.isVerifyHashOnLoad()) {
			verifyHash(nodeHash, nodeBytes, "Merkle hash verification fail! -- NodeHash=" + nodeHash.toBase58());
		}
		return nodeBytes;
	}

	private HashDigest saveNodeBytes(byte[] nodeBytes, boolean reportDuplication) {
		HashDigest nodeHash = DEFAULT_HASH_FUNCTION.hash(nodeBytes);
		saveBytes(nodeHash, nodeBytes, reportDuplication);
		return nodeHash;
	}

	private IndexEntry loadMerkleEntry(HashDigest nodeHash) {
		byte[] nodeBytes = loadNodeBytes(nodeHash);

		IndexEntry merkleEntry = BinaryProtocol.decode(nodeBytes);
		return merkleEntry;
	}

	private void verifyHash(HashDigest hash, byte[] bytes, String errMessage, Object... errorArgs) {
		if (!Crypto.getHashFunction(hash.getAlgorithm()).verify(hash, bytes)) {
			throw new MerkleProofException(String.format(errMessage, errorArgs));
		}
	}

	private HashDigest saveIndex(IndexEntry indexEntry) {
		byte[] nodeBytes = BinaryProtocol.encode(indexEntry, IndexEntry.class);
		return saveNodeBytes(nodeBytes, true);
	}

	/**
	 * 生成存储节点数据的key；
	 * 
	 * @param key 节点逻辑key；
	 * @return 节点的存储key；
	 */
	private Bytes encodeStorageKey(byte[] key) {
		return KEY_PREFIX.concat(key);
	}

	/**
	 * 生成存储节点数据的key；
	 * 
	 * @param key 节点逻辑key；
	 * @return 节点的存储key；
	 */
	private Bytes encodeStorageKey(Bytes key) {
		return KEY_PREFIX.concat(key);
	}

	/**
	 * 加载指定节点的内容，如果不存在，则抛出异常；
	 * 
	 * @param nodeHash
	 * @return
	 */
	@SuppressWarnings("unused")
	private byte[] loadBytes(byte[] key) {
		Bytes storageKey = encodeStorageKey(key);
		byte[] nodeBytes = kvStorage.get(storageKey);
		if (nodeBytes == null) {
			throw new MerkleProofException("Merkle node does not exist! -- key=" + storageKey.toBase58());
		}
		return nodeBytes;
	}

	private byte[] loadBytes(Bytes key) {
		Bytes storageKey = encodeStorageKey(key);
		byte[] nodeBytes = kvStorage.get(storageKey);
		if (nodeBytes == null) {
			throw new MerkleProofException("Merkle node does not exist! -- key=" + storageKey.toBase58());
		}
		return nodeBytes;
	}

	private void saveBytes(Bytes key, byte[] nodeBytes, boolean reportDuplication) {
		Bytes storageKey = encodeStorageKey(key);
		boolean success = kvStorage.set(storageKey, nodeBytes, ExPolicy.NOT_EXISTING);
		if (reportDuplication && !success) {
			throw new MerkleProofException("Merkle node already exist! -- key=" + storageKey.toBase58());
		}
	}

	// ----------------------------- inner types --------------------------

	public static interface MerkleEntrySelector {

		void accept(HashDigest nodeHash, IndexEntry nodePath);

		void accept(HashDigest nodeHash, long id, byte[] bytesValue);

	}

	public static class NullSelector implements MerkleEntrySelector {

		public static final MerkleEntrySelector INSTANCE = new NullSelector();

		private NullSelector() {
		}

		@Override
		public void accept(HashDigest nodeHash, IndexEntry nodePath) {
		}

		@Override
		public void accept(HashDigest nodeHash, long id, byte[] bytesValue) {
		}

	}

	public static class MerkleProofSelector implements MerkleEntrySelector {

		private List<HashDigest> paths = new ArrayList<HashDigest>();

		private boolean success;

		public MerkleProof getProof() {
			return new HashPathProof(paths);
		}

		@Override
		public void accept(HashDigest nodeHash, IndexEntry nodePath) {
			paths.add(nodeHash);
		}

		public void addPath(HashDigest hashPath) {
			paths.add(hashPath);
		}

		@Override
		public void accept(HashDigest nodeHash, long id, byte[] bytesValue) {
			// 已经查找到对应的数据节点；
			this.success = true;
		}

		public boolean isSuccess() {
			return success;
		}
	}

	public static interface ValueEntry {

		long getId();

		/**
		 * 数据字节；
		 * 
		 * @return
		 */
		byte[] getBytes();

	}

	/**
	 * 表示 {@link MerkleSortTree} 维护的数据项；
	 * 
	 * @author huanghaiquan
	 *
	 */
	private static class IDValue implements ValueEntry {

		private long id;

		private byte[] value;

		private IDValue(long id, byte[] value) {
			this.id = id;
			this.value = value;
		}

		@Override
		public long getId() {
			return id;
		}

		/**
		 * 数据字节；
		 * 
		 * @return
		 */
		@Override
		public byte[] getBytes() {
			return value;
		}

	}

	/**
	 * 默克尔数据索引；
	 * 
	 * <br>
	 * 通过 {@link #getOffset()} 和 {@link #getStep()} 表示 1 个特定的位置区间;
	 * 
	 * @author huanghaiquan
	 *
	 */
	@DataContract(code = DataCodes.MERKLE_SORTED_TREE_INDEX)
	public static interface IndexEntry {

		/**
		 * 所有子项的起始ID； <br>
		 * 
		 * 即 {@link #getChildHashs()} 中第 0 个子项的 ID ；
		 * 
		 * @return
		 */
		@DataField(order = 0, primitiveType = PrimitiveType.INT64, numberEncoding = NumberEncoding.LONG)
		long getOffset();

		/**
		 * 子项的 ID 的递增步长；<br>
		 * 
		 * 即 {@link #getChildHashs()} 中任意子项的 ID 加上 {@link #getStep()} 为下一个子项的 ID；
		 * 
		 * @return
		 */
		@DataField(order = 1, primitiveType = PrimitiveType.INT64, numberEncoding = NumberEncoding.LONG)
		long getStep();

		/**
		 * 每个子项包含的数据项个数的列表；
		 * 
		 * @return
		 */
		@DataField(order = 2, primitiveType = PrimitiveType.INT64, numberEncoding = NumberEncoding.LONG, list = true)
		long[] getChildCounts();

		/**
		 * 子项的哈希的列表； <br>
		 * 
		 * 子项的个数总是固定的 {@value MerkleSortTree#DEGREE} ;
		 * 
		 * @return
		 */
		@DataField(order = 3, primitiveType = PrimitiveType.BYTES, list = true)
		HashDigest[] getChildHashs();
	}

	/**
	 * 默克尔树的节点；
	 * 
	 * @author huanghaiquan
	 *
	 */
	private static abstract class MerkleNode implements IndexEntry {

		protected final MerkleSortTree<?> TREE;

		/**
		 * 与当前子树相邻的右侧兄弟子树的偏移量；
		 */
		protected final long NEXT_OFFSET;

		protected final long OFFSET;

		protected final long STEP;

		private HashDigest nodeHash;

		private long[] childCounts;

		private HashDigest[] childHashs;

		private boolean modified;

		private Object[] children;

		private HashDigest[] origChildHashs;

		protected MerkleNode(HashDigest nodeHash, long offset, long step, long[] childCounts, HashDigest[] childHashs,
				MerkleSortTree<?> tree) {
			this.TREE = tree;
			NEXT_OFFSET = tree.nextOffset(offset, step);

			this.nodeHash = nodeHash;
			this.modified = (nodeHash == null);

			this.OFFSET = offset;
			this.STEP = step;
			this.childCounts = childCounts;
			this.childHashs = childHashs;
			this.origChildHashs = childHashs.clone();
			this.children = new Object[tree.DEGREE];

			assert childHashs.length == tree.DEGREE;
		}

		public HashDigest getNodeHash() {
			return nodeHash;
		}

		public boolean isModified() {
			return modified;
		}

		@Override
		public long getOffset() {
			return OFFSET;
		}

		@Override
		public long getStep() {
			return STEP;
		}

		@Override
		public long[] getChildCounts() {
			return childCounts;
		}

		@Override
		public HashDigest[] getChildHashs() {
			return childHashs;
		}

		/**
		 * 返回指定 ID 在当前节点表示的子树的偏移位置；
		 * 
		 * <br>
		 * 
		 * 如果不属于当前节点，则返回 -1；
		 * 
		 * @param id
		 * @return
		 */
		public int index(long id) {
			if (id < OFFSET || id >= NEXT_OFFSET) {
				return -1;
			}
			long p = id - OFFSET;
			long m = p % STEP;
			return (int) ((p - m) / STEP);
		}

		/**
		 * 是否包含指定 id 的节点；
		 * 
		 * @param id
		 * @return
		 */
		@SuppressWarnings("unused")
		public boolean contain(long id) {
			return id >= OFFSET && id < NEXT_OFFSET;
		}

		public HashDigest getChildHashAtIndex(int index) {
			return childHashs[index];
		}

		/**
		 * @param index
		 * @return
		 */
		protected Object getChildObject(int index) {
			Object child = children[index];
			if (child != null) {
				return child;
			}
			HashDigest childHash = childHashs[index];
			if (childHash == null) {
				return null;
			}
			child = loadChild(childHash);
			children[index] = child;
			return child;
		}

		private Object loadChild(HashDigest childHash) {
			byte[] childBytes = TREE.loadNodeBytes(childHash);
			return deserializeChild(childBytes);
		}

		protected abstract Object deserializeChild(byte[] childBytes);

		protected abstract byte[] serializeChild(Object child);

		/**
		 * 设置子节点；
		 * 
		 * @param index     子节点的位置；
		 * @param childHash 子节点的哈希；如果为 null，则在 commit 时计算哈希；
		 * @param child     子节点；
		 */
		protected void setChildObject(int index, HashDigest childHash, Object child) {
			childHashs[index] = childHash;
			children[index] = child;
			modified = true;
		}

		/**
		 * 提交当前节点，把节点数据写入存储；
		 * 
		 * @return
		 */
		public HashDigest commit() {
			if (!modified) {
				return nodeHash;
			}

			commitChildren(childCounts, childHashs, children);

			// save;
			HashDigest hash = TREE.saveIndex(this);

			// update hash;
			for (int i = 0; i < TREE.DEGREE; i++) {
				origChildHashs[i] = childHashs[i];
			}
			this.nodeHash = hash;
			this.modified = false;

			return hash;
		}

		/**
		 * 提交子节点；在提交当前节点之前执行；
		 * 
		 * @param childCounts
		 * @param childHashs
		 * @param children
		 */
		protected abstract void commitChildren(long[] childCounts, HashDigest[] childHashs, Object[] children);

		protected void cancelChild(long id, Object child) {
		}

		/**
		 * 取消当前节点的更改；
		 */
		public void cancel() {
			Object child;
			for (int i = 0; i < TREE.DEGREE; i++) {
				if (children[i] != null && (childHashs[i] == null || origChildHashs[i] == null
						|| (!childHashs[i].equals(origChildHashs[i])))) {
					child = children[i];
					children[i] = null;
					// 清理字节点以便优化大对象的垃圾回收效率；
					if (STEP == 1) {
						long id = OFFSET + i;
						cancelChild(id, child);
					} else if (child instanceof MerkleNode) {
						((MerkleNode) child).cancel();
					}
				}
				childHashs[i] = origChildHashs[i];
			}
			// 注：不需要处理 nodeHash 的回滚，因为 nodeHash 是 commit 操作的最后确认标志；
		}
	}

	/**
	 * 默克尔树的叶子节点；
	 * 
	 * @author huanghaiquan
	 *
	 */
	private static class LeafNode<T> extends MerkleNode {

		private final BytesConverter<T> CONVERTER;

		public LeafNode(long offset, MerkleSortTree<T> tree) {
			this(null, offset, 1L, new long[tree.DEGREE], new HashDigest[tree.DEGREE], tree);
		}

		public LeafNode(HashDigest nodeHash, IndexEntry index, MerkleSortTree<T> tree) {
			this(nodeHash, index.getOffset(), index.getStep(), index.getChildCounts(), index.getChildHashs(), tree);
		}

		protected LeafNode(HashDigest nodeHash, long offset, long step, long[] childCounts, HashDigest[] childHashs,
				MerkleSortTree<T> tree) {
			super(nodeHash, offset, step, childCounts, childHashs, tree);
			assert step == 1;

			CONVERTER = tree.CONVERTER;
		}

		@SuppressWarnings("unchecked")
		private MerkleSortTree<T> tree() {
			return (MerkleSortTree<T>) TREE;
		}

		protected void setChild(int index, HashDigest childHash, T child) {
			super.setChildObject(index, childHash, child);
		}

		@SuppressWarnings("unchecked")
		public T getChild(int index) {
			return (T) super.getChildObject(index);
		}

		@Override
		protected T deserializeChild(byte[] childBytes) {
			return CONVERTER.fromBytes(childBytes);
		}

		@SuppressWarnings("unchecked")
		@Override
		protected byte[] serializeChild(Object child) {
			return CONVERTER.toBytes((T) child);
		}

		@Override
		protected void commitChildren(long[] childCounts, HashDigest[] childHashs, Object[] children) {
			for (int i = 0; i < TREE.DEGREE; i++) {
				// 保存新创建的子节点；
				if (childHashs[i] == null && children[i] != null) {
					@SuppressWarnings("unchecked")
					T child = (T) children[i];
					long id = OFFSET + i;
					child = tree().beforeCommit(id, child);
					byte[] childBytes = CONVERTER.toBytes(child);
					childHashs[i] = tree().saveNodeBytes(childBytes, TREE.OPTIONS.isReportDuplicatedData());
					childCounts[i] = 1;
					children[i] = child;
				}
			}
		}

		@SuppressWarnings("unchecked")
		@Override
		protected void cancelChild(long id, Object child) {
			tree().afterCancel(id, (T) child);
		}

	}

	/**
	 * 默克尔树的路径实现（非叶子节点）；
	 * 
	 * @author huanghaiquan
	 *
	 */
	private static class PathNode extends MerkleNode {

		protected PathNode(long offset, long step, MerkleSortTree<?> tree) {
			this(null, offset, step, new long[tree.DEGREE], new HashDigest[tree.DEGREE], tree);
		}

		protected PathNode(HashDigest nodeHash, IndexEntry index, MerkleSortTree<?> tree) {
			this(nodeHash, index.getOffset(), index.getStep(), index.getChildCounts(), index.getChildHashs(), tree);
		}

		protected PathNode(HashDigest nodeHash, long offset, long step, long[] childCounts, HashDigest[] childHashs,
				MerkleSortTree<?> tree) {
			super(nodeHash, offset, step, childCounts, childHashs, tree);
			assert step > 1;
		}

		/**
		 * 设置子节点；
		 * 
		 * @param index     子节点的位置；
		 * @param childHash 子节点的哈希；如果为 null，则在 commit 时计算哈希；
		 * @param child     子节点；
		 */
		protected void setChild(int index, HashDigest childHash, IndexEntry child) {
			if (child.getStep() >= STEP || child.getOffset() < OFFSET || child.getOffset() >= NEXT_OFFSET) {
				throw new IllegalArgumentException("The specified child not belong to this node!");
			}
			super.setChildObject(index, childHash, child);
		}

		public IndexEntry getChild(int index) {
			return (IndexEntry) super.getChildObject(index);
		}

		@Override
		protected IndexEntry deserializeChild(byte[] childBytes) {
			return BinaryProtocol.decode(childBytes, IndexEntry.class);
		}

		@Override
		protected byte[] serializeChild(Object child) {
			return BinaryProtocol.encode(child, IndexEntry.class);
		}

		@Override
		protected void commitChildren(long[] childCounts, HashDigest[] childHashs, Object[] children) {
			// save the modified childNodes;
			for (int i = 0; i < TREE.DEGREE; i++) {
				if (children[i] != null) {
					IndexEntry child = (IndexEntry) children[i];
					// 需要先保存子节点，获得子节点的哈希；
					if (child instanceof MerkleNode) {
						childHashs[i] = ((MerkleNode) child).commit();
					}
					childCounts[i] = count(child);
				}
			}
		}

	}

	/**
	 * 数据迭代器；
	 * <p>
	 * 注：未考虑迭代过程中新写入数据引起的变化；
	 * 
	 * @author huanghaiquan
	 *
	 */
	private class MerklePathIterator implements SkippingIterator<ValueEntry> {

		private final long totalCount;

		private final long offset;

		private final long step;

		// 子节点的游标边界；
		private long[] childCounts;

		private HashDigest[] childHashs;

		private int childIndex;

		private long cursor = -1;

		private SkippingIterator<ValueEntry> childIterator;

		public MerklePathIterator(long offset, long step, HashDigest[] childHashs, long[] childCounts) {
			this.offset = offset;
			this.step = step;
			this.childHashs = childHashs;
			this.childCounts = childCounts;
			// 使用此方法的上下文逻辑已经能够约束每一项的数字大小范围，不需要考虑溢出；
			this.totalCount = ArrayUtils.sum(childCounts);
		}

		@Override
		public long getTotalCount() {
			return totalCount;
		}

		@Override
		public boolean hasNext() {
			return cursor + 1 < totalCount;
		}

		@Override
		public long skip(long count) {
			if (count < 0) {
				throw new IllegalArgumentException("The specified count is out of bound!");
			}
			if (count == 0) {
				return 0;
			}
			if (childIndex >= DEGREE) {
				return 0;
			}

			long s = ArrayUtils.sum(childCounts, 0, childIndex + 1);
			long skipped;// 实际可略过的数量；
			long currLeft = s - cursor - 1;
			if (count < currLeft) {
				// 实际略过的数量在 index 指示的当前子节点的范围内；
				if (childIterator == null) {
					childIterator = createChildIterator(childIndex);
				}
				skipped = count;
				long sk = childIterator.skip(skipped);
				assert sk == skipped;
			} else {
				// 已经超过 index 指示的当前子节点的剩余数量，直接忽略当前子节点；
				childIterator = null;
				skipped = currLeft;
				childIndex++;
				while (childIndex < DEGREE && skipped + childCounts[childIndex] <= count) {
					skipped += childCounts[childIndex];
					childIndex++;
				}
				if (childIndex < DEGREE) {
					// 未超出子节点的范围；
					long c = count - skipped;
					childIterator = createChildIterator(childIndex);
					long sk = childIterator.skip(c);
					assert sk == c;

					skipped = count;
				}
			}
			cursor = cursor + skipped;
			return skipped;
		}

		private SkippingIterator<ValueEntry> createChildIterator(int childIndex) {
			HashDigest childHash = childHashs[childIndex];
			if (childHash == null) {
				// 正常情况下不应该进入此逻辑分支，因为空的子节点的数量表为 0，迭代器的迭代处理逻辑理应过滤掉此位置的子节点；
				throw new IllegalStateException("The child hash is null !");
			}

			if (step > 1) {
				IndexEntry child = loadMerkleEntry(childHash);
				return new MerklePathIterator(child.getOffset(), child.getStep(), child.getChildHashs(),
						child.getChildCounts());
			}
			byte[] childBytes = loadNodeBytes(childHash);
			long id = offset + childIndex;
			return new MerkleDataIteratorWrapper(new IDValue(id, childBytes));
		}

		@Override
		public ValueEntry next() {
			if (!hasNext()) {
				return null;
			}

			long s = ArrayUtils.sum(childCounts, 0, childIndex + 1);

			while (cursor + 1 >= s && childIndex < DEGREE - 1) {
				childIndex++;
				childIterator = null;
				s += childCounts[childIndex];
			}

			if (childIterator == null) {
				childIterator = createChildIterator(childIndex);
			}
			cursor++;
			return childIterator.next();
		}

		@Override
		public long getCursor() {
			return cursor;
		}

	}

	private static class MerkleDataIteratorWrapper extends AbstractSkippingIterator<ValueEntry> {

		private ValueEntry data;

		public MerkleDataIteratorWrapper(ValueEntry data) {
			this.data = data;
		}

		@Override
		public long getTotalCount() {
			return 1;
		}

		@Override
		public ValueEntry next() {
			if (hasNext()) {
				cursor++;
				return data;
			}
			return null;
		}

	}

	private static class BytesToBytesConverter implements BytesConverter<byte[]> {

		@Override
		public byte[] toBytes(byte[] value) {
			return value;
		}

		@Override
		public byte[] fromBytes(byte[] bytes) {
			return bytes;
		}
	}

}
