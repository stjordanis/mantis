package io.iohk.ethereum.network.discovery

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

case class DiscoveryConfig(
    discoveryEnabled: Boolean,
    interface: String,
    port: Int,
    bootstrapNodes: Set[Node],
    nodesLimit: Int /* TODO: remove once proper discovery protocol is in place */,
    scanMaxNodes: Int /* TODO: remove once proper discovery protocol is in place */,
    maxNeighbours: Int /* TODO: remove once proper discovery protocol is in place */,
    scanInitialDelay: FiniteDuration,
    scanInterval: FiniteDuration,
    messageExpiration: FiniteDuration)

object DiscoveryConfig {
  def apply(etcClientConfig: com.typesafe.config.Config, bootstrapNodes: Set[String]): DiscoveryConfig = {
    val discoveryConfig = etcClientConfig.getConfig("network.discovery")

    DiscoveryConfig(
      discoveryEnabled = discoveryConfig.getBoolean("discovery-enabled"),
      interface = discoveryConfig.getString("interface"),
      port = discoveryConfig.getInt("port"),
      bootstrapNodes = NodeParser.parseNodes(bootstrapNodes),
      nodesLimit = discoveryConfig.getInt("nodes-limit"),
      scanMaxNodes = discoveryConfig.getInt("scan-max-nodes"),
      maxNeighbours = discoveryConfig.getInt("max-sent-neighbours"),
      scanInitialDelay = discoveryConfig.getDuration("scan-initial-delay").toMillis.millis,
      scanInterval = discoveryConfig.getDuration("scan-interval").toMillis.millis,
      messageExpiration = discoveryConfig.getDuration("message-expiration").toMillis.millis)
  }

}
