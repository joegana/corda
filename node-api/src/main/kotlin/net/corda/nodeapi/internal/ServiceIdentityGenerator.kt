package net.corda.nodeapi.internal

import net.corda.core.crypto.CompositeKey
import net.corda.core.crypto.generateKeyPair
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.internal.cert
import net.corda.core.internal.createDirectories
import net.corda.core.internal.div
import net.corda.core.utilities.trace
import net.corda.nodeapi.internal.crypto.*
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.security.KeyPair
import java.security.PublicKey
import java.security.cert.Certificate
import java.security.cert.X509Certificate

object ServiceIdentityGenerator {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Generates signing key pairs and a common distributed service identity for a set of nodes.
     * The key pairs and the group identity get serialized to disk in the corresponding node directories.
     * This method should be called *before* any of the nodes are started.
     *
     * @param dirs List of node directories to place the generated identity and key pairs in.
     * @param serviceName The legal name of the distributed service.
     * @param threshold The threshold for the generated group [CompositeKey].
     * @param customRootCert the certificate to use a Corda root CA. If not specified the one in
     *      certificates/cordadevcakeys.jks is used.
     */
    fun generateToDisk(dirs: List<Path>,
                       serviceName: CordaX500Name,
                       serviceId: String,
                       threshold: Int = 1,
                       customRootCert: X509Certificate? = null): Party {

        log.trace { "Generating a composite group identity \"serviceName\" for nodes: ${dirs.joinToString()}" }
        val caKeyStore = loadKeyStore(javaClass.classLoader.getResourceAsStream("certificates/cordadevcakeys.jks"), "cordacadevpass")
        val issuer = caKeyStore.getCertificateAndKeyPair(X509Utilities.CORDA_INTERMEDIATE_CA, "cordacadevkeypass")
        val rootCert = customRootCert ?: caKeyStore.getCertificate(X509Utilities.CORDA_ROOT_CA)

        val keyPairs = (1..dirs.size).map { generateKeyPair() }
        val notaryKey = CompositeKey.Builder().addKeys(keyPairs.map { it.public }).build(threshold)
        keyPairs.zip(dirs) { keyPair, dir ->
            generateCertificates(issuer, serviceName, keyPair, notaryKey, dir, serviceId, rootCert)
        }
        return Party(serviceName, notaryKey)
    }

    /**
     * Similar to [generateToDisk], but the generated distributed service identity will consist of a single public key
     * instead of a composite key, and each node will receive a copy of the private key.
     *
     * @param dirs List of node directories to place the generated identity and key pairs in.
     * @param serviceName The legal name of the distributed service.
     * @param customRootCert the certificate to use a Corda root CA. If not specified the one in
     *      certificates/cordadevcakeys.jks is used.
     */
    fun generateToDiskSingular(dirs: List<Path>,
                               serviceName: CordaX500Name,
                               serviceId: String,
                               customRootCert: X509Certificate? = null): Party {
        log.trace { "Generating a singular group identity \"serviceName\" for nodes: ${dirs.joinToString()}" }
        val caKeyStore = loadKeyStore(javaClass.classLoader.getResourceAsStream("certificates/cordadevcakeys.jks"), "cordacadevpass")
        val issuer = caKeyStore.getCertificateAndKeyPair(X509Utilities.CORDA_INTERMEDIATE_CA, "cordacadevkeypass")
        val rootCert = customRootCert ?: caKeyStore.getCertificate(X509Utilities.CORDA_ROOT_CA)

        val keyPair = generateKeyPair()
        val notaryKey = keyPair.public
        dirs.forEach { dir ->
            generateCertificates(issuer, serviceName, keyPair, notaryKey, dir, serviceId, rootCert)
        }
        return Party(serviceName, notaryKey)
    }

    private fun generateCertificates(issuer: CertificateAndKeyPair, serviceName: CordaX500Name, keyPair: KeyPair, notaryKey: PublicKey, dir: Path, serviceId: String, rootCert: Certificate?) {
        val serviceKeyCert = X509Utilities.createCertificate(CertificateType.NODE_CA, issuer.certificate, issuer.keyPair, serviceName, keyPair.public)
        val compositeKeyCert = X509Utilities.createCertificate(CertificateType.NODE_CA, issuer.certificate, issuer.keyPair, serviceName, notaryKey)
        val certPath = (dir / "certificates").createDirectories() / "distributedService.jks"
        val keystore = loadOrCreateKeyStore(certPath, "cordacadevpass")
        keystore.setCertificateEntry("$serviceId-composite-key", compositeKeyCert.cert)
        keystore.setKeyEntry("$serviceId-private-key", keyPair.private, "cordacadevkeypass".toCharArray(), arrayOf(serviceKeyCert.cert, issuer.certificate.cert, rootCert))
        keystore.save(certPath, "cordacadevpass")
    }
}
