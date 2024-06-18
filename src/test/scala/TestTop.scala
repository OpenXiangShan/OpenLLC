package openLLC

import chisel3._
import chisel3.util._
import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage}
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tile.MaxHartIdBits
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config._
import coupledL2._
import coupledL2.tl2chi._
import utility._

class TestTop_L3()(implicit p: Parameters) extends LazyModule {
  override lazy val desiredName: String = "TestTop_L3"

  val l3 = LazyModule(new OpenLLC())

  lazy val module = new LazyModuleImp(this){

  }
}

object TestTop_L3 extends App {
  val config = new Config((_, _, _) => {
    case _ => 0
  })

  val top = DisableMonitors(p => LazyModule(new TestTop_L3()(p)))(config)
  (new ChiselStage).execute(args, Seq(
    ChiselGeneratorAnnotation(() => top.module)
  ))
}

class TestTopSoC(numCores: Int = 1, numULAgents: Int = 0, banks: Int = 1)(implicit p: Parameters) extends LazyModule
  with HasCHIMsgParameters {
  
  /*   L1D(L1I)* L1D(L1I)* ... L1D(L1I)*
   *       \         |          /
   *       L2        L2   ...  L2
   *         \       |        /
   *          \      |       /
   *                 L3
   */
  override lazy val desiredName: String = "TestTop"
  val delayFactor = 0.5
  val cacheParams = p(L2ParamKey)

  def createClientNode(name: String, sources: Int) = {
    val masterNode = TLClientNode(Seq(
      TLMasterPortParameters.v2(
        masters = Seq(
          TLMasterParameters.v1(
            name = name,
            sourceId = IdRange(0, sources),
            supportsProbe = TransferSizes(cacheParams.blockBytes)
          )
        ),
        channelBytes = TLChannelBeatBytes(cacheParams.blockBytes),
        minLatency = 1,
        echoFields = Nil,
        requestFields = Seq(huancun.AliasField(2)),
        responseKeys = cacheParams.respKey
      )
    ))
    masterNode
  }

  val l1d_nodes = (0 until numCores).map(i => createClientNode(s"l1d$i", 32))
  val l1i_nodes = (0 until numCores).map {i =>
    (0 until numULAgents).map { j =>
      TLClientNode(Seq(
        TLMasterPortParameters.v1(
          clients = Seq(TLMasterParameters.v1(
            name = s"l1i${i}_${j}",
            sourceId = IdRange(0, 32)
          ))
        )
      ))
    }
  }

  // val l2 = LazyModule(new TL2CHICoupledL2())
  val l2_nodes = (0 until numCores).map(i => LazyModule(new TL2CHICoupledL2()(new Config((_, _, _) => {
    case L2ParamKey => cacheParams.copy(
      name                = s"L2_$i",
      hartId              = i,
    )
    case EnableCHI => true
    case huancun.BankBitsKey => log2Ceil(banks)
    case MaxHartIdBits => log2Up(numCores)
  }))))

  val l3 = LazyModule(new DummyLLC(numCores)(p))
  val ram = LazyModule(new AXI4RAM(AddressSet(0, 0xff_ffffL), beatBytes = 32))

  val bankBinders = (0 until numCores).map(_ => BankBinder(banks, 64))

  l1d_nodes.zip(l2_nodes).zipWithIndex.foreach { case ((l1d, l2), i) =>
    val l1xbar = TLXbar()
    l1xbar := 
      TLBuffer() :=
      TLLogger(s"L2_L1[${i}].C[0]", !cacheParams.FPGAPlatform && cacheParams.enableTLLog) := 
      l1d

    l1i_nodes(i).zipWithIndex.foreach { case (l1i, j) =>
      l1xbar :=
        TLBuffer() :=
        TLLogger(s"L2_L1[${i}].UL[${j}]", !cacheParams.FPGAPlatform && cacheParams.enableTLLog) :=
        l1i
    }
    
    l2.managerNode :=
      TLXbar() :=*
      bankBinders(i) :*=
      l2.node :*=
      l1xbar
    /**
      * MMIO: make diplomacy happy
      */
    val mmioClientNode = TLClientNode(Seq(
      TLMasterPortParameters.v1(
        clients = Seq(TLMasterParameters.v1(
          "uncache"
        ))
      )
    ))
    l2.mmioBridge.mmioNode := mmioClientNode
  }

  ram.node := 
    AXI4Xbar() :=
    AXI4Fragmenter() :=
    l3.axi4node

  lazy val module = new LazyModuleImp(this) {
    val timer = WireDefault(0.U(64.W))
    val logEnable = WireDefault(false.B)
    val clean = WireDefault(false.B)
    val dump = WireDefault(false.B)

    dontTouch(timer)
    dontTouch(logEnable)
    dontTouch(clean)
    dontTouch(dump)

    l1d_nodes.zipWithIndex.foreach{
      case (node, i) =>
        node.makeIOs()(ValName(s"master_port_$i"))
    }
    if (numULAgents != 0) {
      l1i_nodes.zipWithIndex.foreach { case (core, i) =>
        core.zipWithIndex.foreach { case (node, j) =>
          node.makeIOs()(ValName(s"master_ul_port_${i}_${j}"))
        }
      }
    }

    l2_nodes.zipWithIndex.foreach { case (l2, i) =>
      l3.module.io.rn(i) <> l2.module.io.chi
      dontTouch(l2.module.io)

      l2.module.io.hartId := i.U
      l2.module.io.nodeID := i.U(NODEID_WIDTH.W)
      l2.module.io.debugTopDown := DontCare
      l2.module.io.l2_tlb_req <> DontCare
    }
  }
}

object TestTopSoCHelper {
  def gen(fTop: Parameters => TestTopSoC)(args: Array[String]) = {
    val FPGAPlatform    = false
    val enableChiselDB  = !FPGAPlatform && true
    
    val config = new Config((_, _, _) => {
      case L2ParamKey => L2Param(
        ways                = 4,
        sets                = 128,
        clientCaches        = Seq(L1Param(aliasBitsOpt = Some(2))),
        // echoField        = Seq(DirtyField),
        enablePerf          = false,
        enableRollingDB     = enableChiselDB && true,
        enableMonitor       = enableChiselDB && true,
        enableTLLog         = enableChiselDB && true,
        elaboratedTopDown   = false,
        FPGAPlatform        = FPGAPlatform,

        // SAM for tester ICN: Home Node ID = 33
        sam                 = Seq(AddressSet.everything -> 33)
      )
    })

    ChiselDB.init(enableChiselDB)

    val top = DisableMonitors(p => LazyModule(fTop(p)))(config)

    (new ChiselStage).execute(args, Seq(
      ChiselGeneratorAnnotation(() => top.module)
    ))

    ChiselDB.addToFileRegisters
    FileRegisters.write("./build")
  }
}

object TestTopSoC_SingleCore extends App {
  TestTopSoCHelper.gen(p => new TestTopSoC(
    numCores = 1,
    numULAgents = 0,
    banks = 1
  )(p))(args)
}

object TestTopSoC_DualCore extends App {
  TestTopSoCHelper.gen(p => new TestTopSoC(
    numCores = 2,
    numULAgents = 0,
    banks = 1
  )(p))(args)
}