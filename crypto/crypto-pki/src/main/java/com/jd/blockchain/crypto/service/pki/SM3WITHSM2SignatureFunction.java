package com.jd.blockchain.crypto.service.pki;

import static com.jd.blockchain.crypto.CryptoKeyType.PRIVATE;
import static com.jd.blockchain.crypto.CryptoKeyType.PUBLIC;
import static com.jd.blockchain.crypto.service.pki.PKIAlgorithm.SM3WITHSM2;

import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import org.bouncycastle.asn1.gm.GMObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.jcajce.provider.asymmetric.util.KeyUtil;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveGenParameterSpec;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.math.ec.custom.gm.SM2P256V1Curve;

import com.jd.blockchain.crypto.AsymmetricKeypair;
import com.jd.blockchain.crypto.CryptoAlgorithm;
import com.jd.blockchain.crypto.CryptoBytes;
import com.jd.blockchain.crypto.CryptoException;
import com.jd.blockchain.crypto.CryptoKeyType;
import com.jd.blockchain.crypto.PrivKey;
import com.jd.blockchain.crypto.PubKey;
import com.jd.blockchain.crypto.SignatureDigest;
import com.jd.blockchain.crypto.SignatureFunction;
import com.jd.blockchain.crypto.base.DefaultCryptoEncoding;

/**
 * @author zhanglin33
 * @title: SM3WITHSM2SignatureFunction
 * @description: TODO
 * @date 2019-05-15, 16:39
 */
public class SM3WITHSM2SignatureFunction implements SignatureFunction {
	private static final int RAW_PUBKEY_SIZE = 65;
	private static final int RAW_PRIVKEY_SIZE = 32 + 65;

	private static final int RAW_SIGNATUREDIGEST_SIZE = 64;

	private static final SM2P256V1Curve CURVE = new SM2P256V1Curve();
	private static final BigInteger GX = new BigInteger(
			"32C4AE2C1F1981195F9904466A39C994" + "8FE30BBFF2660BE1715A4589334C74C7", 16);
	private static final BigInteger GY = new BigInteger(
			"BC3736A2F4F6779C59BDCEE36B692153" + "D0A9877CC62A474002DF32E52139F0A0", 16);
	private static final ECPoint G = CURVE.createPoint(GX, GY);

	private static final AlgorithmIdentifier SM2_ALGORITHM_IDENTIFIER = new AlgorithmIdentifier(
			X9ObjectIdentifiers.id_ecPublicKey, GMObjectIdentifiers.sm2p256v1);

	@Override
	public SignatureDigest sign(PrivKey privKey, byte[] data) {

		Security.addProvider(new BouncyCastleProvider());

		byte[] rawPrivKeyBytes = privKey.getRawKeyBytes();

		if (rawPrivKeyBytes.length < RAW_PRIVKEY_SIZE) {
			throw new CryptoException("This key has wrong format!");
		}

		if (privKey.getAlgorithm() != SM3WITHSM2.code()) {
			throw new CryptoException("This key is not SM3WITHSM2 private key!");
		}

		PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(rawPrivKeyBytes);

		KeyFactory keyFactory;
		ECPrivateKey rawPrivKey;
		Signature signer;
		byte[] signature;

		try {
			keyFactory = KeyFactory.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
			rawPrivKey = (ECPrivateKey) keyFactory.generatePrivate(keySpec);
			signer = Signature.getInstance("SM3withSM2", BouncyCastleProvider.PROVIDER_NAME);

			signer.initSign(rawPrivKey);
			signer.update(data);
			signature = signer.sign();
		} catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException | InvalidKeySpecException
				| NoSuchProviderException e) {
			throw new CryptoException(e.getMessage(), e);
		}

