/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.rollingupdate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapKeySelector;
import io.fabric8.kubernetes.api.model.ConfigMapKeySelectorBuilder;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.strimzi.api.kafka.model.ExternalLoggingBuilder;
import io.strimzi.api.kafka.model.JmxPrometheusExporterMetrics;
import io.strimzi.api.kafka.model.JmxPrometheusExporterMetricsBuilder;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.KafkaUser;
import io.strimzi.api.kafka.model.ProbeBuilder;
import io.strimzi.systemtest.AbstractST;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.annotations.IsolatedTest;
import io.strimzi.systemtest.annotations.ParallelNamespaceTest;
import io.strimzi.systemtest.kafkaclients.internalClients.InternalKafkaClient;
import io.strimzi.systemtest.resources.ResourceManager;
import io.strimzi.systemtest.resources.ResourceOperation;
import io.strimzi.systemtest.resources.crd.KafkaResource;
import io.strimzi.systemtest.templates.crd.KafkaClientsTemplates;
import io.strimzi.systemtest.templates.crd.KafkaTemplates;
import io.strimzi.systemtest.templates.crd.KafkaTopicTemplates;
import io.strimzi.systemtest.templates.crd.KafkaUserTemplates;
import io.strimzi.systemtest.utils.ClientUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaTopicUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaUtils;
import io.strimzi.systemtest.utils.kubeUtils.controllers.StatefulSetUtils;
import io.strimzi.systemtest.utils.kubeUtils.objects.PodUtils;
import io.strimzi.systemtest.utils.specific.MetricsUtils;
import io.strimzi.test.TestUtils;
import io.strimzi.test.timemeasuring.Operation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.strimzi.api.kafka.model.KafkaResources.kafkaStatefulSetName;
import static io.strimzi.systemtest.Constants.ACCEPTANCE;
import static io.strimzi.systemtest.Constants.INTERNAL_CLIENTS_USED;
import static io.strimzi.systemtest.Constants.REGRESSION;
import static io.strimzi.systemtest.Constants.ROLLING_UPDATE;
import static io.strimzi.systemtest.Constants.SCALABILITY;
import static io.strimzi.systemtest.Constants.STATEFUL_SET;
import static io.strimzi.systemtest.k8s.Events.Killing;
import static io.strimzi.systemtest.matchers.Matchers.hasAllOfReasons;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;
import static java.util.Collections.singletonMap;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag(REGRESSION)
@Tag(INTERNAL_CLIENTS_USED)
class RollingUpdateST extends AbstractST {

    private static final Logger LOGGER = LogManager.getLogger(RollingUpdateST.class);

    static final String NAMESPACE = "rolling-update-cluster-test";

    private static final Pattern ZK_SERVER_STATE = Pattern.compile("zk_server_state\\s+(leader|follower)");

    @ParallelNamespaceTest
    @Tag(ROLLING_UPDATE)
    void testRecoveryDuringZookeeperRollingUpdate(ExtensionContext extensionContext) throws Exception {
        final String namespaceName = extensionContext.getStore(ExtensionContext.Namespace.GLOBAL).get(Constants.NAMESPACE_KEY).toString();
        final String clusterName = mapWithClusterNames.get(extensionContext.getDisplayName());
        final String topicName = mapWithTestTopics.get(extensionContext.getDisplayName());
        final String userName = mapWithTestUsers.get(extensionContext.getDisplayName());
        final String kafkaClientsName = mapWithKafkaClientNames.get(extensionContext.getDisplayName());

        resourceManager.createResource(extensionContext,
            KafkaTemplates.kafkaPersistent(clusterName, 3, 3).build(),
            KafkaTopicTemplates.topic(clusterName, topicName, 2, 2).build());

        KafkaUser user = KafkaUserTemplates.tlsUser(clusterName, userName).build();

        resourceManager.createResource(extensionContext,  user);
        resourceManager.createResource(extensionContext, false, KafkaClientsTemplates.kafkaClients(true, kafkaClientsName, user).build());

        final String defaultKafkaClientsPodName =
            PodUtils.getPodsByPrefixInNameWithDynamicWait(namespaceName, kafkaClientsName).get(0).getMetadata().getName();

        InternalKafkaClient internalKafkaClient = new InternalKafkaClient.Builder()
            .withUsingPodName(defaultKafkaClientsPodName)
            .withTopicName(topicName)
            .withNamespaceName(namespaceName)
            .withClusterName(clusterName)
            .withMessageCount(MESSAGE_COUNT)
            .withKafkaUsername(userName)
            .withListenerName(Constants.TLS_LISTENER_DEFAULT_NAME)
            .build();

        internalKafkaClient.produceTlsMessagesUntilOperationIsSuccessful(MESSAGE_COUNT);

        LOGGER.info("Update resources for pods");

        KafkaResource.replaceKafkaResourceInSpecificNamespace(clusterName, k -> {
            k.getSpec()
                .getZookeeper()
                .setResources(new ResourceRequirementsBuilder()
                    .addToRequests("cpu", new Quantity("100000m"))
                    .build());
        }, namespaceName);

        ClientUtils.waitUntilClientReceivedMessagesTls(internalKafkaClient, MESSAGE_COUNT);

        PodUtils.waitForPendingPod(namespaceName, KafkaResources.zookeeperStatefulSetName(clusterName));
        LOGGER.info("Verifying stability of zookeeper pods except the one, which is in pending phase");
        PodUtils.verifyThatRunningPodsAreStable(namespaceName, KafkaResources.zookeeperStatefulSetName(clusterName));

        LOGGER.info("Verifying stability of kafka pods");
        PodUtils.verifyThatRunningPodsAreStable(namespaceName, KafkaResources.kafkaStatefulSetName(clusterName));

        KafkaResource.replaceKafkaResourceInSpecificNamespace(clusterName, k -> {
            k.getSpec()
                .getZookeeper()
                .setResources(new ResourceRequirementsBuilder()
                    .addToRequests("cpu", new Quantity("200m"))
                    .build());
        }, namespaceName);

        StatefulSetUtils.waitForAllStatefulSetPodsReady(namespaceName, KafkaResources.zookeeperStatefulSetName(clusterName), 3, ResourceOperation.getTimeoutForResourceReadiness(Constants.STATEFUL_SET));

        internalKafkaClient = internalKafkaClient.toBuilder()
            .withConsumerGroupName(ClientUtils.generateRandomConsumerGroup())
            .build();

        int received = internalKafkaClient.receiveMessagesTls();
        assertThat(received, is(MESSAGE_COUNT));

        // Create new topic to ensure, that ZK is working properly
        String newTopicName = KafkaTopicUtils.generateRandomNameOfTopic();

        resourceManager.createResource(extensionContext, KafkaTopicTemplates.topic(clusterName, newTopicName).build());

        internalKafkaClient = internalKafkaClient.toBuilder()
            .withTopicName(newTopicName)
            .withConsumerGroupName(ClientUtils.generateRandomConsumerGroup())
            .build();

        int sent = internalKafkaClient.sendMessagesTls();
        assertThat(sent, is(MESSAGE_COUNT));
        received = internalKafkaClient.receiveMessagesTls();
        assertThat(received, is(sent));
    }

