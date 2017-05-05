package net.corda.core.crypto

import net.corda.core.random63BitValue
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAKey
import net.i2p.crypto.eddsa.EdDSASecurityProvider
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.bc.BCObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.cert.bc.BcX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jcajce.provider.util.AsymmetricKeyInfoConverter
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.interfaces.ECKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import org.bouncycastle.pqc.jcajce.provider.BouncyCastlePQCProvider
import org.bouncycastle.pqc.jcajce.spec.SPHINCS256KeyGenParameterSpec
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.*

/**
 * This object controls and provides the available and supported signature schemes for Corda.
 * Any implemented [SignatureScheme] should be strictly defined here.
 * However, only the schemes returned by {@link #listSupportedSignatureSchemes()} are supported.
 * Note that Corda currently supports the following signature schemes by their code names:
 * <p><ul>
 * <li>RSA_SHA256 (RSA using SHA256 as hash algorithm and MGF1 (with SHA256) as mask generation function).
 * <li>ECDSA_SECP256K1_SHA256 (ECDSA using the secp256k1 Koblitz curve and SHA256 as hash algorithm).
 * <li>ECDSA_SECP256R1_SHA256 (ECDSA using the secp256r1 (NIST P-256) curve and SHA256 as hash algorithm).
 * <li>EDDSA_ED25519_SHA512 (EdDSA using the ed255519 twisted Edwards curve and SHA512 as hash algorithm).
 * <li>SPHINCS256_SHA512 (SPHINCS-256 hash-based signature scheme using SHA512 as hash algorithm).
 * </ul>
 */
object Crypto {
    /**
     * RSA_SHA256 signature scheme using SHA256 as hash algorithm and MGF1 (with SHA256) as mask generation function.
     * Note: Recommended key size >= 3072 bits.
     */
    val RSA_SHA256 = SignatureScheme(
            1,
            "RSA_SHA256",
            PKCSObjectIdentifiers.id_RSASSA_PSS,
            BouncyCastleProvider.PROVIDER_NAME,
            "RSA",
            "SHA256WITHRSAANDMGF1",
            null,
            3072,
            "RSA_SHA256 signature scheme using SHA256 as hash algorithm and MGF1 (with SHA256) as mask generation function."
    )

    /** ECDSA signature scheme using the secp256k1 Koblitz curve. */
    val ECDSA_SECP256K1_SHA256 = SignatureScheme(
            2,
            "ECDSA_SECP256K1_SHA256",
            X9ObjectIdentifiers.ecdsa_with_SHA256,
            BouncyCastleProvider.PROVIDER_NAME,
            "ECDSA",
            "SHA256withECDSA",
            ECNamedCurveTable.getParameterSpec("secp256k1"),
            256,
            "ECDSA signature scheme using the secp256k1 Koblitz curve."
    )

    /** ECDSA signature scheme using the secp256r1 (NIST P-256) curve. */
    val ECDSA_SECP256R1_SHA256 = SignatureScheme(
            3,
            "ECDSA_SECP256R1_SHA256",
            X9ObjectIdentifiers.ecdsa_with_SHA256,
            BouncyCastleProvider.PROVIDER_NAME,
            "ECDSA",
            "SHA256withECDSA",
            ECNamedCurveTable.getParameterSpec("secp256r1"),
            256,
            "ECDSA signature scheme using the secp256r1 (NIST P-256) curve."
    )

    /** EdDSA signature scheme using the ed255519 twisted Edwards curve. */
    val EDDSA_ED25519_SHA512 = SignatureScheme(
            4,
            "EDDSA_ED25519_SHA512",
            ASN1ObjectIdentifier("1.3.101.112"),
            // We added EdDSA to bouncy castle for certificate signing.
            BouncyCastleProvider.PROVIDER_NAME,
            EdDSAKey.KEY_ALGORITHM,
            EdDSAEngine.SIGNATURE_ALGORITHM,
            EdDSANamedCurveTable.getByName("ED25519"),
            256,
            "EdDSA signature scheme using the ed25519 twisted Edwards curve."
    )

