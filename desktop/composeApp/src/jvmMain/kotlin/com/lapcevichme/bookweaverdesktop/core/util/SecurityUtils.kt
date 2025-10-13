package com.lapcevichme.bookweaverdesktop.core.util

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
    private const val PBKDF2_ITERATIONS = 100_000
    private const val AES_KEY_SIZE = 256
    private const val GCM_IV_SIZE = 12
    private const val GCM_TAG_SIZE = 128
    private const val SALT_SIZE = 16 // УЛУЧШЕНИЕ: Размер для случайной соли

    // УЛУЧШЕНИЕ: Получаем безопасную директорию для хранения файлов
    private val securityDir = getSecurityDir()
    private val encryptedPasswordFile = File(securityDir, "keystore_encrypted.dat")

    /**
     * Загружает KeyStore или создает новый. Пароль зашифрован ключом,
     * полученным из "отпечатка" машины.
     * @return Triple<KeyStore, fingerprint, password>
     */
    fun setupCertificate(keyStoreFileName: String): Triple<KeyStore, String, CharArray> {
        val keyStoreFile = File(securityDir, keyStoreFileName)
        val keyStore = KeyStore.getInstance("BKS", "BC")

        val (keystorePassword, isNew) = if (keyStoreFile.exists() && encryptedPasswordFile.exists()) {
            logger.info { "Loading existing keystore..." }
            try {
                val encryptedData = encryptedPasswordFile.readBytes()
                val passwordChars = decryptPassword(encryptedData)
                keyStoreFile.inputStream().use { keyStore.load(it, passwordChars) }
                Pair(passwordChars, false)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load keystore or decrypt password. Regenerating..." }
                generateNewKeystoreAndPassword(keyStore, keyStoreFile)
            }
        } else {
            logger.info { "No existing keystore found. Generating new one..." }
            generateNewKeystoreAndPassword(keyStore, keyStoreFile)
        }

        val cert = keyStore.getCertificate(KEY_ALIAS) as X509Certificate
        val fingerprint = calculateFingerprint(cert)
        if (isNew) {
            logger.info { "New certificate fingerprint: $fingerprint (QR scan required)" }
        }
        return Triple(keyStore, fingerprint, keystorePassword)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun generateNewKeystoreAndPassword(keyStore: KeyStore, keyStoreFile: File): Pair<CharArray, Boolean> {
        val passwordStr = generateSecurePassword()
        val passwordChars = passwordStr.toCharArray()

        // Шифруем и сохраняем пароль
        val encryptedData = encryptPassword(passwordChars)
        encryptedPasswordFile.writeBytes(encryptedData)

        // Генерируем сертификат и сохраняем keystore
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cert = generateCertificate(keyPair)
        keyStore.load(null, passwordChars)
        keyStore.setKeyEntry(KEY_ALIAS, keyPair.private, passwordChars, arrayOf(cert))
        keyStoreFile.outputStream().use { keyStore.store(it, passwordChars) }

        logger.info { "New keystore and encrypted password saved to ${securityDir.absolutePath}" }
        return Pair(passwordChars, true)
    }

    private fun deriveMasterKey(salt: ByteArray): SecretKey {
        val fingerprint = getMachineFingerprint()
        val keySpec: KeySpec = PBEKeySpec(fingerprint.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_SIZE)
        val keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = keyFactory.generateSecret(keySpec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun encryptPassword(password: CharArray): ByteArray {
        // УЛУЧШЕНИЕ БЕЗОПАСНОСТИ: Генерируем случайную соль для каждого шифрования
        val salt = ByteArray(SALT_SIZE).apply { SecureRandom().nextBytes(this) }
        val masterKey = deriveMasterKey(salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_SIZE).apply { SecureRandom().nextBytes(this) }
        val gcmSpec = GCMParameterSpec(GCM_TAG_SIZE, iv)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey, gcmSpec)

        val encryptedBytes = cipher.doFinal(password.concatToString().toByteArray(Charsets.UTF_8))

        // УЛУЧШЕНИЕ: Сохраняем соль и IV вместе с зашифрованными данными
        return ByteBuffer.allocate(salt.size + iv.size + encryptedBytes.size)
            .put(salt).put(iv).put(encryptedBytes).array()
    }

    private fun decryptPassword(encryptedData: ByteArray): CharArray {
        val buffer = ByteBuffer.wrap(encryptedData)

        // УЛУЧШЕНИЕ: Извлекаем соль и IV из сохраненных данных
        val salt = ByteArray(SALT_SIZE).apply { buffer.get(this) }
        val iv = ByteArray(GCM_IV_SIZE).apply { buffer.get(this) }

        val masterKey = deriveMasterKey(salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(GCM_TAG_SIZE, iv)
        cipher.init(Cipher.DECRYPT_MODE, masterKey, gcmSpec)

        val encryptedBytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }
        val decryptedBytes = cipher.doFinal(encryptedBytes)

        return String(decryptedBytes, Charsets.UTF_8).toCharArray()
    }

    // --- Вспомогательные функции (без изменений, но с добавлением getSecurityDir) ---

    @OptIn(ExperimentalEncodingApi::class)
    private fun generateSecurePassword(): String {
        val passwordBytes = ByteArray(32).apply { SecureRandom().nextBytes(this) }
        return Base64.encode(passwordBytes)
    }

    private fun getMachineFingerprint(): String {
        val hostname = try { InetAddress.getLocalHost().hostName } catch (e: Exception) { "unknown" }
        val primaryMac = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { !it.isLoopback && it.isUp && it.hardwareAddress != null }
            .firstOrNull()
            ?.hardwareAddress
            ?.joinToString("") { "%02x".format(it) } ?: "no_mac"
        return "$hostname-$primaryMac"
    }

    fun calculateFingerprint(cert: X509Certificate): String {
        val keyBytes = cert.publicKey.encoded
        val digest = MessageDigest.getInstance("SHA-256").digest(keyBytes)
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
        certBuilder.addExtension(Extension.subjectAlternativeName, false, sanBuilder.build())

        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
        return JcaX509CertificateConverter().setProvider("BC").getCertificate(certBuilder.build(signer))
    }

    private fun getSecurityDir(): File {
        val os = System.getProperty("os.name").uppercase()
        val userHome = System.getProperty("user.home")
        val path = when {
            os.contains("WIN") -> "$userHome/AppData/Roaming/BookWeaver"
            os.contains("MAC") -> "$userHome/Library/Application Support/BookWeaver"
            os.contains("NIX") || os.contains("NUX") -> "$userHome/.config/BookWeaver"
            else -> "$userHome/BookWeaver"
        }
        return File(path, "security").apply { mkdirs() }
    }
}