    @ParallelNamespaceTest
    @Tag(ROLLING_UPDATE)
    void testRecoveryDuringKafkaRollingUpdate(ExtensionContext extensionContext) throws Exception {
        final String namespaceName = extensionContext.getStore(ExtensionContext.Namespace.GLOBAL).get(Constants.NAMESPACE_KEY).toString();
        final String clusterName = mapWithClusterNames.get(extensionContext.getDisplayName());
        final String topicName = mapWithTestTopics.get(extensionContext.getDisplayName());
        final String userName = mapWithTestUsers.get(extensionContext.getDisplayName());
        final String kafkaClientsName = mapWithKafkaClientNames.get(extensionContext.getDisplayName());

        resourceManager.createResource(extensionContext, KafkaTemplates.kafkaPersistent(clusterName, 3, 3).build());
        resourceManager.createResource(extensionContext, KafkaTopicTemplates.topic(clusterName, topicName, 2, 3).build());

        KafkaUser user = KafkaUserTemplates.tlsUser(clusterName, userName).build();

        resourceManager.createResource(extensionContext, user);
        resourceManager.createResource(extensionContext, false, KafkaClientsTemplates.kafkaClients(true, kafkaClientsName, user).build());

        final String defaultKafkaClientsPodName =
            PodUtils.getPodsByPrefixInNameWithDynamicWait(namespaceName, kafkaClientsName).get(0).getMetadata().getName();

        InternalKafkaClient internalKafkaClient = new InternalKafkaClient.Builder()
            .withUsingPodName(defaultKafkaClientsPodName)
            .withTopicName(topicName)
            .withNamespaceName(namespaceName)
            .withClusterName(clusterName)
            .withMessageCount(MESSAGE_COUNT)
            .withKafkaUsername(userName)
            .withListenerName(Constants.TLS_LISTENER_DEFAULT_NAME)
            .build();

        internalKafkaClient.produceTlsMessagesUntilOperationIsSuccessful(MESSAGE_COUNT);

        LOGGER.info("Update resources for pods");

        KafkaResource.replaceKafkaResourceInSpecificNamespace(clusterName, k -> {
            k.getSpec()
                .getKafka()
                .setResources(new ResourceRequirementsBuilder()
                    .addToRequests("cpu", new Quantity("100000m"))
                    .build());
        }, namespaceName);

        ClientUtils.waitUntilClientReceivedMessagesTls(internalKafkaClient, MESSAGE_COUNT);

        internalKafkaClient = internalKafkaClient.toBuilder()
            .withConsumerGroupName(ClientUtils.generateRandomConsumerGroup())
            .build();

        PodUtils.waitForPendingPod(namespaceName, KafkaResources.kafkaStatefulSetName(clusterName));
        LOGGER.info("Verifying stability of kafka pods except the one, which is in pending phase");
        PodUtils.verifyThatRunningPodsAreStable(namespaceName, KafkaResources.kafkaStatefulSetName(clusterName));

        LOGGER.info("Verifying stability of zookeeper pods");
        PodUtils.verifyThatRunningPodsAreStable(namespaceName, KafkaResources.zookeeperStatefulSetName(clusterName));

        ClientUtils.waitUntilClientReceivedMessagesTls(internalKafkaClient, MESSAGE_COUNT);

        KafkaResource.replaceKafkaResourceInSpecificNamespace(clusterName, k -> {
            k.getSpec()
                .getKafka()
                .setResources(new ResourceRequirementsBuilder()
                    .addToRequests("cpu", new Quantity("200m"))
                    .build());
        }, namespaceName);

        // This might need to wait for the previous reconciliation to timeout and for the KafkaRoller to timeout.
        // Therefore we use longer timeout.
        StatefulSetUtils.waitForAllStatefulSetPodsReady(namespaceName, KafkaResources.kafkaStatefulSetName(clusterName), 3, Duration.ofMinutes(12).toMillis());

        internalKafkaClient = internalKafkaClient.toBuilder()
            .withConsumerGroupName(ClientUtils.generateRandomConsumerGroup())
            .build();

        ClientUtils.waitUntilClientReceivedMessagesTls(internalKafkaClient, MESSAGE_COUNT);

        // Create new topic to ensure, that ZK is working properly
        String newTopicName = KafkaTopicUtils.generateRandomNameOfTopic();

        resourceManager.createResource(extensionContext, KafkaTopicTemplates.topic(clusterName, newTopicName).build());

        internalKafkaClient = internalKafkaClient.toBuilder()
            .withTopicName(newTopicName)
            .withConsumerGroupName(ClientUtils.generateRandomConsumerGroup())
            .build();

        internalKafkaClient.produceAndConsumesTlsMessagesUntilBothOperationsAreSuccessful();
    }

