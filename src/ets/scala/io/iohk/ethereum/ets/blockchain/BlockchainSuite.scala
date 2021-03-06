package io.iohk.ethereum.ets.blockchain

import io.iohk.ethereum.ets.common.TestOptions
import io.iohk.ethereum.utils.Logger
import org.scalatest._


class BlockchainSuite extends FreeSpec with Matchers with Logger {

  val unsupportedNetworks = Set("Byzantium","Constantinople", "EIP158ToByzantiumAt5")
  val supportedNetworks = Set("EIP150", "Frontier", "FrontierToHomesteadAt5", "Homestead", "HomesteadToEIP150At5", "HomesteadToDaoAt5", "EIP158")

  //Map of ignored tests, empty set of ignored names means cancellation of whole group
  val ignoredTests: Map[String, Set[String]] = Map()

  override def run(testName: Option[String], args: Args): Status = {
    val options = TestOptions(args.configMap)
    val scenarios = BlockchainScenarioLoader.load("ets/BlockchainTests/", options)

    scenarios.foreach { group =>
      group.name - {
        for {
          (name, scenario) <- group.scenarios
          if options.isScenarioIncluded(name)
        } {
          name in new ScenarioSetup(scenario) {
            if (unsupportedNetworks.contains(scenario.network)) {
              cancel(s"Unsupported network: ${scenario.network}")
            } else if (!supportedNetworks.contains(scenario.network)) {
              fail(s"Unknown network: ${scenario.network}")
            } else if (isCanceled(group.name, name)){
              cancel(s"Test: $name in group: ${group.name} not yet supported")
            } else {
              log.info(s"Running test: ${group.name}/$name")
              runScenario(scenario, this)
            }
          }
        }
      }
    }

    runTests(testName, args)
  }

  private def isCanceled(groupName: String, testName: String): Boolean =
    ignoredTests.get(groupName).isDefined && (ignoredTests(groupName).contains(testName) || ignoredTests(groupName).isEmpty)

  private def runScenario(scenario: BlockchainScenario, setup: ScenarioSetup): Unit = {
    import setup._

    loadGenesis()

    val blocksToProcess = getBlocks(scenario.blocks)

    val invalidBlocks = getBlocks(getInvalid)

    blocksToProcess.foreach { b =>
      val r = ledger.importBlock(b)
      log.debug(s"Block (${b.idTag}) import result: $r")
    }

    val lastBlock = getBestBlock()

    val expectedWorldStateHash = finalWorld.stateRootHash

    lastBlock shouldBe defined

    val expectedState = getExpectedState()
    val resultState = getResultState()

    lastBlock.get.header.hash shouldEqual scenario.lastblockhash
    resultState should contain theSameElementsAs expectedState
    lastBlock.get.header.stateRoot shouldEqual expectedWorldStateHash
  }
}



