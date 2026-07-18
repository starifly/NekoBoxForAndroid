package io.nekohasekai.sagernet.aidl;

import io.nekohasekai.sagernet.aidl.SpeedDisplayData;
import io.nekohasekai.sagernet.aidl.TrafficData;
import io.nekohasekai.sagernet.aidl.TrafficDataBatch;

oneway interface ISagerNetServiceCallback {
  void stateChanged(int state, String profileName, String msg);
  void missingPlugin(String profileName, String pluginName);
  void cbSpeedUpdate(in SpeedDisplayData stats);
  void cbTrafficUpdate(in TrafficData stats);
  void cbTrafficUpdateList(in List<TrafficData> stats);
  void cbSelectorUpdate(long id);
}