    @ParallelNamespaceTest
    @Tag(ACCEPTANCE)
    @Tag(SCALABILITY)
    void testKafkaAndZookeeperScaleUpScaleDown(ExtensionContext extensionContext) {
        final String namespaceName = extensionContext.getStore(ExtensionContext.Namespace.GLOBAL).get(Constants.NAMESPACE_KEY).toString();
        final String clusterName = mapWithClusterNames.get(extensionContext.getDisplayName());
        final String topicName = mapWithTestTopics.get(extensionContext.getDisplayName());
        final String userName = mapWithTestUsers.get(extensionContext.getDisplayName());
        final String kafkaClientsName = mapWithKafkaClientNames.get(extensionContext.getDisplayName());

        String operationId = timeMeasuringSystem.startTimeMeasuring(Operation.CLUSTER_RECOVERY, extensionContext.getRequiredTestClass().getName(), extensionContext.getDisplayName());

        resourceManager.createResource(extensionContext, KafkaTemplates.kafkaPersistent(clusterName, 3, 3)
            .editSpec()
                .editKafka()
                    .addToConfig(singletonMap("default.replication.factor", 1))
                    .addToConfig("auto.create.topics.enable", "false")
                .endKafka()
            .endSpec()
            .build());

        Map<String, String> kafkaPods = StatefulSetUtils.ssSnapshot(KafkaResources.kafkaStatefulSetName(clusterName));

        KafkaUser user = KafkaUserTemplates.tlsUser(clusterName, userName).build();
        resourceManager.createResource(extensionContext, user);

        testDockerImagesForKafkaCluster(clusterName, NAMESPACE, namespaceName, 3, 1, false);
        // kafka cluster already deployed

        LOGGER.info("Running kafkaScaleUpScaleDown {}", clusterName);

        final int initialReplicas = kubeClient(namespaceName).getStatefulSet(KafkaResources.kafkaStatefulSetName(clusterName)).getStatus().getReplicas();

        assertEquals(3, initialReplicas);

        resourceManager.createResource(extensionContext, KafkaTopicTemplates.topic(clusterName, topicName, 3, initialReplicas, initialReplicas).build());
        resourceManager.createResource(extensionContext, false, KafkaClientsTemplates.kafkaClients(true, kafkaClientsName, user).build());

        final String defaultKafkaClientsPodName =
            PodUtils.getPodsByPrefixInNameWithDynamicWait(namespaceName, kafkaClientsName).get(0).getMetadata().getName();

        InternalKafkaClient internalKafkaClient = new InternalKafkaClient.Builder()
            .withUsingPodName(defaultKafkaClientsPodName)
            .withTopicName(topicName)
            .withNamespaceName(namespaceName)
            .withClusterName(clusterName)
            .withMessageCount(MESSAGE_COUNT)
            .withKafkaUsername(userName)
            .withListenerName(Constants.TLS_LISTENER_DEFAULT_NAME)
            .build();

        internalKafkaClient.produceAndConsumesTlsMessagesUntilBothOperationsAreSuccessful();

        // scale up
        final int scaleTo = initialReplicas + 4;
        LOGGER.info("Scale up Kafka to {}", scaleTo);
        // Create snapshot of current cluster
        String kafkaStsName = kafkaStatefulSetName(clusterName);

        KafkaResource.replaceKafkaResourceInSpecificNamespace(clusterName, kafka -> {
            kafka.getSpec().getKafka().setReplicas(scaleTo);
        }, namespaceName);

        kafkaPods = StatefulSetUtils.waitTillSsHasRolled(namespaceName, kafkaStsName, scaleTo, kafkaPods);

        LOGGER.info("Kafka scale up to {} finished", scaleTo);

        internalKafkaClient = internalKafkaClient.toBuilder()
            .withConsumerGroupName(ClientUtils.generateRandomConsumerGroup())
            .build();

        internalKafkaClient.consumesTlsMessagesUntilOperationIsSuccessful(MESSAGE_COUNT);

        //Test that CO doesn't have any exceptions in log
        timeMeasuringSystem.stopOperation(operationId, extensionContext.getRequiredTestClass().getName(), extensionContext.getDisplayName());

        assertThat((int) kubeClient(namespaceName).listPersistentVolumeClaims().stream().filter(
            pvc -> pvc.getMetadata().getName().contains(KafkaResources.kafkaStatefulSetName(clusterName))).count(), is(scaleTo));

        final int zookeeperScaleTo = initialReplicas + 2;
        LOGGER.info("Scale up Zookeeper to {}", zookeeperScaleTo);
        KafkaResource.replaceKafkaResourceInSpecificNamespace(clusterName, k -> k.getSpec().getZookeeper().setReplicas(zookeeperScaleTo), namespaceName);
        StatefulSetUtils.waitForAllStatefulSetPodsReady(namespaceName, KafkaResources.zookeeperStatefulSetName(clusterName), zookeeperScaleTo, ResourceOperation.getTimeoutForResourceReadiness(STATEFUL_SET));
        LOGGER.info("Kafka scale up to {} finished", zookeeperScaleTo);

        internalKafkaClient = internalKafkaClient.toBuilder()
            .withConsumerGroupName(ClientUtils.generateRandomConsumerGroup())
            .build();

        internalKafkaClient.consumesTlsMessagesUntilOperationIsSuccessful(MESSAGE_COUNT);

        // scale down
        LOGGER.info("Scale down Kafka to {}", initialReplicas);
        operationId = timeMeasuringSystem.startTimeMeasuring(Operation.SCALE_DOWN, extensionContext.getRequiredTestClass().getName(), extensionContext.getDisplayName());
        KafkaResource.replaceKafkaResourceInSpecificNamespace(clusterName, k -> k.getSpec().getKafka().setReplicas(initialReplicas), namespaceName);

        StatefulSetUtils.waitTillSsHasRolled(namespaceName, kafkaStsName, initialReplicas, kafkaPods);

        LOGGER.info("Kafka scale down to {} finished", initialReplicas);
        //Test that CO doesn't have any exceptions in log
        timeMeasuringSystem.stopOperation(operationId, extensionContext.getRequiredTestClass().getName(), extensionContext.getDisplayName());

        internalKafkaClient = internalKafkaClient.toBuilder()
            .withConsumerGroupName(ClientUtils.generateRandomConsumerGroup())
            .build();

        internalKafkaClient.consumesTlsMessagesUntilOperationIsSuccessful(MESSAGE_COUNT);

        assertThat(kubeClient(namespaceName).listPersistentVolumeClaims(namespaceName, clusterName).stream()
            .filter(pvc -> pvc.getMetadata().getName().contains("data-" + KafkaResources.kafkaStatefulSetName(clusterName))).collect(Collectors.toList()).size(), is(initialReplicas));

        // Create new topic to ensure, that ZK is working properly
        String newTopicName = KafkaTopicUtils.generateRandomNameOfTopic();

        resourceManager.createResource(extensionContext, KafkaTopicTemplates.topic(clusterName, newTopicName).build());

        internalKafkaClient = internalKafkaClient.toBuilder()
            .withTopicName(newTopicName)
            .withConsumerGroupName(ClientUtils.generateRandomConsumerGroup())
            .build();

        internalKafkaClient.produceAndConsumesTlsMessagesUntilBothOperationsAreSuccessful();
    }

