package net.corda.node.utilities.registration

import net.corda.core.crypto.Crypto
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.concurrent.transpose
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.minutes
import net.corda.finance.DOLLARS
import net.corda.finance.flows.CashIssueAndPaymentFlow
import net.corda.nodeapi.internal.crypto.CertificateAndKeyPair
import net.corda.nodeapi.internal.crypto.CertificateType
import net.corda.nodeapi.internal.crypto.X509CertificateFactory
import net.corda.nodeapi.internal.crypto.X509Utilities
import net.corda.nodeapi.internal.crypto.X509Utilities.CORDA_CLIENT_CA
import net.corda.nodeapi.internal.crypto.X509Utilities.CORDA_INTERMEDIATE_CA
import net.corda.nodeapi.internal.crypto.X509Utilities.CORDA_ROOT_CA
import net.corda.testing.DEV_ROOT_CA
import net.corda.testing.SerializationEnvironmentRule
import net.corda.testing.driver.PortAllocation
import net.corda.testing.node.NotarySpec
import net.corda.testing.node.internal.CompatibilityZoneParams
import net.corda.testing.node.internal.internalDriver
import net.corda.testing.node.internal.network.NetworkMapServer
import net.corda.testing.singleIdentity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import java.security.KeyPair
import java.security.cert.CertPath
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.security.auth.x500.X500Principal
import javax.ws.rs.*
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response

class NodeRegistrationTest {
    @Rule
    @JvmField
    val testSerialization = SerializationEnvironmentRule(true)
    private val portAllocation = PortAllocation.Incremental(13000)
    private val registrationHandler = RegistrationHandler(DEV_ROOT_CA)
    private lateinit var server: NetworkMapServer
    private lateinit var serverHostAndPort: NetworkHostAndPort

    @Before
    fun startServer() {
        server = NetworkMapServer(1.minutes, portAllocation.nextHostAndPort(), DEV_ROOT_CA, "localhost", registrationHandler)
        serverHostAndPort = server.start()
    }

    @After
    fun stopServer() {
        server.close()
    }

    @Test
    fun `node registration correct root cert`() {
        val compatibilityZone = CompatibilityZoneParams(URL("http://$serverHostAndPort"), rootCert = DEV_ROOT_CA.certificate)
        internalDriver(
                portAllocation = portAllocation,
                compatibilityZone = compatibilityZone,
                initialiseSerialization = false,
                notarySpecs = listOf(NotarySpec(CordaX500Name(organisation = "NotaryService", locality = "Zurich", country = "CH"), validating = false)),
                extraCordappPackagesToScan = listOf("net.corda.finance"),
                onNetworkParametersGeneration = { server.networkParameters = it }
        ) {
            val notary = defaultNotaryNode.get()

            val ALICE_NAME = "Alice"
            val GENEVIEVE_NAME = "Genevieve"
            val nodesFutures = listOf(startNode(providedName = CordaX500Name(ALICE_NAME, "London", "GB")),
                    startNode(providedName = CordaX500Name(GENEVIEVE_NAME, "London", "GB")))

            val (alice, genevieve) = nodesFutures.transpose().get()
            val nodes = listOf(alice, genevieve, notary)

            assertThat(registrationHandler.idsPolled).contains(ALICE_NAME, GENEVIEVE_NAME)
            // Notary identities are generated beforehand hence notary nodes don't go through registration.
            // This test isn't specifically testing this, or relying on this behavior, though if this check fail,
            // this will probably lead to the rest of the test to fail.
            assertThat(registrationHandler.idsPolled).doesNotContain("NotaryService")

            // Check each node has each other identity in their network map cache.
            val nodeIdentities = nodes.map { it.nodeInfo.singleIdentity() }
            for (node in nodes) {
                assertThat(node.rpc.networkMapSnapshot().map { it.singleIdentity() }).containsAll(nodeIdentities)
            }

            // Check we nodes communicate among themselves (and the notary).
            val anonymous = false
            genevieve.rpc.startFlow(::CashIssueAndPaymentFlow, 1000.DOLLARS, OpaqueBytes.of(12),
                    alice.nodeInfo.singleIdentity(),
                    anonymous,
                    notary.nodeInfo.singleIdentity())
                    .returnValue
                    .getOrThrow()
        }
    }

    @Test
    fun `node registration wrong root cert`() {
        val someCert = createSelfKeyAndSelfSignedCertificate().certificate
        val compatibilityZone = CompatibilityZoneParams(URL("http://$serverHostAndPort"), rootCert = someCert)
        internalDriver(
                portAllocation = portAllocation,
                notarySpecs = emptyList(),
                compatibilityZone = compatibilityZone,
                initialiseSerialization = false,
                // Changing the content of the truststore makes the node fail in a number of ways if started out process.
                startNodesInProcess = true
        ) {
            assertThatThrownBy {
                startNode(providedName = CordaX500Name("Alice", "London", "GB")).getOrThrow()
            }.isInstanceOf(WrongRootCertException::class.java)
        }
    }

    private fun createSelfKeyAndSelfSignedCertificate(): CertificateAndKeyPair {
        val rootCAKey = Crypto.generateKeyPair(X509Utilities.DEFAULT_TLS_SIGNATURE_SCHEME)
        val rootCACert = X509Utilities.createSelfSignedCACertificate(
                X500Principal("CN=Integration Test Corda Node Root CA,O=R3 Ltd,L=London,C=GB"),
                rootCAKey)
        return CertificateAndKeyPair(rootCACert, rootCAKey)
    }
}

@Path("certificate")
class RegistrationHandler(private val rootCertAndKeyPair: CertificateAndKeyPair) {
    private val certPaths = HashMap<String, CertPath>()
    val idsPolled = HashSet<String>()

    @POST
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @Produces(MediaType.TEXT_PLAIN)
    fun registration(input: InputStream): Response {
        val certificationRequest = input.use { JcaPKCS10CertificationRequest(it.readBytes()) }
        val (certPath, name) = createSignedClientCertificate(
                certificationRequest,
                rootCertAndKeyPair.keyPair,
                arrayOf(rootCertAndKeyPair.certificate))
        certPaths[name.organisation] = certPath
        return Response.ok(name.organisation).build()
    }

    @GET
    @Path("{id}")
    fun reply(@PathParam("id") id: String): Response {
        idsPolled += id
        return buildResponse(certPaths[id]!!.certificates)
    }

    private fun buildResponse(certificates: List<Certificate>): Response {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            listOf(CORDA_CLIENT_CA, CORDA_INTERMEDIATE_CA, CORDA_ROOT_CA).zip(certificates).forEach {
                zip.putNextEntry(ZipEntry("${it.first}.cer"))
                zip.write(it.second.encoded)
                zip.closeEntry()
            }
        }
        return Response.ok(baos.toByteArray())
                .type("application/zip")
                .header("Content-Disposition", "attachment; filename=\"certificates.zip\"").build()
    }

    private fun createSignedClientCertificate(certificationRequest: PKCS10CertificationRequest,
                                              caKeyPair: KeyPair,
                                              caCertPath: Array<Certificate>): Pair<CertPath, CordaX500Name> {
        val request = JcaPKCS10CertificationRequest(certificationRequest)
        val name = CordaX500Name.parse(request.subject.toString())
        val x509CertificateHolder = X509Utilities.createCertificate(CertificateType.NODE_CA,
                caCertPath[0] as X509Certificate ,
                caKeyPair,
                name.x500Principal,
                request.publicKey,
                nameConstraints = null)
        val certPath = X509CertificateFactory().generateCertPath(x509CertificateHolder, *caCertPath)
        return Pair(certPath, name)
    }
}
