/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.ha;

import static org.junit.Assert.assertEquals;

import java.io.File;

import org.apache.commons.logging.impl.Log4JLogger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.ha.HAServiceProtocol.HAServiceState;
import org.apache.hadoop.ha.HealthMonitor.State;
import org.apache.hadoop.ha.MiniZKFCCluster.DummyZKFC;
import org.apache.log4j.Level;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class TestZKFailoverController extends ClientBase {
  private Configuration conf;
  private MiniZKFCCluster cluster;
  
  static {
    ((Log4JLogger)ActiveStandbyElector.LOG).getLogger().setLevel(Level.ALL);
  }
  
  @Override
  public void setUp() throws Exception {
    // build.test.dir is used by zookeeper
    new File(System.getProperty("build.test.dir", "build")).mkdirs();
    super.setUp();
  }
  
  @Before
  public void setupConfAndServices() {
    conf = new Configuration();
    conf.set(ZKFailoverController.ZK_QUORUM_KEY, hostPort);
    this.cluster = new MiniZKFCCluster(conf, getServer(serverFactory));
  }

  /**
   * Test that the various command lines for formatting the ZK directory
   * function correctly.
   */
  @Test(timeout=15000)
  public void testFormatZK() throws Exception {
    DummyHAService svc = cluster.getService(1);
    // Run without formatting the base dir,
    // should barf
    assertEquals(ZKFailoverController.ERR_CODE_NO_PARENT_ZNODE,
        runFC(svc));

    // Format the base dir, should succeed
    assertEquals(0, runFC(svc, "-formatZK"));

    // Should fail to format if already formatted
    assertEquals(ZKFailoverController.ERR_CODE_FORMAT_DENIED,
        runFC(svc, "-formatZK", "-nonInteractive"));
  
    // Unless '-force' is on
    assertEquals(0, runFC(svc, "-formatZK", "-force"));
  }
  
  /**
   * Test that the ZKFC won't run if fencing is not configured for the
   * local service.
   */
  @Test(timeout=15000)
  public void testFencingMustBeConfigured() throws Exception {
    DummyHAService svc = Mockito.spy(cluster.getService(0));
    Mockito.doThrow(new BadFencingConfigurationException("no fencing"))
        .when(svc).checkFencingConfigured();
    // Format the base dir, should succeed
    assertEquals(0, runFC(svc, "-formatZK"));
    // Try to run the actual FC, should fail without a fencer
    assertEquals(ZKFailoverController.ERR_CODE_NO_FENCER,
        runFC(svc));
  }
  
  /**
   * Test that, when the health monitor indicates bad health status,
   * failover is triggered. Also ensures that graceful active->standby
   * transition is used when possible, falling back to fencing when
   * the graceful approach fails.
   */
  @Test(timeout=15000)
  public void testAutoFailoverOnBadHealth() throws Exception {
    try {
      cluster.start();
      DummyHAService svc1 = cluster.getService(1);
      
      LOG.info("Faking svc0 unhealthy, should failover to svc1");
      cluster.setHealthy(0, false);
      
      LOG.info("Waiting for svc0 to enter standby state");
      cluster.waitForHAState(0, HAServiceState.STANDBY);
      cluster.waitForHAState(1, HAServiceState.ACTIVE);
  
      LOG.info("Allowing svc0 to be healthy again, making svc1 unreachable " +
          "and fail to gracefully go to standby");
      cluster.setUnreachable(1, true);
      cluster.setHealthy(0, true);
 
      // Should fail back to svc0 at this point
      cluster.waitForHAState(0, HAServiceState.ACTIVE);
      // and fence svc1
      Mockito.verify(svc1.fencer).fence(Mockito.same(svc1));
    } finally {
      cluster.stop();
    }
  }
  
  @Test(timeout=15000)
  public void testAutoFailoverOnLostZKSession() throws Exception {
    try {
      cluster.start();

      // Expire svc0, it should fail over to svc1
      cluster.expireAndVerifyFailover(0, 1);
      
      // Expire svc1, it should fail back to svc0
      cluster.expireAndVerifyFailover(1, 0);
      
      LOG.info("======= Running test cases second time to test " +
          "re-establishment =========");
      // Expire svc0, it should fail over to svc1
      cluster.expireAndVerifyFailover(0, 1);
      
      // Expire svc1, it should fail back to svc0
      cluster.expireAndVerifyFailover(1, 0);
    } finally {
      cluster.stop();
    }
  }

  /**
   * Test that, if the standby node is unhealthy, it doesn't try to become
   * active
   */
  @Test(timeout=15000)
  public void testDontFailoverToUnhealthyNode() throws Exception {
    try {
      cluster.start();

      // Make svc1 unhealthy, and wait for its FC to notice the bad health.
      cluster.setHealthy(1, false);
      cluster.waitForHealthState(1, HealthMonitor.State.SERVICE_UNHEALTHY);
      
      // Expire svc0
      cluster.getElector(0).preventSessionReestablishmentForTests();
      try {
        cluster.expireActiveLockHolder(0);

        LOG.info("Expired svc0's ZK session. Waiting a second to give svc1" +
            " a chance to take the lock, if it is ever going to.");
        Thread.sleep(1000);
        
        // Ensure that no one holds the lock.
        cluster.waitForActiveLockHolder(null);
        
      } finally {
        LOG.info("Allowing svc0's elector to re-establish its connection");
        cluster.getElector(0).allowSessionReestablishmentForTests();
      }
      // svc0 should get the lock again
      cluster.waitForActiveLockHolder(0);
    } finally {
      cluster.stop();
    }
  }

  /**
   * Test that the ZKFC successfully quits the election when it fails to
   * become active. This allows the old node to successfully fail back.
   */
  @Test(timeout=15000)
  public void testBecomingActiveFails() throws Exception {
    try {
      cluster.start();
      DummyHAService svc1 = cluster.getService(1);
      
      LOG.info("Making svc1 fail to become active");
      cluster.setFailToBecomeActive(1, true);
      
      LOG.info("Faking svc0 unhealthy, should NOT successfully " +
          "failover to svc1");
      cluster.setHealthy(0, false);
      cluster.waitForHealthState(0, State.SERVICE_UNHEALTHY);
      cluster.waitForActiveLockHolder(null);

      
      Mockito.verify(svc1.proxy, Mockito.timeout(2000).atLeastOnce())
        .transitionToActive();

      cluster.waitForHAState(0, HAServiceState.STANDBY);
      cluster.waitForHAState(1, HAServiceState.STANDBY);
      
      LOG.info("Faking svc0 healthy again, should go back to svc0");
      cluster.setHealthy(0, true);
      cluster.waitForHAState(0, HAServiceState.ACTIVE);
      cluster.waitForHAState(1, HAServiceState.STANDBY);
      cluster.waitForActiveLockHolder(0);
      
      // Ensure that we can fail back to svc1  once it it is able
      // to become active (e.g the admin has restarted it)
      LOG.info("Allowing svc1 to become active, expiring svc0");
      svc1.failToBecomeActive = false;
      cluster.expireAndVerifyFailover(0, 1);
    } finally {
      cluster.stop();
    }
  }
  
  /**
   * Test that, when ZooKeeper fails, the system remains in its
   * current state, without triggering any failovers, and without
   * causing the active node to enter standby state.
   */
  @Test(timeout=15000)
  public void testZooKeeperFailure() throws Exception {
    try {
      cluster.start();

      // Record initial ZK sessions
      long session0 = cluster.getElector(0).getZKSessionIdForTests();
      long session1 = cluster.getElector(1).getZKSessionIdForTests();

      LOG.info("====== Stopping ZK server");
      stopServer();
      waitForServerDown(hostPort, CONNECTION_TIMEOUT);
      
      LOG.info("====== Waiting for services to enter NEUTRAL mode");
      cluster.waitForElectorState(0,
          ActiveStandbyElector.State.NEUTRAL);
      cluster.waitForElectorState(1,
          ActiveStandbyElector.State.NEUTRAL);

      LOG.info("====== Checking that the services didn't change HA state");
      assertEquals(HAServiceState.ACTIVE, cluster.getService(0).state);
      assertEquals(HAServiceState.STANDBY, cluster.getService(1).state);
      
      LOG.info("====== Restarting server");
      startServer();
      waitForServerUp(hostPort, CONNECTION_TIMEOUT);

      // Nodes should go back to their original states, since they re-obtain
      // the same sessions.
      cluster.waitForElectorState(0, ActiveStandbyElector.State.ACTIVE);
      cluster.waitForElectorState(1, ActiveStandbyElector.State.STANDBY);
      // Check HA states didn't change.
      cluster.waitForHAState(0, HAServiceState.ACTIVE);
      cluster.waitForHAState(1, HAServiceState.STANDBY);

      // Check they re-used the same sessions and didn't spuriously reconnect
      assertEquals(session0,
          cluster.getElector(0).getZKSessionIdForTests());
      assertEquals(session1,
          cluster.getElector(1).getZKSessionIdForTests());
    } finally {
      cluster.stop();
    }
  }

  private int runFC(DummyHAService target, String ... args) throws Exception {
    DummyZKFC zkfc = new DummyZKFC(target);
    zkfc.setConf(conf);
    return zkfc.run(args);
  }

}