    /**
     * SPHINCS-256 hash-based signature scheme. It provides 128bit security against post-quantum attackers
     * at the cost of larger key sizes and loss of compatibility.
     */
    val SPHINCS256_SHA256 = SignatureScheme(
            5,
            "SPHINCS-256_SHA512",
            BCObjectIdentifiers.sphincs256_with_SHA512,
            "BCPQC",
            "SPHINCS256",
            "SHA512WITHSPHINCS256",
            SPHINCS256KeyGenParameterSpec(SPHINCS256KeyGenParameterSpec.SHA512_256),
            256,
            "SPHINCS-256 hash-based signature scheme. It provides 128bit security against post-quantum attackers " +
                    "at the cost of larger key sizes and loss of compatibility."
    )

    /** Our default signature scheme if no algorithm is specified (e.g. for key generation). */
    val DEFAULT_SIGNATURE_SCHEME = EDDSA_ED25519_SHA512

    /**
     * Supported digital signature schemes.
     * Note: Only the schemes added in this map will be supported (see [Crypto]).
     */
    val supportedSignatureSchemes = listOf(
            RSA_SHA256,
            ECDSA_SECP256K1_SHA256,
            ECDSA_SECP256R1_SHA256,
            EDDSA_ED25519_SHA512,
            SPHINCS256_SHA256
    ).associateBy { it.schemeCodeName }

    // This map is required to defend against users that forcibly call Security.addProvider / Security.removeProvider
    // that could cause unexpected and suspicious behaviour.
    // i.e. if someone removes a Provider and then he/she adds a new one with the same name.
    // The val is private to avoid any harmful state changes.
    private val providerMap: Map<String, Provider> = mapOf(
            BouncyCastleProvider.PROVIDER_NAME to getBouncyCastleProvider(),
            "BCPQC" to BouncyCastlePQCProvider()) // unfortunately, provider's name is not final in BouncyCastlePQCProvider, so we explicitly set it.

    private fun getBouncyCastleProvider() = BouncyCastleProvider().apply {
        putAll(EdDSASecurityProvider())
        addKeyInfoConverter(EDDSA_ED25519_SHA512.signatureOID, KeyInfoConverter(EDDSA_ED25519_SHA512))
    }

    init {
        // This registration is needed for reading back EdDSA key from java keystore.
        // TODO: Find a way to make JSK work with bouncy castle provider or implement our own provide so we don't have to register bouncy castle provider.
        Security.addProvider(getBouncyCastleProvider())
    }

    /**
     * Factory pattern to retrieve the corresponding [SignatureScheme] based on the type of the [String] input.
     * This function is usually called by key generators and verify signature functions.
     * In case the input is not a key in the supportedSignatureSchemes map, null will be returned.
     * @param schemeCodeName a [String] that should match a supported signature scheme code name (e.g. ECDSA_SECP256K1_SHA256), see [Crypto].
     * @return a currently supported SignatureScheme.
     * @throws IllegalArgumentException if the requested signature scheme is not supported.
     */
    fun findSignatureScheme(schemeCodeName: String): SignatureScheme = supportedSignatureSchemes[schemeCodeName] ?: throw IllegalArgumentException("Unsupported key/algorithm for metadata schemeCodeName: $schemeCodeName")

