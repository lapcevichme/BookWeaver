package com.lapcevichme.bookweaverdesktop.util

import mu.KotlinLogging
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNamesBuilder
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.security.*
import java.security.cert.X509Certificate
import java.security.spec.KeySpec
import java.util.*
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private val logger = KotlinLogging.logger {}

object SecurityUtils {
    private const val CERT_VALIDITY_DAYS = 365
    private const val KEY_ALIAS = "bookweaver"
    private const val ENCRYPTED_PASSWORD_FILE = "keystore_encrypted.dat"  // Binary файл для зашифрованного пароля
    private const val PBKDF2_ITERATIONS = 100_000  // Итерации для PBKDF2 (безопасность)
    private const val AES_KEY_SIZE = 256  // AES-256
    private const val GCM_IV_SIZE = 12  // Стандарт для GCM
    private const val GCM_TAG_SIZE = 128  // Аутентификация

    /**
     * Загружает существующий KeyStore или создает новый.
     * Пароль зашифрован с master key, derived от machine fingerprint.
     *
     * @return Triple<KeyStore, fingerprint, password> — пароль (CharArray) нужно использовать в Ktor и затем очистить.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun setupCertificate(keyStoreFileName: String): Triple<KeyStore, String, CharArray> {
        val keyStoreFile = File(keyStoreFileName)
        val encryptedPasswordFile = File(ENCRYPTED_PASSWORD_FILE)
        val keyStore = KeyStore.getInstance("BKS", "BC")

        val (keystorePassword, isNew) = if (keyStoreFile.exists() && encryptedPasswordFile.exists()) {
            logger.info { "Loading existing keystore with decrypted password." }
            try {
                val encryptedData = encryptedPasswordFile.readBytes()
                val passwordStr = decryptPassword(encryptedData)
                val passwordChars = passwordStr.toCharArray()
                keyStoreFile.inputStream().use {
                    keyStore.load(it, passwordChars)
                }
                Pair(passwordChars, false)
            } catch (e: Exception) {
                logger.warn { "Failed to decrypt password: ${e.message}. Regenerating..." }
                generateNewKeystoreAndPassword(keyStore, keyStoreFileName)
            }
        } else {
            logger.info { "No existing keystore or encrypted password found. Generating new ones..." }
            generateNewKeystoreAndPassword(keyStore, keyStoreFileName)
        }

        val cert = keyStore.getCertificate(KEY_ALIAS) as X509Certificate
        val fingerprint = calculateFingerprint(cert)
        if (isNew) {
            logger.info { "New certificate fingerprint: $fingerprint (QR scan required)" }
        }
        return Triple(keyStore, fingerprint, keystorePassword)
    }

    private fun generateNewKeystoreAndPassword(keyStore: KeyStore, keyStoreFileName: String): Pair<CharArray, Boolean> {
        val passwordStr = generateSecurePassword()
        val passwordChars = passwordStr.toCharArray()
        val encryptedData = encryptPassword(passwordChars)
        File(ENCRYPTED_PASSWORD_FILE).writeBytes(encryptedData)

        val keyPairGenerator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val keyPair = keyPairGenerator.generateKeyPair()
        val cert = generateCertificate(keyPair)

        keyStore.load(null, passwordChars)
        keyStore.setKeyEntry(KEY_ALIAS, keyPair.private, passwordChars, arrayOf(cert))

        File(keyStoreFileName).outputStream().use {
            keyStore.store(it, passwordChars)
        }
        logger.info { "New keystore and encrypted password saved." }
        return Pair(passwordChars, true)
    }

    private fun generateSecurePassword(): String {
        val secureRandom = SecureRandom()
        val passwordBytes = ByteArray(32)
        secureRandom.nextBytes(passwordBytes)
        return Base64.encode(passwordBytes)
    }

    private fun deriveMasterKey(): SecretKey {
        val fingerprint = getMachineFingerprint()
        val salt = "bookweaver_salt".toByteArray()
        val keySpec: KeySpec = PBEKeySpec(fingerprint.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_SIZE)
        val keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = keyFactory.generateSecret(keySpec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun getMachineFingerprint(): String {
        val hostname = try {
            InetAddress.getLocalHost().hostName
        } catch (e: Exception) {
            "unknown"
        }
        val primaryMac = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { !it.isLoopback && it.isUp }
            .firstOrNull()
            ?.hardwareAddress
            ?.joinToString("") { "%02x".format(it) }
            ?: "no_mac"
        return "$hostname-$primaryMac"
    }

    private fun encryptPassword(password: CharArray): ByteArray {
        val masterKey = deriveMasterKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secureRandom = SecureRandom()
        val iv = ByteArray(GCM_IV_SIZE)
        secureRandom.nextBytes(iv)
        val gcmSpec = GCMParameterSpec(GCM_TAG_SIZE, iv)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey, gcmSpec)
        val encryptedBytes = cipher.doFinal(password.concatToString().toByteArray(Charsets.UTF_8))
        return ByteBuffer.allocate(iv.size + encryptedBytes.size).put(iv).put(encryptedBytes).array()
    }

    private fun decryptPassword(encryptedData: ByteArray): String {
        val masterKey = deriveMasterKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val buffer = ByteBuffer.wrap(encryptedData)
        val iv = ByteArray(GCM_IV_SIZE)
        buffer.get(iv)
        val encryptedBytes = ByteArray(encryptedData.size - GCM_IV_SIZE)
        buffer.get(encryptedBytes)
        val gcmSpec = GCMParameterSpec(GCM_TAG_SIZE, iv)
        cipher.init(Cipher.DECRYPT_MODE, masterKey, gcmSpec)
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    fun calculateFingerprint(cert: X509Certificate): String {
        val keyBytes = cert.publicKey.encoded
        val sha256 = MessageDigest.getInstance("SHA-256")
        val digest = sha256.digest(keyBytes)
        return "SHA-256;" + digest.joinToString(":") { "%02x".format(it) }.uppercase()
    }

    private fun generateCertificate(keyPair: KeyPair): X509Certificate {
        val now = Date()
        val notAfter = Date(now.time + CERT_VALIDITY_DAYS * 24 * 60 * 60 * 1000L)
        val subject = X500Name("CN=BookWeaverSelfSigned")
        val serial = BigInteger(64, SecureRandom())
        val publicKeyInfo = SubjectPublicKeyInfo.getInstance(keyPair.public.encoded)
        val certBuilder = X509v3CertificateBuilder(subject, serial, now, notAfter, subject, publicKeyInfo)

        val sanBuilder = GeneralNamesBuilder()
        sanBuilder.addName(GeneralName(GeneralName.dNSName, "localhost"))
        NetworkUtils.getAllLocalIPs().forEach { ip ->
            sanBuilder.addName(GeneralName(GeneralName.iPAddress, ip.hostAddress))
        }
        val sanNames = sanBuilder.build()

        certBuilder.addExtension(Extension.subjectAlternativeName, false, sanNames)
        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
        return JcaX509CertificateConverter().setProvider("BC").getCertificate(certBuilder.build(signer))
    }
}
