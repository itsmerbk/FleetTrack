# FleetTrack

**Live Delivery Rider Dispatch & SLA Monitoring Console (Java 21 + Vanilla JavaScript)**

FleetTrack is a real-time last-mile logistics dispatch and rider telemetry platform built on a **Java 21 Virtual Threads** server, **SQLite WAL** transactional persistence, and an interactive **JavaScript HTML5 Canvas** city radar map. It dispatches the closest available rider using Euclidean distance scoring, enforces atomic rider-order locks, simulates live kinematic movement (`PICKING_UP -> IN_TRANSIT -> DELIVERED`), and monitors SLA breach thresholds.

## Quickstart

~~~bash
git clone https://github.com/itsmerbk/FleetTrack.git
cd FleetTrack

mkdir -p out
javac -cp "lib/*" -d out src/com/fleettrack/*.java

# 1. Run automated nearest-rider dispatch & kinematic telemetry tests
java -ea --enable-native-access=ALL-UNNAMED -cp "out:lib/*" com.fleettrack.FleetTrackTest

# 2. Start the live Dispatch & Radar Console (http://127.0.0.1:8084)
java --enable-native-access=ALL-UNNAMED -cp "out:lib/*" com.fleettrack.Main
~~~