    /**
     * Retrieve the corresponding [SignatureScheme] based on the type of the input [Key].
     * This function is usually called when requiring to verify signatures and the signing schemes must be defined.
     * Note that only the Corda platform standard schemes are supported (see [Crypto]).
     * Note that we always need to add an additional if-else statement when there are signature schemes
     * with the same algorithmName, but with different parameters (e.g. now there are two ECDSA schemes, each using its own curve).
     * @param key either private or public.
     * @return a currently supported SignatureScheme.
     * @throws IllegalArgumentException if the requested key type is not supported.
     */
    fun findSignatureScheme(key: Key): SignatureScheme {
        for (sig in supportedSignatureSchemes.values) {
            var algorithm = key.algorithm
            if (algorithm == "EC") algorithm = "ECDSA" // required to read ECC keys from Keystore, because encoding may change algorithm name from ECDSA to EC.
            if (algorithm == "SPHINCS-256") algorithm = "SPHINCS256" // because encoding may change algorithm name from SPHINCS256 to SPHINCS-256.
            if (algorithm == sig.algorithmName) {
                // If more than one ECDSA schemes are supported, we should distinguish between them by checking their curve parameters.
                // TODO: change 'continue' to 'break' if only one EdDSA curve will be used.
                if (algorithm == "EdDSA") {
                    if ((key as EdDSAKey).params == sig.algSpec) {
                        return sig
                    } else continue
                } else if (algorithm == "ECDSA") {
                    if ((key as ECKey).parameters == sig.algSpec) {
                        return sig
                    } else continue
                } else return sig // it's either RSA_SHA256 or SPHINCS-256.
            }
        }
        throw IllegalArgumentException("Unsupported key/algorithm for the private key: ${key.encoded.toBase58()}")
    }

    /**
     * Decode a PKCS8 encoded key to its [PrivateKey] object.
     * Use this method if the key type is a-priori unknown.
     * @param encodedKey a PKCS8 encoded private key.
     * @throws IllegalArgumentException on not supported scheme or if the given key specification
     * is inappropriate for this key factory to produce a private key.
     */
    @Throws(IllegalArgumentException::class)
    fun decodePrivateKey(encodedKey: ByteArray): PrivateKey {
        for ((_, _, _, providerName, algorithmName) in supportedSignatureSchemes.values) {
            try {
                return KeyFactory.getInstance(algorithmName, providerMap[providerName]).generatePrivate(PKCS8EncodedKeySpec(encodedKey))
            } catch (ikse: InvalidKeySpecException) {
                // ignore it - only used to bypass the scheme that causes an exception.
            }
        }
        throw IllegalArgumentException("This private key cannot be decoded, please ensure it is PKCS8 encoded and the signature scheme is supported.")
    }

    /**
     * Decode a PKCS8 encoded key to its [PrivateKey] object based on the input scheme code name.
     * This should be used when the type key is known, e.g. during Kryo deserialisation or with key caches or key managers.
     * @param schemeCodeName a [String] that should match a key in supportedSignatureSchemes map (e.g. ECDSA_SECP256K1_SHA256).
     * @param encodedKey a PKCS8 encoded private key.
     * @throws IllegalArgumentException on not supported scheme or if the given key specification
     * is inappropriate for this key factory to produce a private key.
     */
    @Throws(IllegalArgumentException::class, InvalidKeySpecException::class)
    fun decodePrivateKey(schemeCodeName: String, encodedKey: ByteArray): PrivateKey = decodePrivateKey(findSignatureScheme(schemeCodeName), encodedKey)

    /**
     * Decode a PKCS8 encoded key to its [PrivateKey] object based on the input scheme code name.
     * This should be used when the type key is known, e.g. during Kryo deserialisation or with key caches or key managers.
     * @param signatureScheme a signature scheme (e.g. ECDSA_SECP256K1_SHA256).
     * @param encodedKey a PKCS8 encoded private key.
     * @throws IllegalArgumentException on not supported scheme or if the given key specification
     * is inappropriate for this key factory to produce a private key.
     */
    @Throws(IllegalArgumentException::class, InvalidKeySpecException::class)
    fun decodePrivateKey(signatureScheme: SignatureScheme, encodedKey: ByteArray): PrivateKey {
        try {
            return KeyFactory.getInstance(signatureScheme.algorithmName, providerMap[signatureScheme.providerName]).generatePrivate(PKCS8EncodedKeySpec(encodedKey))
        } catch (ikse: InvalidKeySpecException) {
            throw InvalidKeySpecException("This private key cannot be decoded, please ensure it is PKCS8 encoded and that it corresponds to the input scheme's code name.", ikse)
        }
    }