    @ParallelNamespaceTest
    @Tag(SCALABILITY)
    void testZookeeperScaleUpScaleDown(ExtensionContext extensionContext) {
        final String namespaceName = extensionContext.getStore(ExtensionContext.Namespace.GLOBAL).get(Constants.NAMESPACE_KEY).toString();
        final String clusterName = mapWithClusterNames.get(extensionContext.getDisplayName());
        final String topicName = mapWithTestTopics.get(extensionContext.getDisplayName());
        final String userName = mapWithTestUsers.get(extensionContext.getDisplayName());
        final String kafkaClientsName = mapWithKafkaClientNames.get(extensionContext.getDisplayName());

        String operationId = timeMeasuringSystem.startTimeMeasuring(Operation.CLUSTER_RECOVERY, extensionContext.getRequiredTestClass().getName(), extensionContext.getDisplayName());

        resourceManager.createResource(extensionContext, KafkaTemplates.kafkaPersistent(clusterName, 3, 3).build());
        resourceManager.createResource(extensionContext, KafkaTopicTemplates.topic(clusterName, topicName).build());

        KafkaUser user = KafkaUserTemplates.tlsUser(clusterName, userName).build();

        resourceManager.createResource(extensionContext, user);

        // kafka cluster already deployed
        LOGGER.info("Running zookeeperScaleUpScaleDown with cluster {}", clusterName);
        final int initialZkReplicas = kubeClient(namespaceName).getStatefulSet(KafkaResources.zookeeperStatefulSetName(clusterName)).getStatus().getReplicas();
        assertThat(initialZkReplicas, is(3));

        resourceManager.createResource(extensionContext, false, KafkaClientsTemplates.kafkaClients(true, kafkaClientsName, user).build());

        final String defaultKafkaClientsPodName =
            PodUtils.getPodsByPrefixInNameWithDynamicWait(namespaceName, kafkaClientsName).get(0).getMetadata().getName();

        InternalKafkaClient internalKafkaClient = new InternalKafkaClient.Builder()
            .withUsingPodName(defaultKafkaClientsPodName)
            .withTopicName(topicName)
            .withNamespaceName(namespaceName)
            .withClusterName(clusterName)
            .withMessageCount(MESSAGE_COUNT)
            .withKafkaUsername(userName)
            .withListenerName(Constants.TLS_LISTENER_DEFAULT_NAME)
            .build();

        internalKafkaClient.produceTlsMessagesUntilOperationIsSuccessful(MESSAGE_COUNT);

        final int scaleZkTo = initialZkReplicas + 4;
        final List<String> newZkPodNames = new ArrayList<String>() {{
                for (int i = initialZkReplicas; i < scaleZkTo; i++) {
                    add(KafkaResources.zookeeperPodName(clusterName, i));
                }
            }};

        LOGGER.info("Scale up Zookeeper to {}", scaleZkTo);
        KafkaResource.replaceKafkaResourceInSpecificNamespace(clusterName, k -> k.getSpec().getZookeeper().setReplicas(scaleZkTo), namespaceName);
        int received = internalKafkaClient.receiveMessagesTls();
        assertThat(received, is(MESSAGE_COUNT));

        StatefulSetUtils.waitForAllStatefulSetPodsReady(namespaceName, KafkaResources.zookeeperStatefulSetName(clusterName), scaleZkTo, ResourceOperation.getTimeoutForResourceReadiness(STATEFUL_SET));
        // check the new node is either in leader or follower state
        KafkaUtils.waitForZkMntr(namespaceName, clusterName, ZK_SERVER_STATE, 0, 1, 2, 3, 4, 5, 6);

        //Test that CO doesn't have any exceptions in log
        timeMeasuringSystem.stopOperation(operationId, extensionContext.getRequiredTestClass().getName(), extensionContext.getDisplayName());

        internalKafkaClient = internalKafkaClient.toBuilder()
            .withConsumerGroupName(ClientUtils.generateRandomConsumerGroup())
            .build();

        internalKafkaClient.consumesTlsMessagesUntilOperationIsSuccessful(MESSAGE_COUNT);

        // Create new topic to ensure, that ZK is working properly
        String scaleUpTopicName = KafkaTopicUtils.generateRandomNameOfTopic();

        resourceManager.createResource(extensionContext, KafkaTopicTemplates.topic(clusterName, scaleUpTopicName, 1, 1).build());

        internalKafkaClient = internalKafkaClient.toBuilder()
            .withTopicName(scaleUpTopicName)
            .withConsumerGroupName(ClientUtils.generateRandomConsumerGroup())
            .build();

        internalKafkaClient.produceAndConsumesTlsMessagesUntilBothOperationsAreSuccessful();
        // scale down
        LOGGER.info("Scale down Zookeeper to {}", initialZkReplicas);
        // Get zk-3 uid before deletion
        String uid = kubeClient(namespaceName).getPodUid(newZkPodNames.get(3));

        operationId = timeMeasuringSystem.startTimeMeasuring(Operation.SCALE_DOWN, extensionContext.getRequiredTestClass().getName(), extensionContext.getDisplayName());
        KafkaResource.replaceKafkaResourceInSpecificNamespace(clusterName, k -> k.getSpec().getZookeeper().setReplicas(initialZkReplicas), namespaceName);

        StatefulSetUtils.waitForAllStatefulSetPodsReady(namespaceName, KafkaResources.zookeeperStatefulSetName(clusterName), initialZkReplicas, ResourceOperation.getTimeoutForResourceReadiness(STATEFUL_SET));

        internalKafkaClient = internalKafkaClient.toBuilder()
            .withConsumerGroupName(ClientUtils.generateRandomConsumerGroup())
            .build();

        // Wait for one zk pods will became leader and others follower state
        KafkaUtils.waitForZkMntr(namespaceName, clusterName, ZK_SERVER_STATE, 0, 1, 2);

        internalKafkaClient.consumesTlsMessagesUntilOperationIsSuccessful(MESSAGE_COUNT);

        // Create new topic to ensure, that ZK is working properly
        String scaleDownTopicName = KafkaTopicUtils.generateRandomNameOfTopic();
        resourceManager.createResource(extensionContext, KafkaTopicTemplates.topic(clusterName, scaleDownTopicName, 1, 1).build());

        internalKafkaClient = internalKafkaClient.toBuilder()
            .withTopicName(scaleDownTopicName)
            .withConsumerGroupName(ClientUtils.generateRandomConsumerGroup())
            .build();

        internalKafkaClient.produceAndConsumesTlsMessagesUntilBothOperationsAreSuccessful();

        //Test that the second pod has event 'Killing'
        assertThat(kubeClient(namespaceName).listEventsByResourceUid(uid), hasAllOfReasons(Killing));
        // Stop measuring
        timeMeasuringSystem.stopOperation(operationId, extensionContext.getRequiredTestClass().getName(), extensionContext.getDisplayName());
    }

