package hydraulic.url

import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.tsp.TimeStampResponse
import org.bouncycastle.util.Selector
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Security
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.CertStore
import java.security.cert.CertPathBuilder
import java.security.cert.PKIXBuilderParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509CertSelector
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.util.Date
import kotlin.io.path.inputStream

private const val TIMESTAMP_FILE = "timestamp.tsr"
private const val TIMESTAMPING_EKU = "1.3.6.1.5.5.7.3.8"

/** Verifies the optional timestamp in a run package before its JavaScript runs. */
internal fun verifyRunPackage(packageDir: Path) {
    val timestamp = packageDir.resolve(TIMESTAMP_FILE)
    if (!Files.exists(timestamp, LinkOption.NOFOLLOW_LINKS))
        return

    val attributes = Files.readAttributes(timestamp, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    require(attributes.isRegularFile && !attributes.isSymbolicLink()) {
        "$TIMESTAMP_FILE must be a regular file at the package root"
    }

    val manifest = canonicalRunManifest(packageDir)
    verifyTimestamp(Files.readAllBytes(timestamp), manifest)
}

/** Builds the byte representation covered by a run package's RFC 3161 token. */
internal fun canonicalRunManifest(packageDir: Path): ByteArray {
    val root = packageDir.toRealPath()
    val files = ArrayList<Path>()
    Files.walk(root).use { paths ->
        paths.forEach { path ->
            if (path == root)
                return@forEach
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(!attributes.isSymbolicLink()) {
                "Run packages may not contain symbolic links: ${root.relativize(path)}"
            }
            if (attributes.isDirectory)
                return@forEach
            require(attributes.isRegularFile) {
                "Run packages may only contain regular files: ${root.relativize(path)}"
            }
            if (path.fileName.toString() == "context.d.ts" || path.fileName.toString() == TIMESTAMP_FILE)
                return@forEach
            files.add(path)
        }
    }

    val records = files.map { path ->
        val relative = root.relativize(path).toString().replace('\\', '/')
        require('\n' !in relative && '\r' !in relative) {
            "Run package paths may not contain newlines: $relative"
        }
        relative to sha256(path)
    }.sortedWith { left, right -> compareUtf8(left.first, right.first) }

    return buildString {
        records.forEach { (path, digest) -> append(digest).append("  ").append(path).append('\n') }
    }.toByteArray(Charsets.UTF_8)
}

private fun verifyTimestamp(responseBytes: ByteArray, manifest: ByteArray) {
    ensureBouncyCastle()
    val response = try {
        TimeStampResponse(responseBytes)
    } catch (e: Exception) {
        throw IllegalArgumentException("Invalid RFC 3161 timestamp response", e)
    }
    require(response.status == 0 || response.status == 1) {
        "Timestamp authority rejected the package manifest: ${response.statusString.orEmpty()}"
    }
    val token = requireNotNull(response.timeStampToken) {
        "Timestamp response does not contain a timestamp token"
    }
    val info = token.timeStampInfo
    require(info.messageImprintAlgOID == NISTObjectIdentifiers.id_sha256) {
        "Run package timestamp must use SHA-256"
    }
    val expectedImprint = MessageDigest.getInstance("SHA-256").digest(manifest)
    require(expectedImprint.contentEquals(info.messageImprintDigest)) {
        "Run package timestamp does not cover the package contents"
    }

    val signedData = CMSSignedData(token.encoded)
    @Suppress("UNCHECKED_CAST")
    val signer = signedData.certificates.getMatches(token.sid as Selector<X509CertificateHolder>).singleOrNull()
        ?: throw IllegalArgumentException("Timestamp token does not contain exactly one signer certificate")
    val signerCertificate = JcaX509CertificateConverter().setProvider("BC").getCertificate(signer)
    require(signerCertificate.extendedKeyUsage?.let { it.size == 1 && TIMESTAMPING_EKU in it } == true) {
        "Timestamp signer certificate is not authorized for timestamping"
    }
    require("2.5.29.37" in (signerCertificate.criticalExtensionOIDs ?: emptySet())) {
        "Timestamp signer certificate must mark timestamping usage as critical"
    }
    signerCertificate.checkValidity(Date(info.genTime.time))
    token.validate(JcaSimpleSignerInfoVerifierBuilder().setProvider("BC").build(signer))
    validateTimestampSigner(signedData, signerCertificate, info.genTime)
}

private fun validateTimestampSigner(signedData: CMSSignedData, signer: X509Certificate, timestamp: Date) {
    val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    trustManagerFactory.init(null as KeyStore?)
    val trustManager = trustManagerFactory.trustManagers.filterIsInstance<X509TrustManager>().singleOrNull()
        ?: throw IllegalArgumentException("The runtime does not provide an X.509 trust manager")
    val certificates = signedData.certificates.getMatches(null).map {
        JcaX509CertificateConverter().setProvider("BC").getCertificate(it)
    }
    val selector = X509CertSelector().also { it.certificate = signer }
    val trustAnchors = trustManager.acceptedIssuers.map { TrustAnchor(it, null) }.toSet()
    val parameters = PKIXBuilderParameters(trustAnchors, selector).apply {
        date = timestamp
        isRevocationEnabled = false
        addCertStore(CertStore.getInstance("Collection", CollectionCertStoreParameters(certificates)))
    }
    try {
        CertPathBuilder.getInstance("PKIX").build(parameters)
    } catch (e: Exception) {
        throw IllegalArgumentException("Timestamp signer certificate is not trusted", e)
    }
}

private fun ensureBouncyCastle() {
    if (Security.getProvider("BC") == null)
        Security.addProvider(BouncyCastleProvider())
}

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    path.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0)
                break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
}

private fun compareUtf8(left: String, right: String): Int {
    val leftBytes = left.toByteArray(Charsets.UTF_8)
    val rightBytes = right.toByteArray(Charsets.UTF_8)
    for (index in 0 until minOf(leftBytes.size, rightBytes.size)) {
        val comparison = leftBytes[index].toInt().and(0xff) - rightBytes[index].toInt().and(0xff)
        if (comparison != 0)
            return comparison
    }
    return leftBytes.size - rightBytes.size
}
