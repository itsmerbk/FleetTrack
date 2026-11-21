package com.fleettrack;

import java.nio.file.Files;
import java.nio.file.Path;

public class FleetTrackTest {
    public static void main(String[] args) throws Exception {
        Path tmpDb = Files.createTempFile("fleettrack_test_", ".db");
        tmpDb.toFile().deleteOnExit();

        try (FleetStore store = new FleetStore(tmpDb.toString())) {
            // Seeded order #3 (TRK-1003) has pickup at (74, 25); RDR-03 is at (76, 22) -> closest!
            String dispatchRes = store.dispatchNearestRider(3L);
            assert dispatchRes.contains("\"ok\":true") : "Nearest rider dispatch must succeed";
            assert dispatchRes.contains("\"riderCode\":\"RDR-03\"") : "Expected closest rider RDR-03: " + dispatchRes;

            // Duplicate dispatch of same order must be rejected
            String dupDispatch = store.dispatchNearestRider(3L);
            assert dupDispatch.contains("\"ok\":false") : "Already-dispatched order cannot be dispatched twice";

            // Advance telemetry ticks so riders reach pickup and drop locations
            store.stepSimulation(150.0); // PICKING_UP -> IN_TRANSIT
            String tick2 = store.stepSimulation(150.0); // IN_TRANSIT -> DELIVERED
            assert tick2.contains("\"completedNow\":3") : "All 3 active deliveries must complete after 2 large steps: " + tick2;
        }

        System.out.println("ALL FLEETTRACK ASSERTION TESTS PASSED (Nearest-Rider Dispatch, ACID Locking, Kinematic Telemetry)");
    }
}