    /**
     * Decode an X509 encoded key to its [PublicKey] object.
     * Use this method if the key type is a-priori unknown.
     * @param encodedKey an X509 encoded public key.
     * @throws IllegalArgumentException on not supported scheme or if the given key specification
     * is inappropriate for this key factory to produce a private key.
     */
    @Throws(IllegalArgumentException::class)
    fun decodePublicKey(encodedKey: ByteArray): PublicKey {
        for ((_, _, _, providerName, algorithmName) in supportedSignatureSchemes.values) {
            try {
                return KeyFactory.getInstance(algorithmName, providerMap[providerName]).generatePublic(X509EncodedKeySpec(encodedKey))
            } catch (ikse: InvalidKeySpecException) {
                // ignore it - only used to bypass the scheme that causes an exception.
            }
        }
        throw IllegalArgumentException("This public key cannot be decoded, please ensure it is X509 encoded and the signature scheme is supported.")
    }

    /**
     * Decode an X509 encoded key to its [PrivateKey] object based on the input scheme code name.
     * This should be used when the type key is known, e.g. during Kryo deserialisation or with key caches or key managers.
     * @param schemeCodeName a [String] that should match a key in supportedSignatureSchemes map (e.g. ECDSA_SECP256K1_SHA256).
     * @param encodedKey an X509 encoded public key.
     * @throws IllegalArgumentException if the requested scheme is not supported
     * @throws InvalidKeySpecException if the given key specification
     * is inappropriate for this key factory to produce a public key.
     */
    @Throws(IllegalArgumentException::class, InvalidKeySpecException::class)
    fun decodePublicKey(schemeCodeName: String, encodedKey: ByteArray): PublicKey = decodePublicKey(findSignatureScheme(schemeCodeName), encodedKey)

    /**
     * Decode an X509 encoded key to its [PrivateKey] object based on the input scheme code name.
     * This should be used when the type key is known, e.g. during Kryo deserialisation or with key caches or key managers.
     * @param signatureScheme a signature scheme (e.g. ECDSA_SECP256K1_SHA256).
     * @param encodedKey an X509 encoded public key.
     * @throws IllegalArgumentException if the requested scheme is not supported
     * @throws InvalidKeySpecException if the given key specification
     * is inappropriate for this key factory to produce a public key.
     */
    @Throws(IllegalArgumentException::class, InvalidKeySpecException::class)
    fun decodePublicKey(signatureScheme: SignatureScheme, encodedKey: ByteArray): PublicKey {
        try {
            return KeyFactory.getInstance(signatureScheme.algorithmName, providerMap[signatureScheme.providerName]).generatePublic(X509EncodedKeySpec(encodedKey))
        } catch (ikse: InvalidKeySpecException) {
            throw throw InvalidKeySpecException("This public key cannot be decoded, please ensure it is X509 encoded and that it corresponds to the input scheme's code name.", ikse)
        }
    }

    /**
     * Generic way to sign [ByteArray] data with a [PrivateKey]. Strategy on on identifying the actual signing scheme is based
     * on the [PrivateKey] type, but if the schemeCodeName is known, then better use doSign(signatureScheme: String, privateKey: PrivateKey, clearData: ByteArray).
     * @param privateKey the signer's [PrivateKey].
     * @param clearData the data/message to be signed in [ByteArray] form (usually the Merkle root).
     * @return the digital signature (in [ByteArray]) on the input message.
     * @throws IllegalArgumentException if the signature scheme is not supported for this private key.
     * @throws InvalidKeyException if the private key is invalid.
     * @throws SignatureException if signing is not possible due to malformed data or private key.
     */
    @Throws(IllegalArgumentException::class, InvalidKeyException::class, SignatureException::class)
    fun doSign(privateKey: PrivateKey, clearData: ByteArray) = doSign(findSignatureScheme(privateKey), privateKey, clearData)

