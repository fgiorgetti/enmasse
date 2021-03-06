/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.listener;

import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;
import org.slf4j.Logger;

import io.enmasse.systemtest.Environment;
import io.enmasse.systemtest.TestTag;
import io.enmasse.systemtest.info.TestInfo;
import io.enmasse.systemtest.logs.CustomLogger;
import io.enmasse.systemtest.logs.GlobalLogCollector;
import io.enmasse.systemtest.manager.IsolatedResourcesManager;
import io.enmasse.systemtest.operator.EnmasseOperatorManager;
import io.enmasse.systemtest.platform.Kubernetes;
import io.enmasse.systemtest.time.TimeMeasuringSystem;
import io.enmasse.systemtest.utils.AddressSpaceUtils;

/**
 * Execution listener useful for safety cleanups of the test environment after test suite execution
 */
public class JunitExecutionListener implements TestExecutionListener {
    private static final Logger LOGGER = CustomLogger.getLogger();

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        TestInfo.getInstance().setTestPlan(testPlan);
        TestInfo.getInstance().printTestClasses();
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {

        var tags = TestInfo.getInstance().getTestRunTags();
        if (tags != null && tags.size() == 1 && tags.get(0).equals(TestTag.FRAMEWORK)) {
            LOGGER.info("Running framework tests, no cleanup performed");
            return;
        }

        final Environment env = Environment.getInstance();

        if (!env.skipCleanup()) {
            // clean up resources
            LOGGER.info("Cleaning resources");
            performCleanup();
        } else {
            LOGGER.warn("Remove address spaces when test run finished - SKIPPED!");
        }

        TimeMeasuringSystem.printAndSaveResults();

        if (!(env.skipCleanup() || env.skipUninstall())) {
            // clean up infrastructure after resources
            LOGGER.info("Uninstalling infrastructure");
            performInfraCleanup();
        }

    }

    private void performCleanup() {
        final Environment env = Environment.getInstance();
        final Kubernetes kube = Kubernetes.getInstance();
        final GlobalLogCollector logCollector = new GlobalLogCollector(kube, env.testLogDir());

        /*
         * Clean up address spaces
         */
        try {
            kube.getAddressSpaceClient().inAnyNamespace().list().getItems().forEach((addrSpace) -> {
                LOGGER.info("address space '{}' will be removed", addrSpace);
                try {
                    AddressSpaceUtils.deleteAddressSpaceAndWait(addrSpace, logCollector);
                    IsolatedResourcesManager.getInstance().tearDown(TestInfo.getInstance().getActualTest());
                } catch (Exception e) {
                    LOGGER.warn("Cleanup failed or no clean is needed");
                }
            });
        } catch (Exception e) {
            LOGGER.warn("Cleanup failed or no clean is needed");
        }

    }

    private void performInfraCleanup() {
        try {
            EnmasseOperatorManager.getInstance().deleteEnmasseOlm();
            EnmasseOperatorManager.getInstance().deleteEnmasseBundle();
        } catch (Exception e) {
            LOGGER.error("Failed", e);
        }
    }

}
