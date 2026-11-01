package dev.pocketengine.features.local.runtime;

import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;
import static org.junit.Assert.*;

public final class RuntimeOwnerMaintenanceTest {
    @Test public void originalGenerationLeaseAndReviewedMaintenanceExcludeEachOtherWithoutStealingLease()throws Exception {
        RuntimeEnvironment original=new RuntimeEnvironment(Files.createTempDirectory("storage-lease-").toFile(),identity->{});String generation="a".repeat(64);AutoCloseable lease=original.acquireGeneration(generation);assertThrows(Exception.class,original::beginOwnerMaintenance);lease.close();try(AutoCloseable cleanup=original.beginOwnerMaintenance()){assertThrows(Exception.class,()->original.acquireGeneration(generation));assertThrows(Exception.class,original::beginOwnerMaintenance);}try(AutoCloseable next=original.acquireGeneration(generation)){assertThrows(Exception.class,original::beginOwnerMaintenance);}try(AutoCloseable next=original.beginOwnerMaintenance()){assertNotNull(next);}
    }
    @Test public void originalUnknownUsageRefusesMaintenanceAndFailedAdmissionDoesNotLeaveGateClosed()throws Exception {
        AtomicBoolean unknown=new AtomicBoolean(true);RuntimeEnvironment original=new RuntimeEnvironment(Files.createTempDirectory("storage-unknown-").toFile(),identity->{assertNull(identity);if(unknown.get())throw new IllegalStateException("original_job_resource_release_unknown");});assertThrows(Exception.class,original::beginOwnerMaintenance);unknown.set(false);try(AutoCloseable cleanup=original.beginOwnerMaintenance()){assertNotNull(cleanup);}try(AutoCloseable lease=original.acquireGeneration("b".repeat(64))){assertNotNull(lease);}
    }
}