    /**
     * Generic way to sign [ByteArray] data with a [PrivateKey] and a known schemeCodeName [String].
     * @param schemeCodeName a signature scheme's code name (e.g. ECDSA_SECP256K1_SHA256).
     * @param privateKey the signer's [PrivateKey].
     * @param clearData the data/message to be signed in [ByteArray] form (usually the Merkle root).
     * @return the digital signature (in [ByteArray]) on the input message.
     * @throws IllegalArgumentException if the signature scheme is not supported.
     * @throws InvalidKeyException if the private key is invalid.
     * @throws SignatureException if signing is not possible due to malformed data or private key.
     */
    @Throws(IllegalArgumentException::class, InvalidKeyException::class, SignatureException::class)
    fun doSign(schemeCodeName: String, privateKey: PrivateKey, clearData: ByteArray) = doSign(findSignatureScheme(schemeCodeName), privateKey, clearData)

    /**
     * Generic way to sign [ByteArray] data with a [PrivateKey] and a known [Signature].
     * @param signatureScheme a [SignatureScheme] object, retrieved from supported signature schemes, see [Crypto].
     * @param privateKey the signer's [PrivateKey].
     * @param clearData the data/message to be signed in [ByteArray] form (usually the Merkle root).
     * @return the digital signature (in [ByteArray]) on the input message.
     * @throws IllegalArgumentException if the signature scheme is not supported for this private key.
     * @throws InvalidKeyException if the private key is invalid.
     * @throws SignatureException if signing is not possible due to malformed data or private key.
     */
    @Throws(IllegalArgumentException::class, InvalidKeyException::class, SignatureException::class)
    fun doSign(signatureScheme: SignatureScheme, privateKey: PrivateKey, clearData: ByteArray): ByteArray {
        if (!supportedSignatureSchemes.containsKey(signatureScheme.schemeCodeName))
            throw IllegalArgumentException("Unsupported key/algorithm for schemeCodeName: $signatureScheme.schemeCodeName")
        val signature = Signature.getInstance(signatureScheme.signatureName, providerMap[signatureScheme.providerName])
        if (clearData.isEmpty()) throw Exception("Signing of an empty array is not permitted!")
        signature.initSign(privateKey)
        signature.update(clearData)
        return signature.sign()
    }

    /**
     * Generic way to sign [MetaData] objects with a [PrivateKey].
     * [MetaData] is a wrapper over the transaction's Merkle root in order to attach extra information, such as a timestamp or partial and blind signature indicators.
     * @param privateKey the signer's [PrivateKey].
     * @param metaData a [MetaData] object that adds extra information to a transaction.
     * @return a [TransactionSignature] object than contains the output of a successful signing and the metaData.
     * @throws IllegalArgumentException if the signature scheme is not supported for this private key or
     * if metaData.schemeCodeName is not aligned with key type.
     * @throws InvalidKeyException if the private key is invalid.
     * @throws SignatureException if signing is not possible due to malformed data or private key.
     */
    @Throws(IllegalArgumentException::class, InvalidKeyException::class, SignatureException::class)
    fun doSign(privateKey: PrivateKey, metaData: MetaData): TransactionSignature {
        val sigKey: SignatureScheme = findSignatureScheme(privateKey)
        val sigMetaData: SignatureScheme = findSignatureScheme(metaData.schemeCodeName)
        if (sigKey != sigMetaData) throw IllegalArgumentException("Metadata schemeCodeName: ${metaData.schemeCodeName} is not aligned with the key type.")
        val signatureData = doSign(sigKey.schemeCodeName, privateKey, metaData.bytes())
        return TransactionSignature(signatureData, metaData)
    }

    /**
     * Utility to simplify the act of verifying a digital signature.
     * It returns true if it succeeds, but it always throws an exception if verification fails.
     * @param schemeCodeName a signature scheme's code name (e.g. ECDSA_SECP256K1_SHA256).
     * @param publicKey the signer's [PublicKey].
     * @param signatureData the signatureData on a message.
     * @param clearData the clear data/message that was signed (usually the Merkle root).
     * @return true if verification passes or throws an exception if verification fails.
     * @throws InvalidKeyException if the key is invalid.
     * @throws SignatureException if this signatureData object is not initialized properly,
     * the passed-in signatureData is improperly encoded or of the wrong type,
     * if this signatureData scheme is unable to process the input data provided, if the verification is not possible.
     * @throws IllegalArgumentException if the signature scheme is not supported or if any of the clear or signature data is empty.
     */
    @Throws(InvalidKeyException::class, SignatureException::class, IllegalArgumentException::class)
    fun doVerify(schemeCodeName: String, publicKey: PublicKey, signatureData: ByteArray, clearData: ByteArray) = doVerify(findSignatureScheme(schemeCodeName), publicKey, signatureData, clearData)