    @ParallelNamespaceTest
    @Tag(ROLLING_UPDATE)
    void testBrokerConfigurationChangeTriggerRollingUpdate(ExtensionContext extensionContext) {
        final String namespaceName = extensionContext.getStore(ExtensionContext.Namespace.GLOBAL).get(Constants.NAMESPACE_KEY).toString();
        final String clusterName = mapWithClusterNames.get(extensionContext.getDisplayName());

        resourceManager.createResource(extensionContext, KafkaTemplates.kafkaPersistent(clusterName, 3, 3).build());

        Map<String, String> kafkaPods = StatefulSetUtils.ssSnapshot(namespaceName, KafkaResources.kafkaStatefulSetName(clusterName));
        Map<String, String> zkPods = StatefulSetUtils.ssSnapshot(namespaceName, KafkaResources.zookeeperStatefulSetName(clusterName));

        // Changes to readiness probe should trigger a rolling update
        KafkaResource.replaceKafkaResourceInSpecificNamespace(clusterName, kafka -> {
            kafka.getSpec().getKafka().setReadinessProbe(new ProbeBuilder().withTimeoutSeconds(6).build());
        }, namespaceName);

        StatefulSetUtils.waitTillSsHasRolled(namespaceName, KafkaResources.kafkaStatefulSetName(clusterName), 3, kafkaPods);
        assertThat(StatefulSetUtils.ssSnapshot(namespaceName, KafkaResources.zookeeperStatefulSetName(clusterName)), is(zkPods));
    }

    @ParallelNamespaceTest
    @Tag(ROLLING_UPDATE)
    void testManualKafkaConfigMapChangeDontTriggerRollingUpdate(ExtensionContext extensionContext) {
        final String namespaceName = extensionContext.getStore(ExtensionContext.Namespace.GLOBAL).get(Constants.NAMESPACE_KEY).toString();
        final String clusterName = mapWithClusterNames.get(extensionContext.getDisplayName());

        resourceManager.createResource(extensionContext, KafkaTemplates.kafkaPersistent(clusterName, 3, 3).build());

        Map<String, String> kafkaPods = StatefulSetUtils.ssSnapshot(namespaceName, KafkaResources.kafkaStatefulSetName(clusterName));
        Map<String, String> zkPods = StatefulSetUtils.ssSnapshot(namespaceName, KafkaResources.zookeeperStatefulSetName(clusterName));

        ConfigMap configMap = kubeClient(namespaceName).getConfigMap(KafkaResources.kafkaMetricsAndLogConfigMapName(clusterName));
        configMap.getData().put("new.kafka.config", "new.config.value");
        kubeClient(namespaceName).getClient().configMaps().inNamespace(namespaceName).createOrReplace(configMap);

        PodUtils.verifyThatRunningPodsAreStable(namespaceName, clusterName);

        assertThat(StatefulSetUtils.ssSnapshot(namespaceName, KafkaResources.zookeeperStatefulSetName(clusterName)), is(zkPods));
        assertThat(StatefulSetUtils.ssSnapshot(namespaceName, KafkaResources.kafkaStatefulSetName(clusterName)), is(kafkaPods));
    }

