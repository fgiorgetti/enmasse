/*
 * Copyright 2020, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.sharedinfra;

import io.enmasse.api.model.MessagingAddressBuilder;
import io.enmasse.api.model.MessagingEndpoint;
import io.enmasse.api.model.MessagingEndpointBuilder;
import io.enmasse.api.model.MessagingEndpointPort;
import io.enmasse.api.model.MessagingTenant;
import io.enmasse.systemtest.Endpoint;
import io.enmasse.systemtest.amqp.AmqpClient;
import io.enmasse.systemtest.amqp.AmqpConnectOptions;
import io.enmasse.systemtest.amqp.DeliveryHandler;
import io.enmasse.systemtest.amqp.QueueTerminusFactory;
import io.enmasse.systemtest.annotations.DefaultMessagingInfrastructure;
import io.enmasse.systemtest.annotations.DefaultMessagingTenant;
import io.enmasse.systemtest.annotations.ExternalClients;
import io.enmasse.systemtest.bases.TestBase;
import io.enmasse.systemtest.bases.isolated.ITestIsolatedSharedInfra;
import io.enmasse.systemtest.condition.Kubernetes;
import io.enmasse.systemtest.condition.OpenShift;
import io.enmasse.systemtest.messagingclients.ClientArgument;
import io.enmasse.systemtest.messagingclients.ExternalMessagingClient;
import io.enmasse.systemtest.messagingclients.rhea.RheaClientReceiver;
import io.enmasse.systemtest.messagingclients.rhea.RheaClientSender;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.proton.ProtonClientOptions;
import io.vertx.proton.ProtonDelivery;
import io.vertx.proton.ProtonQoS;
import org.apache.qpid.proton.amqp.messaging.Accepted;
import org.apache.qpid.proton.amqp.messaging.Rejected;
import org.apache.qpid.proton.amqp.messaging.Source;
import org.apache.qpid.proton.message.Message;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static io.enmasse.systemtest.TestTag.ISOLATED_SHARED_INFRA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(ISOLATED_SHARED_INFRA)
@DefaultMessagingInfrastructure
@DefaultMessagingTenant
@ExternalClients
public class MessagingAddressTest extends TestBase implements ITestIsolatedSharedInfra {

    private MessagingTenant tenant;
    private MessagingEndpoint endpoint;

    @BeforeAll
    public void createEndpoint() {
        tenant = infraResourceManager.getDefaultMessagingTenant();
        endpoint = new MessagingEndpointBuilder()
                .editOrNewMetadata()
                .withNamespace(tenant.getMetadata().getNamespace())
                .withName("app")
                .endMetadata()
                .editOrNewSpec()
                .editOrNewTls()
                .editOrNewSelfsigned()
                .endSelfsigned()
                .endTls()
                .editOrNewCluster()
                .endCluster()
                .addToProtocols("AMQP", "AMQPS")
                .endSpec()
                .build();
        infraResourceManager.createResource(endpoint);
    }

    @Test
    public void testAnycast() throws Exception {
        infraResourceManager.createResource(new MessagingAddressBuilder()
                .editOrNewMetadata()
                .withName("addr1")
                .withNamespace(tenant.getMetadata().getNamespace())
                .endMetadata()
                .editOrNewSpec()
                .editOrNewAnycast()
                .endAnycast()
                .endSpec()
                .build());
        doTestSendReceive("addr1", "addr1");
    }

    @Test
    public void testMulticast() throws Exception {
        infraResourceManager.createResource(new MessagingAddressBuilder()
                .editOrNewMetadata()
                .withName("multicast1")
                .withNamespace(tenant.getMetadata().getNamespace())
                .endMetadata()
                .editOrNewSpec()
                .editOrNewMulticast()
                .endMulticast()
                .endSpec()
                .build());
        doTestSendReceive(true, "multicast1", "multicast1", "multicast1", "multicast1");
    }

    @Test
    public void testQueue() throws Exception {
        infraResourceManager.createResource(new MessagingAddressBuilder()
                .editOrNewMetadata()
                .withName("queue1")
                .withNamespace(tenant.getMetadata().getNamespace())
                .endMetadata()
                .editOrNewSpec()
                .editOrNewQueue()
                .endQueue()
                .endSpec()
                .build());
        doTestSendReceive("queue1", "queue1");
    }

    @Test
    public void testDeadLetterExpiry() throws Exception {
        infraResourceManager.createResource(new MessagingAddressBuilder()
                .editOrNewMetadata()
                .withName("dlq1")
                .withNamespace(tenant.getMetadata().getNamespace())
                .endMetadata()
                .editOrNewSpec()
                .editOrNewDeadLetter()
                .endDeadLetter()
                .endSpec()
                .build());
        infraResourceManager.createResource(new MessagingAddressBuilder()
                .editOrNewMetadata()
                .withName("queue1")
                .withNamespace(tenant.getMetadata().getNamespace())
                .endMetadata()
                .editOrNewSpec()
                .editOrNewQueue()
                .withExpiryAddress("dlq1")
                .endQueue()
                .endSpec()
                .build());

        doTestSendReceive(false, Collections.singletonMap(ClientArgument.MSG_TTL, "100"), null, "queue1", "dlq1");
    }

    @Test
    public void testDeadLetterConsume() throws Exception {
        MessagingEndpoint ingress = new MessagingEndpointBuilder()
                .editOrNewMetadata()
                .withName("dlq")
                .withNamespace(tenant.getMetadata().getNamespace())
                .endMetadata()
                .editOrNewSpec()
                .editOrNewNodePort()
                .endNodePort()
                .withHost(kubernetes.getNodeHost())
                .addToProtocols("AMQP")
                .endSpec()
                .build();
        infraResourceManager.createResource(ingress);
        infraResourceManager.createResource(new MessagingAddressBuilder()
                .editOrNewMetadata()
                .withName("dlq1")
                .withNamespace(tenant.getMetadata().getNamespace())
                .endMetadata()
                .editOrNewSpec()
                .editOrNewDeadLetter()
                .endDeadLetter()
                .endSpec()
                .build());
        infraResourceManager.createResource(new MessagingAddressBuilder()
                .editOrNewMetadata()
                .withName("queue1")
                .withNamespace(tenant.getMetadata().getNamespace())
                .endMetadata()
                .editOrNewSpec()
                .editOrNewQueue()
                .withDeadLetterAddress("dlq1")
                .endQueue()
                .endSpec()
                .build());


        AmqpClient client = infraResourceManager.getAmqpClientFactory().createClient(new AmqpConnectOptions()
                .setSaslMechanism("ANONYMOUS")
                .setQos(ProtonQoS.AT_LEAST_ONCE)
                .setEndpoint(new Endpoint(ingress.getStatus().getHost(), ingress.getStatus().getPorts().get(0).getPort()))
                .setProtonClientOptions(new ProtonClientOptions())
                .setTerminusFactory(new QueueTerminusFactory()));

        assertEquals(1, client.sendMessages("queue1", Collections.singletonList("todeadletter")).get(1, TimeUnit.MINUTES));
        Source source = new Source();
        source.setAddress("queue1");
        AtomicInteger redeliveries = new AtomicInteger(0);
        assertEquals(1, client.recvMessages(source, message -> redeliveries.incrementAndGet() >= 1, Optional.empty(), protonDelivery -> protonDelivery.disposition(new Rejected(), true)).getResult().get(1, TimeUnit.MINUTES).size());
        assertEquals(1, client.recvMessages("dlq1", 1).get(1, TimeUnit.MINUTES).size());
    }

    @Test
    public void testTopic() throws Exception {
        infraResourceManager.createResource(new MessagingAddressBuilder()
                .editOrNewMetadata()
                .withName("topic1")
                .withNamespace(tenant.getMetadata().getNamespace())
                .endMetadata()
                .editOrNewSpec()
                .editOrNewTopic()
                .endTopic()
                .endSpec()
                .build());
        doTestSendReceive(true, "topic1", "topic1", "topic1", "topic1");
    }

    @Test
    public void testSubscription() throws Exception {
        infraResourceManager.createResource(new MessagingAddressBuilder()
                .editOrNewMetadata()
                .withName("topic1")
                .withNamespace(tenant.getMetadata().getNamespace())
                .endMetadata()
                .editOrNewSpec()
                .editOrNewTopic()
                .endTopic()
                .endSpec()
                .build(),
                new MessagingAddressBuilder()
                .editOrNewMetadata()
                .withName("sub1")
                .withNamespace(tenant.getMetadata().getNamespace())
                .endMetadata()
                .editOrNewSpec()
                .editOrNewSubscription()
                .withTopic("topic1")
                .endSubscription()
                .endSpec()
                .build(),
                new MessagingAddressBuilder()
                .editOrNewMetadata()
                .withName("sub2")
                .withNamespace(tenant.getMetadata().getNamespace())
                .endMetadata()
                .editOrNewSpec()
                .editOrNewSubscription()
                .withTopic("topic1")
                .endSubscription()
                .endSpec()
                .build());
        doTestSendReceive("topic1", "sub1", "sub2");
    }

    /**
     * Send 10 messages on sender address, and receive 10 messages on each receiver address.
     */
    void doTestSendReceive(boolean waitReceivers, Map<ClientArgument, Object> extraSenderArgs, Map<ClientArgument, Object> extraReceiverArgs, String senderAddress, String ... receiverAddresses) throws Exception {
        int expectedMsgCount = 10;

        Endpoint e = new Endpoint(endpoint.getStatus().getHost(), getPort("AMQP", endpoint));
        ExternalMessagingClient senderClient = new ExternalMessagingClient(false)
                .withClientEngine(new RheaClientSender())
                .withMessagingRoute(e)
                .withAddress(senderAddress)
                .withCount(expectedMsgCount)
                .withMessageBody("msg no. %d")
                .withAdditionalArgument(ClientArgument.CONN_AUTH_MECHANISM, "ANONYMOUS")
                .withTimeout(60);

        if (extraSenderArgs != null) {
            for (Map.Entry<ClientArgument, Object> arg : extraSenderArgs.entrySet()) {
                senderClient.withAdditionalArgument(arg.getKey(), arg.getValue());
            }
        }

        List<ExternalMessagingClient> receiverClients = new ArrayList<>();
        for (String receiverAddress : receiverAddresses) {
            ExternalMessagingClient receiverClient = new ExternalMessagingClient(false)
                    .withClientEngine(new RheaClientReceiver())
                    .withMessagingRoute(e)
                    .withAddress(receiverAddress)
                    .withCount(expectedMsgCount)
                    .withAdditionalArgument(ClientArgument.CONN_AUTH_MECHANISM, "ANONYMOUS")
                    .withTimeout(60);

            if (extraReceiverArgs != null) {
                for (Map.Entry<ClientArgument, Object> arg : extraReceiverArgs.entrySet()) {
                    receiverClient.withAdditionalArgument(arg.getKey(), arg.getValue());
                }
            }

            receiverClients.add(receiverClient);
        }

        List<Future<Boolean>> receiverResults = new ArrayList<>();
        for (ExternalMessagingClient receiverClient : receiverClients) {
            receiverResults.add(ForkJoinPool.commonPool().submit((Callable<Boolean>) receiverClient::run));
        }

        if (waitReceivers) {
            // To ensure receivers are attached and ready
            Thread.sleep(10_000);
        }

        Future<Boolean> senderResult = ForkJoinPool.commonPool().submit((Callable<Boolean>) senderClient::run);

        assertTrue(senderResult.get(1, TimeUnit.MINUTES), "Sender failed, expected return code 0");
        for (Future<Boolean> receiverResult : receiverResults) {
            assertTrue(receiverResult.get(1, TimeUnit.MINUTES), "Receiver failed, expected return code 0");
        }

        assertEquals(expectedMsgCount, senderClient.getMessages().size(),
                String.format("Expected %d sent messages", expectedMsgCount));
        for (ExternalMessagingClient receiverClient : receiverClients) {
            assertEquals(expectedMsgCount, receiverClient.getMessages().size(),
                    String.format("Expected %d received messages", expectedMsgCount));
        }
    }

    void doTestSendReceive(boolean waitReceivers, String senderAddress, String ... receiverAddresses) throws Exception {
        doTestSendReceive(waitReceivers, null, null, senderAddress, receiverAddresses);
    }

    void doTestSendReceive(String senderAddress, String ... receiverAddresses) throws Exception {
        doTestSendReceive(false, senderAddress, receiverAddresses);
    }

    private static int getPort(String protocol, MessagingEndpoint endpoint) {
        for (MessagingEndpointPort port : endpoint.getStatus().getPorts()) {
            if (protocol.equals(port.getProtocol()))  {
                return port.getPort();
            }
        }
        return 0;
    }
}