    /**
     * Utility to simplify the act of verifying a digital signature by identifying the signature scheme used from the input public key's type.
     * It returns true if it succeeds, but it always throws an exception if verification fails.
     * Strategy on identifying the actual signing scheme is based on the [PublicKey] type, but if the schemeCodeName is known,
     * then better use doVerify(schemeCodeName: String, publicKey: PublicKey, signatureData: ByteArray, clearData: ByteArray).
     * @param publicKey the signer's [PublicKey].
     * @param signatureData the signatureData on a message.
     * @param clearData the clear data/message that was signed (usually the Merkle root).
     * @return true if verification passes or throws an exception if verification fails.
     * @throws InvalidKeyException if the key is invalid.
     * @throws SignatureException if this signatureData object is not initialized properly,
     * the passed-in signatureData is improperly encoded or of the wrong type,
     * if this signatureData scheme is unable to process the input data provided, if the verification is not possible.
     * @throws IllegalArgumentException if the signature scheme is not supported or if any of the clear or signature data is empty.
     */
    @Throws(InvalidKeyException::class, SignatureException::class, IllegalArgumentException::class)
    fun doVerify(publicKey: PublicKey, signatureData: ByteArray, clearData: ByteArray) = doVerify(findSignatureScheme(publicKey), publicKey, signatureData, clearData)

    /**
     * Method to verify a digital signature.
     * It returns true if it succeeds, but it always throws an exception if verification fails.
     * @param signatureScheme a [SignatureScheme] object, retrieved from supported signature schemes, see [Crypto].
     * @param publicKey the signer's [PublicKey].
     * @param signatureData the signatureData on a message.
     * @param clearData the clear data/message that was signed (usually the Merkle root).
     * @return true if verification passes or throws an exception if verification fails.
     * @throws InvalidKeyException if the key is invalid.
     * @throws SignatureException if this signatureData object is not initialized properly,
     * the passed-in signatureData is improperly encoded or of the wrong type,
     * if this signatureData scheme is unable to process the input data provided, if the verification is not possible.
     * @throws IllegalArgumentException if the signature scheme is not supported or if any of the clear or signature data is empty.
     */
    @Throws(InvalidKeyException::class, SignatureException::class, IllegalArgumentException::class)
    fun doVerify(signatureScheme: SignatureScheme, publicKey: PublicKey, signatureData: ByteArray, clearData: ByteArray): Boolean {
        if (!supportedSignatureSchemes.containsKey(signatureScheme.schemeCodeName))
            throw IllegalArgumentException("Unsupported key/algorithm for schemeCodeName: $signatureScheme.schemeCodeName")
        if (signatureData.isEmpty()) throw IllegalArgumentException("Signature data is empty!")
        if (clearData.isEmpty()) throw IllegalArgumentException("Clear data is empty, nothing to verify!")
        val verificationResult = isValid(signatureScheme, publicKey, signatureData, clearData)
        if (verificationResult) {
            return true
        } else {
            throw SignatureException("Signature Verification failed!")
        }
    }

    /**
     * Utility to simplify the act of verifying a [TransactionSignature].
     * It returns true if it succeeds, but it always throws an exception if verification fails.
     * @param publicKey the signer's [PublicKey].
     * @param transactionSignature the signatureData on a message.
     * @return true if verification passes or throws an exception if verification fails.
     * @throws InvalidKeyException if the key is invalid.
     * @throws SignatureException if this signatureData object is not initialized properly,
     * the passed-in signatureData is improperly encoded or of the wrong type,
     * if this signatureData scheme is unable to process the input data provided, if the verification is not possible.
     * @throws IllegalArgumentException if the signature scheme is not supported or if any of the clear or signature data is empty.
     */
    @Throws(InvalidKeyException::class, SignatureException::class, IllegalArgumentException::class)
    fun doVerify(publicKey: PublicKey, transactionSignature: TransactionSignature): Boolean {
        if (publicKey != transactionSignature.metaData.publicKey) IllegalArgumentException("MetaData's publicKey: ${transactionSignature.metaData.publicKey.encoded.toBase58()} does not match the input clearData: ${publicKey.encoded.toBase58()}")
        return Crypto.doVerify(publicKey, transactionSignature.signatureData, transactionSignature.metaData.bytes())
    }