		return DefaultCryptoEncoding.encodeSignatureDigest(SM3WITHSM2, signature);
	}

	@Override
	public boolean verify(SignatureDigest digest, PubKey pubKey, byte[] data) {

		Security.addProvider(new BouncyCastleProvider());

		byte[] rawPubKeyBytes = pubKey.getRawKeyBytes();
		byte[] rawDigestBytes = digest.getRawDigest();

		if (rawPubKeyBytes.length < RAW_PUBKEY_SIZE) {
			throw new CryptoException("This key has wrong format!");
		}

		if (pubKey.getAlgorithm() != SM3WITHSM2.code()) {
			throw new CryptoException("This key is not SM3WITHSM2 public key!");
		}

		if (digest.getAlgorithm() != SM3WITHSM2.code() || rawDigestBytes.length < RAW_SIGNATUREDIGEST_SIZE) {
			throw new CryptoException("This is not SM3WITHSM2 signature digest!");
		}

		X509EncodedKeySpec keySpec = new X509EncodedKeySpec(rawPubKeyBytes);

		KeyFactory keyFactory;
		ECPublicKey rawPubKey;
		Signature verifier;
		boolean isValid;

		try {
			keyFactory = KeyFactory.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
			rawPubKey = (ECPublicKey) keyFactory.generatePublic(keySpec);
			verifier = Signature.getInstance("SM3withSM2", BouncyCastleProvider.PROVIDER_NAME);
			verifier.initVerify(rawPubKey);
			verifier.update(data);
			isValid = verifier.verify(rawDigestBytes);
		} catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException | InvalidKeySpecException
				| NoSuchProviderException e) {
			throw new CryptoException(e.getMessage(), e);
		}

		return isValid;
	}

	@Override
	public boolean supportPubKey(byte[] pubKeyBytes) {
		return pubKeyBytes.length > (CryptoAlgorithm.CODE_SIZE + CryptoKeyType.TYPE_CODE_SIZE + RAW_PUBKEY_SIZE)
				&& CryptoAlgorithm.match(SM3WITHSM2, pubKeyBytes)
				&& pubKeyBytes[CryptoAlgorithm.CODE_SIZE] == PUBLIC.CODE;
	}

	@Override
	public PubKey resolvePubKey(byte[] pubKeyBytes) {
		if (supportPubKey(pubKeyBytes)) {
			return DefaultCryptoEncoding.createPubKey(SM3WITHSM2.code(), pubKeyBytes);
		} else {
			throw new CryptoException("pubKeyBytes are invalid!");
		}
	}

	@Override
	public boolean supportPrivKey(byte[] privKeyBytes) {
		return privKeyBytes.length > (CryptoAlgorithm.CODE_SIZE + CryptoKeyType.TYPE_CODE_SIZE + RAW_PRIVKEY_SIZE)
				&& CryptoAlgorithm.match(SM3WITHSM2, privKeyBytes)
				&& privKeyBytes[CryptoAlgorithm.CODE_SIZE] == PRIVATE.CODE;
	}

	@Override
	public PrivKey resolvePrivKey(byte[] privKeyBytes) {
		if (supportPrivKey(privKeyBytes)) {
			return DefaultCryptoEncoding.createPrivKey(SM3WITHSM2.code(), privKeyBytes);
		} else {
			throw new CryptoException("privKeyBytes are invalid!");
		}
	}

	@Override
	public PubKey retrievePubKey(PrivKey privKey) {

		byte[] rawPrivKeyBytes = privKey.getRawKeyBytes();

		if (rawPrivKeyBytes.length < RAW_PRIVKEY_SIZE) {
			throw new CryptoException("This key has wrong format!");
		}

		if (privKey.getAlgorithm() != SM3WITHSM2.code()) {
			throw new CryptoException("This key is not SM3WITHSM2 private key!");
		}

		PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(rawPrivKeyBytes);

		KeyFactory keyFactory;
		ECPrivateKey rawPrivKey;
		byte[] rawPubKeyBytes;
		try {
			keyFactory = KeyFactory.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
			rawPrivKey = (ECPrivateKey) keyFactory.generatePrivate(keySpec);
			BigInteger d = rawPrivKey.getS();
			ECPoint Q = G.multiply(d).normalize();
			rawPubKeyBytes = KeyUtil.getEncodedSubjectPublicKeyInfo(SM2_ALGORITHM_IDENTIFIER, Q.getEncoded(false));
		} catch (NoSuchAlgorithmException | InvalidKeySpecException | NoSuchProviderException e) {
			throw new CryptoException(e.getMessage(), e);
		}

		return DefaultCryptoEncoding.encodePubKey(SM3WITHSM2, rawPubKeyBytes);
	}

	@Override
	public boolean supportDigest(byte[] digestBytes) {
		return digestBytes.length > (RAW_SIGNATUREDIGEST_SIZE + CryptoAlgorithm.CODE_SIZE)
				&& CryptoAlgorithm.match(SM3WITHSM2, digestBytes);
	}

	@Override
	public SignatureDigest resolveDigest(byte[] digestBytes) {
		if (supportDigest(digestBytes)) {
			return DefaultCryptoEncoding.createSignatureDigest(SM3WITHSM2.code(), digestBytes);
		} else {
			throw new CryptoException("digestBytes are invalid!");
		}
	}

	@Override
	public AsymmetricKeypair generateKeypair() {
		return generateKeypair(new SecureRandom());
	}

	@Override
	public AsymmetricKeypair generateKeypair(byte[] seed) {
		return generateKeypair(new SM3SecureRandom(seed));
	}

	public AsymmetricKeypair generateKeypair(SecureRandom random) {
		Security.addProvider(new BouncyCastleProvider());
		KeyPairGenerator generator;
		PublicKey publicKey;
		PrivateKey privateKey;
		try {
			generator = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
			generator.initialize(new ECNamedCurveGenParameterSpec("sm2p256v1"), random);
			KeyPair keyPair = generator.generateKeyPair();
			publicKey = keyPair.getPublic();
			privateKey = keyPair.getPrivate();
		} catch (NoSuchAlgorithmException | NoSuchProviderException | InvalidAlgorithmParameterException e) {
			throw new CryptoException(e.getMessage(), e);
		}

		byte[] pubKeyBytes = publicKey.getEncoded();
		byte[] privKeyBytes = privateKey.getEncoded();

		PubKey pubKey = DefaultCryptoEncoding.encodePubKey(SM3WITHSM2, pubKeyBytes);
		PrivKey privKey = DefaultCryptoEncoding.encodePrivKey(SM3WITHSM2, privKeyBytes);

		return new AsymmetricKeypair(pubKey, privKey);
	}

	@Override
	public CryptoAlgorithm getAlgorithm() {
		return SM3WITHSM2;
	}

	@Override
	public <T extends CryptoBytes> boolean support(Class<T> cryptoDataType, byte[] encodedCryptoBytes) {
		return (SignatureDigest.class == cryptoDataType && supportDigest(encodedCryptoBytes))
				|| (PubKey.class == cryptoDataType && supportPubKey(encodedCryptoBytes))
				|| (PrivKey.class == cryptoDataType && supportPrivKey(encodedCryptoBytes));
	}

}