    @ParallelNamespaceTest
    @Tag(ROLLING_UPDATE)
    void testExternalLoggingChangeTriggerRollingUpdate(ExtensionContext extensionContext) {
        final String namespaceName = extensionContext.getStore(ExtensionContext.Namespace.GLOBAL).get(Constants.NAMESPACE_KEY).toString();
        final String clusterName = mapWithClusterNames.get(extensionContext.getDisplayName());

        // EO dynamic logging is tested in io.strimzi.systemtest.log.LoggingChangeST.testDynamicallySetEOloggingLevels
        resourceManager.createResource(extensionContext, KafkaTemplates.kafkaEphemeral(clusterName, 3, 3).build());

        Map<String, String> kafkaPods = StatefulSetUtils.ssSnapshot(namespaceName, KafkaResources.kafkaStatefulSetName(clusterName));
        Map<String, String> zkPods = StatefulSetUtils.ssSnapshot(namespaceName, KafkaResources.zookeeperStatefulSetName(clusterName));

        String loggersConfig = "log4j.appender.CONSOLE=org.apache.log4j.ConsoleAppender\n" +
                "log4j.appender.CONSOLE.layout=org.apache.log4j.PatternLayout\n" +
                "log4j.appender.CONSOLE.layout.ConversionPattern=%d{ISO8601} %p %m (%c) [%t]\n" +
                "kafka.root.logger.level=INFO\n" +
                "log4j.rootLogger=${kafka.root.logger.level}, CONSOLE\n" +
                "log4j.logger.org.I0Itec.zkclient.ZkClient=INFO\n" +
                "log4j.logger.org.apache.zookeeper=INFO\n" +
                "log4j.logger.kafka=INFO\n" +
                "log4j.logger.org.apache.kafka=INFO\n" +
                "log4j.logger.kafka.request.logger=WARN, CONSOLE\n" +
                "log4j.logger.kafka.network.Processor=INFO\n" +
                "log4j.logger.kafka.server.KafkaApis=INFO\n" +
                "log4j.logger.kafka.network.RequestChannel$=INFO\n" +
                "log4j.logger.kafka.controller=INFO\n" +
                "log4j.logger.kafka.log.LogCleaner=INFO\n" +
                "log4j.logger.state.change.logger=TRACE\n" +
                "log4j.logger.kafka.authorizer.logger=INFO";

        String configMapLoggersName = "loggers-config-map";
        ConfigMap configMapLoggers = new ConfigMapBuilder()
                .withNewMetadata()
                    .withName(configMapLoggersName)
                .endMetadata()
                .addToData("log4j-custom.properties", loggersConfig)
                .build();

        ConfigMapKeySelector log4jLoggimgCMselector = new ConfigMapKeySelectorBuilder()
                .withName(configMapLoggersName)
                .withKey("log4j-custom.properties")
                .build();

        kubeClient().getClient().configMaps().inNamespace(namespaceName).createOrReplace(configMapLoggers);

        KafkaResource.replaceKafkaResourceInSpecificNamespace(clusterName, kafka -> {
            kafka.getSpec().getKafka().setLogging(new ExternalLoggingBuilder()
                    .withNewValueFrom()
                        .withConfigMapKeyRef(log4jLoggimgCMselector)
                    .endValueFrom()
                    .build());
            kafka.getSpec().getZookeeper().setLogging(new ExternalLoggingBuilder()
                    .withNewValueFrom()
                        .withConfigMapKeyRef(log4jLoggimgCMselector)
                    .endValueFrom()
                    .build());
        }, namespaceName);

        zkPods = StatefulSetUtils.waitTillSsHasRolled(namespaceName, KafkaResources.zookeeperStatefulSetName(clusterName), 3, zkPods);
        kafkaPods = StatefulSetUtils.waitTillSsHasRolled(namespaceName, KafkaResources.kafkaStatefulSetName(clusterName), 3, kafkaPods);

        configMapLoggers.getData().put("log4j-custom.properties", loggersConfig.replace("%p %m (%c) [%t]", "%p %m (%c) [%t]%n"));
        kubeClient().getClient().configMaps().inNamespace(namespaceName).createOrReplace(configMapLoggers);

        StatefulSetUtils.waitTillSsHasRolled(namespaceName, KafkaResources.zookeeperStatefulSetName(clusterName), 3, zkPods);
        StatefulSetUtils.waitTillSsHasRolled(namespaceName, KafkaResources.kafkaStatefulSetName(clusterName), 3, kafkaPods);
    }

    @ParallelNamespaceTest
    @Tag(ROLLING_UPDATE)
    void testClusterOperatorFinishAllRollingUpdates(ExtensionContext extensionContext) {
        final String namespaceName = extensionContext.getStore(ExtensionContext.Namespace.GLOBAL).get(Constants.NAMESPACE_KEY).toString();
        final String clusterName = mapWithClusterNames.get(extensionContext.getDisplayName());

        resourceManager.createResource(extensionContext, KafkaTemplates.kafkaPersistent(clusterName, 3, 3).build());

        Map<String, String> kafkaPods = StatefulSetUtils.ssSnapshot(namespaceName, KafkaResources.kafkaStatefulSetName(clusterName));
        Map<String, String> zkPods = StatefulSetUtils.ssSnapshot(namespaceName, KafkaResources.zookeeperStatefulSetName(clusterName));

        // Changes to readiness probe should trigger a rolling update
        KafkaResource.replaceKafkaResourceInSpecificNamespace(clusterName, kafka -> {
            kafka.getSpec().getKafka().setReadinessProbe(new ProbeBuilder().withTimeoutSeconds(6).build());
            kafka.getSpec().getZookeeper().setReadinessProbe(new ProbeBuilder().withTimeoutSeconds(6).build());
        }, namespaceName);

        TestUtils.waitFor("rolling update starts", Constants.GLOBAL_POLL_INTERVAL, Constants.GLOBAL_STATUS_TIMEOUT,
            () -> kubeClient(namespaceName).listPods().stream().filter(pod -> pod.getStatus().getPhase().equals("Running"))
                    .map(pod -> pod.getStatus().getPhase()).collect(Collectors.toList()).size() < kubeClient().listPods().size());

        LabelSelector coLabelSelector = kubeClient(NAMESPACE).getDeployment(NAMESPACE, ResourceManager.getCoDeploymentName()).getSpec().getSelector();
        LOGGER.info("Deleting Cluster Operator pod with labels {}", coLabelSelector);
        kubeClient().deletePod(coLabelSelector);
        LOGGER.info("Cluster Operator pod deleted");

        StatefulSetUtils.waitTillSsHasRolled(namespaceName, KafkaResources.zookeeperStatefulSetName(clusterName), 3, zkPods);

        TestUtils.waitFor("rolling update starts", Constants.GLOBAL_POLL_INTERVAL, Constants.GLOBAL_STATUS_TIMEOUT,
            () -> kubeClient(namespaceName).listPods().stream().map(pod -> pod.getStatus().getPhase()).collect(Collectors.toList()).contains("Pending"));

        LOGGER.info("Deleting Cluster Operator pod with labels {}", coLabelSelector);
        kubeClient(namespaceName).deletePod(coLabelSelector);
        LOGGER.info("Cluster Operator pod deleted");

        StatefulSetUtils.waitTillSsHasRolled(namespaceName, KafkaResources.kafkaStatefulSetName(clusterName), 3, kafkaPods);
    }