    /**
     * Utility to simplify the act of verifying a digital signature by identifying the signature scheme used from the input public key's type.
     * It returns true if it succeeds and false if not. In comparison to [doVerify] if the key and signature
     * do not match it returns false rather than throwing an exception. Normally you should use the function which throws,
     * as it avoids the risk of failing to test the result.
     * Use this method if the signature scheme is not a-priori known.
     * @param publicKey the signer's [PublicKey].
     * @param signatureData the signatureData on a message.
     * @param clearData the clear data/message that was signed (usually the Merkle root).
     * @return true if verification passes or false if verification fails.
     * @throws SignatureException if this signatureData object is not initialized properly,
     * the passed-in signatureData is improperly encoded or of the wrong type,
     * if this signatureData scheme is unable to process the input data provided, if the verification is not possible.
     */
    @Throws(SignatureException::class)
    fun isValid(publicKey: PublicKey, signatureData: ByteArray, clearData: ByteArray) = isValid(findSignatureScheme(publicKey), publicKey, signatureData, clearData)

    /**
     * Method to verify a digital signature. In comparison to [doVerify] if the key and signature
     * do not match it returns false rather than throwing an exception.
     * Use this method if the signature scheme type is a-priori unknown.
     * @param signatureScheme a [SignatureScheme] object, retrieved from supported signature schemes, see [Crypto].
     * @param publicKey the signer's [PublicKey].
     * @param signatureData the signatureData on a message.
     * @param clearData the clear data/message that was signed (usually the Merkle root).
     * @return true if verification passes or false if verification fails.
     * @throws SignatureException if this signatureData object is not initialized properly,
     * the passed-in signatureData is improperly encoded or of the wrong type,
     * if this signatureData scheme is unable to process the input data provided, if the verification is not possible.
     * @throws IllegalArgumentException if the requested signature scheme is not supported.
     */
    @Throws(SignatureException::class, IllegalArgumentException::class)
    fun isValid(signatureScheme: SignatureScheme, publicKey: PublicKey, signatureData: ByteArray, clearData: ByteArray): Boolean {
        if (!supportedSignatureSchemes.containsKey(signatureScheme.schemeCodeName))
            throw IllegalArgumentException("Unsupported key/algorithm for schemeCodeName: $signatureScheme.schemeCodeName")
        val signature = Signature.getInstance(signatureScheme.signatureName, providerMap[signatureScheme.providerName])
        signature.initVerify(publicKey)
        signature.update(clearData)
        return signature.verify(signatureData)
    }

    /**
     * Utility to simplify the act of generating keys.
     * Normally, we don't expect other errors here, assuming that key generation parameters for every supported signature scheme have been unit-tested.
     * @param schemeCodeName a signature scheme's code name (e.g. ECDSA_SECP256K1_SHA256).
     * @return a KeyPair for the requested signature scheme code name.
     * @throws IllegalArgumentException if the requested signature scheme is not supported.
     */
    @Throws(IllegalArgumentException::class)
    fun generateKeyPair(schemeCodeName: String): KeyPair = generateKeyPair(findSignatureScheme(schemeCodeName))

