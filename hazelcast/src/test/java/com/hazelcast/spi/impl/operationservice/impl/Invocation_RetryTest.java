package com.hazelcast.spi.impl.operationservice.impl;

import com.hazelcast.config.Config;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.MemberLeftException;
import com.hazelcast.instance.GroupProperty;
import com.hazelcast.nio.Address;
import com.hazelcast.spi.AbstractOperation;
import com.hazelcast.spi.Operation;
import com.hazelcast.spi.OperationService;
import com.hazelcast.spi.PartitionAwareOperation;
import com.hazelcast.spi.exception.RetryableHazelcastException;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.test.AssertTask;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.HazelcastTestSupport;
import com.hazelcast.test.TestHazelcastInstanceFactory;
import com.hazelcast.test.annotation.ParallelTest;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.concurrent.Future;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

@RunWith(HazelcastParallelClassRunner.class)
@Category({QuickTest.class, ParallelTest.class})
public class Invocation_RetryTest extends HazelcastTestSupport {

    @Test
    public void whenPartitionTargetMemberDiesThenOperationSendToNewPartitionOwner() throws Exception {
        TestHazelcastInstanceFactory factory = createHazelcastInstanceFactory(2);
        HazelcastInstance local = factory.newHazelcastInstance();
        HazelcastInstance remote = factory.newHazelcastInstance();
        warmUpPartitions(local, remote);

        OperationService service = getOperationService(local);
        Operation op = new PartitionTargetOperation();
        Future f = service.createInvocationBuilder(null, op, getPartitionId(remote))
                .setCallTimeout(30000)
                .invoke();
        sleepSeconds(1);

        remote.shutdown();

        //the get should work without a problem because the operation should be re-targeted at the newest owner
        //for that given partition
        f.get();
    }

    @Test
    public void whenTargetMemberDiesThenOperationAbortedWithMembersLeftException() throws Exception {
        TestHazelcastInstanceFactory factory = createHazelcastInstanceFactory(2);
        HazelcastInstance local = factory.newHazelcastInstance();
        HazelcastInstance remote = factory.newHazelcastInstance();
        warmUpPartitions(local, remote);

        OperationService service = getOperationService(local);
        Operation op = new TargetOperation();
        Address address = new Address(remote.getCluster().getLocalMember().getSocketAddress());
        Future f = service.createInvocationBuilder(null, op, address).invoke();
        sleepSeconds(1);

        remote.getLifecycleService().terminate();

        try {
            f.get();
            fail();
        } catch (MemberLeftException expected) {

        }
    }

    @Test
    public void testNoStuckInvocationsWhenRetriedMultipleTimes() throws Exception {
        Config config = new Config();
        config.setProperty(GroupProperty.OPERATION_CALL_TIMEOUT_MILLIS, "3000");
        TestHazelcastInstanceFactory factory = createHazelcastInstanceFactory(2);
        HazelcastInstance local = factory.newHazelcastInstance(config);
        HazelcastInstance remote = factory.newHazelcastInstance(config);
        warmUpPartitions(local, remote);
        NodeEngineImpl localNodeEngine = getNodeEngineImpl(local);
        NodeEngineImpl remoteNodeEngine = getNodeEngineImpl(remote);
        final OperationServiceImpl operationService = (OperationServiceImpl) localNodeEngine.getOperationService();
        NonResponsiveOperation op = new NonResponsiveOperation();
        op.setValidateTarget(false);
        op.setPartitionId(1);
        InvocationFuture future = (InvocationFuture) operationService.invokeOnTarget(null
                , op, remoteNodeEngine.getThisAddress());
        Field invocationField = InvocationFuture.class.getDeclaredField("invocation");
        invocationField.setAccessible(true);
        final Invocation invocation = (Invocation) invocationField.get(future);

        invocation.notifyError(new RetryableHazelcastException());
        invocation.notifyError(new RetryableHazelcastException());

        assertTrueEventually(new AssertTask() {
            @Override
            public void run() throws Exception {
                Iterator<Invocation> invocations = operationService.invocationRegistry.iterator();
                assertFalse(invocations.hasNext());
            }
        });
    }

    /**
     * Non-responsive operation.
     */
    public static class NonResponsiveOperation extends AbstractOperation {

        @Override
        public void run() throws InterruptedException {
        }

        @Override
        public boolean returnsResponse() {
            return false;
        }
    }

    /**
     * Operation send to a specific member.
     */
    private static class TargetOperation extends AbstractOperation {

        @Override
        public void run() throws InterruptedException {
            Thread.sleep(10000);
        }
    }

    /**
     * Operation send to a specific target partition.
     */
    private static class PartitionTargetOperation extends AbstractOperation implements PartitionAwareOperation {

        @Override
        public void run() throws InterruptedException {
            Thread.sleep(5000);
        }
    }
}