    @IsolatedTest
    @Tag(ROLLING_UPDATE)
    @SuppressWarnings("checkstyle:MethodLength")
    void testMetricsChange(ExtensionContext extensionContext) throws JsonProcessingException {
        final String clusterName = mapWithClusterNames.get(extensionContext.getDisplayName());
        final String kafkaClientsName = mapWithKafkaClientNames.get(extensionContext.getDisplayName());

        //Kafka
        Map<String, Object> kafkaRule = new HashMap<>();
        kafkaRule.put("pattern", "kafka.(\\w+)<type=(.+), name=(.+)><>Count");
        kafkaRule.put("name", "kafka_$1_$2_$3_count");
        kafkaRule.put("type", "COUNTER");

        Map<String, Object> kafkaMetrics = new HashMap<>();
        kafkaMetrics.put("lowercaseOutputName", true);
        kafkaMetrics.put("rules", Collections.singletonList(kafkaRule));

        String metricsCMNameK = "k-metrics-cm";

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        String yaml = mapper.writeValueAsString(kafkaMetrics);
        ConfigMap metricsCMK = new ConfigMapBuilder()
                .withNewMetadata()
                .withName(metricsCMNameK)
                .endMetadata()
                .withData(singletonMap("metrics-config.yml", yaml))
                .build();

        JmxPrometheusExporterMetrics kafkaMetricsConfig = new JmxPrometheusExporterMetricsBuilder()
                .withNewValueFrom()
                    .withConfigMapKeyRef(new ConfigMapKeySelectorBuilder()
                            .withName(metricsCMNameK)
                            .withKey("metrics-config.yml")
                            .withOptional(true)
                            .build())
                .endValueFrom()
                .build();

        //Zookeeper
        Map<String, Object> zookeeperLabels = new HashMap<>();
        zookeeperLabels.put("replicaId", "$2");

        Map<String, Object> zookeeperRule = new HashMap<>();
        zookeeperRule.put("labels", zookeeperLabels);
        zookeeperRule.put("name", "zookeeper_$3");
        zookeeperRule.put("pattern", "org.apache.ZooKeeperService<name0=ReplicatedServer_id(\\d+), name1=replica.(\\d+)><>(\\w+)");

        Map<String, Object> zookeeperMetrics = new HashMap<>();
        zookeeperMetrics.put("lowercaseOutputName", true);
        zookeeperMetrics.put("rules", Collections.singletonList(zookeeperRule));

        String metricsCMNameZk = "zk-metrics-cm";
        ConfigMap metricsCMZk = new ConfigMapBuilder()
                .withNewMetadata()
                .withName(metricsCMNameZk)
                .endMetadata()
                .withData(singletonMap("metrics-config.yml", mapper.writeValueAsString(zookeeperMetrics)))
                .build();

        JmxPrometheusExporterMetrics zkMetricsConfig = new JmxPrometheusExporterMetricsBuilder()
                .withNewValueFrom()
                    .withConfigMapKeyRef(new ConfigMapKeySelectorBuilder()
                            .withName(metricsCMNameZk)
                            .withKey("metrics-config.yml")
                            .withOptional(true)
                            .build())
                .endValueFrom()
                .build();

        kubeClient(NAMESPACE).getClient().configMaps().inNamespace(NAMESPACE).createOrReplace(metricsCMK);
        kubeClient(NAMESPACE).getClient().configMaps().inNamespace(NAMESPACE).createOrReplace(metricsCMZk);

        resourceManager.createResource(extensionContext, KafkaTemplates.kafkaEphemeral(clusterName, 3, 3)
            .editSpec()
                .editKafka()
                    .withMetricsConfig(kafkaMetricsConfig)
                .endKafka()
                .editOrNewZookeeper()
                    .withMetricsConfig(zkMetricsConfig)
                .endZookeeper()
                .withNewKafkaExporter()
                .endKafkaExporter()
            .endSpec()
            .build());

        Map<String, String> kafkaPods = StatefulSetUtils.ssSnapshot(NAMESPACE, KafkaResources.kafkaStatefulSetName(clusterName));
        Map<String, String> zkPods = StatefulSetUtils.ssSnapshot(NAMESPACE, KafkaResources.zookeeperStatefulSetName(clusterName));

        resourceManager.createResource(extensionContext, false, KafkaClientsTemplates.kafkaClients(false, kafkaClientsName).build());

        String metricsScraperPodName = PodUtils.getPodsByPrefixInNameWithDynamicWait(NAMESPACE, kafkaClientsName).get(0).getMetadata().getName();

        LOGGER.info("Check if metrics are present in pod of Kafka and Zookeeper");
        HashMap<String, String> kafkaMetricsOutput = MetricsUtils.collectKafkaPodsMetrics(NAMESPACE, metricsScraperPodName, clusterName);
        HashMap<String, String> zkMetricsOutput = MetricsUtils.collectZookeeperPodsMetrics(NAMESPACE, metricsScraperPodName, clusterName);

        assertThat(kafkaMetricsOutput.values().toString().contains("kafka_"), is(true));
        assertThat(zkMetricsOutput.values().toString().contains("replicaId"), is(true));

        LOGGER.info("Changing metrics to something else");

        kafkaRule.replace("pattern", "kafka.(\\w+)<type=(.+), name=(.+)><>Count",
                "kafka.(\\w+)<type=(.+), name=(.+)Percent\\w*><>MeanRate");
        kafkaRule.replace("name", "kafka_$1_$2_$3_count", "kafka_$1_$2_$3_percent");
        kafkaRule.replace("type", "COUNTER", "GAUGE");

        zookeeperRule.replace("pattern",
                "org.apache.ZooKeeperService<name0=ReplicatedServer_id(\\d+), name1=replica.(\\d+)><>(\\w+)",
                "org.apache.ZooKeeperService<name0=StandaloneServer_port(\\d+)><>(\\w+)");
        zookeeperRule.replace("name", "zookeeper_$3", "zookeeper_$2");
        zookeeperRule.replace("labels", zookeeperLabels, null);

        metricsCMZk = new ConfigMapBuilder()
                .withNewMetadata()
                .withName(metricsCMNameZk)
                .endMetadata()
                .withData(singletonMap("metrics-config.yml", mapper.writeValueAsString(zookeeperMetrics)))
                .build();

        metricsCMK = new ConfigMapBuilder()
                .withNewMetadata()
                .withName(metricsCMNameK)
                .endMetadata()
                .withData(singletonMap("metrics-config.yml", mapper.writeValueAsString(kafkaMetrics)))
                .build();

        kubeClient(NAMESPACE).getClient().configMaps().inNamespace(NAMESPACE).createOrReplace(metricsCMK);
        kubeClient(NAMESPACE).getClient().configMaps().inNamespace(NAMESPACE).createOrReplace(metricsCMZk);

        PodUtils.verifyThatRunningPodsAreStable(NAMESPACE, KafkaResources.zookeeperStatefulSetName(clusterName));
        PodUtils.verifyThatRunningPodsAreStable(NAMESPACE, KafkaResources.kafkaStatefulSetName(clusterName));

        LOGGER.info("Check if Kafka and Zookeeper pods didn't roll");
        assertThat(StatefulSetUtils.ssSnapshot(NAMESPACE, KafkaResources.zookeeperStatefulSetName(clusterName)), is(zkPods));
        assertThat(StatefulSetUtils.ssSnapshot(NAMESPACE, KafkaResources.kafkaStatefulSetName(clusterName)), is(kafkaPods));

        LOGGER.info("Check if Kafka and Zookeeper metrics are changed");
        ObjectMapper yamlReader = new ObjectMapper(new YAMLFactory());
        String kafkaMetricsConf = kubeClient(NAMESPACE).getClient().configMaps().inNamespace(NAMESPACE).withName(metricsCMNameK).get().getData().get("metrics-config.yml");
        String zkMetricsConf = kubeClient(NAMESPACE).getClient().configMaps().inNamespace(NAMESPACE).withName(metricsCMNameZk).get().getData().get("metrics-config.yml");
        Object kafkaMetricsJsonToYaml = yamlReader.readValue(kafkaMetricsConf, Object.class);
        Object zkMetricsJsonToYaml = yamlReader.readValue(zkMetricsConf, Object.class);
        ObjectMapper jsonWriter = new ObjectMapper();
        assertThat(kubeClient(NAMESPACE).getClient().configMaps().inNamespace(NAMESPACE).withName(KafkaResources.kafkaMetricsAndLogConfigMapName(clusterName)).get().getData().get("metrics-config.yml"),
                is(jsonWriter.writeValueAsString(kafkaMetricsJsonToYaml)));
        assertThat(kubeClient(NAMESPACE).getClient().configMaps().inNamespace(NAMESPACE).withName(KafkaResources.zookeeperMetricsAndLogConfigMapName(clusterName)).get().getData().get("metrics-config.yml"),
                is(jsonWriter.writeValueAsString(zkMetricsJsonToYaml)));

        LOGGER.info("Check if metrics are present in pod of Kafka and Zookeeper");

        kafkaMetricsOutput = MetricsUtils.collectKafkaPodsMetrics(NAMESPACE, metricsScraperPodName, clusterName);
        zkMetricsOutput = MetricsUtils.collectZookeeperPodsMetrics(NAMESPACE, metricsScraperPodName, clusterName);

        assertThat(kafkaMetricsOutput.values().toString().contains("kafka_"), is(true));
        assertThat(zkMetricsOutput.values().toString().contains("replicaId"), is(true));

        LOGGER.info("Removing metrics from Kafka and Zookeeper and setting them to null");

        KafkaResource.replaceKafkaResourceInSpecificNamespace(clusterName, kafka -> {
            kafka.getSpec().getKafka().setMetricsConfig(null);
            kafka.getSpec().getZookeeper().setMetricsConfig(null);
        }, NAMESPACE);

        LOGGER.info("Wait if Kafka and Zookeeper pods will roll");
        StatefulSetUtils.waitTillSsHasRolled(NAMESPACE, KafkaResources.zookeeperStatefulSetName(clusterName), 3, zkPods);
        StatefulSetUtils.waitTillSsHasRolled(NAMESPACE, KafkaResources.kafkaStatefulSetName(clusterName), 3, kafkaPods);

        LOGGER.info("Check if metrics are not existing in pods");

        kafkaMetricsOutput = MetricsUtils.collectKafkaPodsMetrics(NAMESPACE, metricsScraperPodName, clusterName);
        zkMetricsOutput = MetricsUtils.collectZookeeperPodsMetrics(NAMESPACE, metricsScraperPodName, clusterName);

        kafkaMetricsOutput.values().forEach(value -> assertThat(value, is("")));
        zkMetricsOutput.values().forEach(value -> assertThat(value, is("")));
    }

    @BeforeAll
    void setup(ExtensionContext extensionContext) {
        installClusterWideClusterOperator(extensionContext, NAMESPACE, Constants.CO_OPERATION_TIMEOUT_DEFAULT, Constants.RECONCILIATION_INTERVAL);
    }
}