    /**
     * Generate a [KeyPair] for the selected [SignatureScheme].
     * Note that RSA is the sole algorithm initialized specifically by its supported keySize.
     * @param signatureScheme a supported [SignatureScheme], see [Crypto], default to [DEFAULT_SIGNATURE_SCHEME] if not provided.
     * @return a new [KeyPair] for the requested [SignatureScheme].
     * @throws IllegalArgumentException if the requested signature scheme is not supported.
     */
    @Throws(IllegalArgumentException::class)
    @JvmOverloads
    fun generateKeyPair(signatureScheme: SignatureScheme = DEFAULT_SIGNATURE_SCHEME): KeyPair {
        if (!supportedSignatureSchemes.containsKey(signatureScheme.schemeCodeName))
            throw IllegalArgumentException("Unsupported key/algorithm for schemeCodeName: $signatureScheme.schemeCodeName")
        val keyPairGenerator = KeyPairGenerator.getInstance(signatureScheme.algorithmName, providerMap[signatureScheme.providerName])
        if (signatureScheme.algSpec != null)
            keyPairGenerator.initialize(signatureScheme.algSpec, newSecureRandom())
        else
            keyPairGenerator.initialize(signatureScheme.keySize, newSecureRandom())
        return keyPairGenerator.generateKeyPair()
    }

    /** Check if the requested signature scheme is supported by the system. */
    fun isSupportedSignatureScheme(schemeCodeName: String): Boolean = schemeCodeName in supportedSignatureSchemes

    fun isSupportedSignatureScheme(signatureScheme: SignatureScheme): Boolean = signatureScheme.schemeCodeName in supportedSignatureSchemes

    /**
     * Use bouncy castle utilities to sign completed X509 certificate with CA cert private key
     */
    fun createCertificate(issuer: X500Name, issuerKeyPair: KeyPair,
                          subject: X500Name, subjectPublicKey: PublicKey,
                          keyUsage: KeyUsage, purposes: List<KeyPurposeId>,
                          signatureScheme: SignatureScheme, validityWindow: Pair<Date, Date>,
                          pathLength: Int? = null, subjectAlternativeName: List<GeneralName>? = null): X509Certificate {

        val provider = providerMap[signatureScheme.providerName]
        val serial = BigInteger.valueOf(random63BitValue())
        val keyPurposes = DERSequence(ASN1EncodableVector().apply { purposes.forEach { add(it) } })

        val builder = JcaX509v3CertificateBuilder(issuer, serial, validityWindow.first, validityWindow.second, subject, subjectPublicKey)
                .addExtension(Extension.subjectKeyIdentifier, false, BcX509ExtensionUtils().createSubjectKeyIdentifier(SubjectPublicKeyInfo.getInstance(subjectPublicKey.encoded)))
                .addExtension(Extension.basicConstraints, pathLength != null, if (pathLength == null) BasicConstraints(false) else BasicConstraints(pathLength))
                .addExtension(Extension.keyUsage, false, keyUsage)
                .addExtension(Extension.extendedKeyUsage, false, keyPurposes)

        if (subjectAlternativeName != null && subjectAlternativeName.isNotEmpty()) {
            builder.addExtension(Extension.subjectAlternativeName, false, DERSequence(subjectAlternativeName.toTypedArray()))
        }
        val signer = ContentSignerBuilder.build(signatureScheme, issuerKeyPair.private, provider)
        return JcaX509CertificateConverter().setProvider(provider).getCertificate(builder.build(signer)).apply {
            checkValidity(Date())
            verify(issuerKeyPair.public, provider)
        }
    }

    /**
     * Create certificate signing request using provided information.
     */
    fun createCertificateSigningRequest(subject: X500Name, keyPair: KeyPair, signatureScheme: SignatureScheme): PKCS10CertificationRequest {
        val signer = ContentSignerBuilder.build(signatureScheme, keyPair.private, providerMap[signatureScheme.providerName])
        return JcaPKCS10CertificationRequestBuilder(subject, keyPair.public).build(signer)
    }

    private class KeyInfoConverter(val signatureScheme: SignatureScheme) : AsymmetricKeyInfoConverter {
        override fun generatePublic(keyInfo: SubjectPublicKeyInfo?): PublicKey? = keyInfo?.let { decodePublicKey(signatureScheme, it.encoded) }
        override fun generatePrivate(keyInfo: PrivateKeyInfo?): PrivateKey? = keyInfo?.let { decodePrivateKey(signatureScheme, it.encoded) }
    }
}
