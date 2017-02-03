/*
 * Copyright 2016-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.central.repo;

import java.util.List;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.Session;
import com.google.common.collect.Lists;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.central.repo.SyntheticResultDao.SyntheticResult;
import org.glowroot.central.util.Sessions;
import org.glowroot.common.config.CentralStorageConfig;
import org.glowroot.common.config.ImmutableCentralStorageConfig;
import org.glowroot.common.util.Clock;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class SyntheticResultDaoIT {

    private static Cluster cluster;
    private static Session session;
    private static AgentDao agentDao;
    private static SyntheticResultDao syntheticResultDao;

    @BeforeClass
    public static void setUp() throws Exception {
        SharedSetupRunListener.startCassandra();
        cluster = Clusters.newCluster();
        session = cluster.newSession();
        Sessions.createKeyspaceIfNotExists(session, "glowroot_unit_tests");
        session.execute("use glowroot_unit_tests");
        KeyspaceMetadata keyspace = cluster.getMetadata().getKeyspace("glowroot_unit_tests");

        CentralConfigDao centralConfigDao = new CentralConfigDao(session);
        agentDao = new AgentDao(session);
        ConfigDao configDao = new ConfigDao(session);
        UserDao userDao = new UserDao(session, keyspace);
        RoleDao roleDao = new RoleDao(session, keyspace);
        ConfigRepositoryImpl configRepository = new ConfigRepositoryImpl(agentDao, configDao,
                centralConfigDao, userDao, roleDao);
        CentralStorageConfig storageConfig = configRepository.getCentralStorageConfig();
        configRepository.updateCentralStorageConfig(
                ImmutableCentralStorageConfig
                        .copyOf(storageConfig)
                        .withRollupExpirationHours(0, 0, 0, 0),
                storageConfig.version());
        syntheticResultDao = new SyntheticResultDao(session, configRepository, Clock.systemClock());
    }

    @AfterClass
    public static void tearDown() throws Exception {
        session.close();
        cluster.close();
        SharedSetupRunListener.stopCassandra();
    }

    @Test
    public void shouldRollup() throws Exception {
        syntheticResultDao.truncateAll();
        syntheticResultDao.store("one", "11223344", 60001, SECONDS.toNanos(1), false);
        syntheticResultDao.store("one", "11223344", 120002, SECONDS.toNanos(3), false);
        syntheticResultDao.store("one", "11223344", 360000, SECONDS.toNanos(7), false);

        // check non-rolled up data
        List<SyntheticResult> syntheticResults =
                syntheticResultDao.readSyntheticResults("one", "11223344", 0, 300000, 0);
        assertThat(syntheticResults).hasSize(2);
        SyntheticResult result1 = syntheticResults.get(0);
        SyntheticResult result2 = syntheticResults.get(1);
        assertThat(result1.captureTime()).isEqualTo(60001);
        assertThat(result1.totalDurationNanos()).isEqualTo(SECONDS.toNanos(1));
        assertThat(result1.executionCount()).isEqualTo(1);
        assertThat(result1.errorCount()).isEqualTo(0);
        assertThat(result2.captureTime()).isEqualTo(120002);
        assertThat(result2.totalDurationNanos()).isEqualTo(SECONDS.toNanos(3));
        assertThat(result2.executionCount()).isEqualTo(1);
        assertThat(result2.errorCount()).isEqualTo(0);

        // rollup
        List<Integer> rollupExpirationHours = Lists.newArrayList(
                ImmutableCentralStorageConfig.builder().build().rollupExpirationHours());
        rollupExpirationHours.add(0, rollupExpirationHours.get(0));
        syntheticResultDao.rollup("one");
        syntheticResultDao.rollup("one");
        syntheticResultDao.rollup("one");

        // check rolled-up data after rollup
        syntheticResults = syntheticResultDao.readSyntheticResults("one", "11223344", 0, 300000, 1);
        assertThat(syntheticResults).hasSize(1);
        result1 = syntheticResults.get(0);
        assertThat(result1.captureTime()).isEqualTo(300000);
        assertThat(result1.totalDurationNanos()).isEqualTo(SECONDS.toNanos(4));
        assertThat(result1.executionCount()).isEqualTo(2);
        assertThat(result1.errorCount()).isEqualTo(0);
    }
}
