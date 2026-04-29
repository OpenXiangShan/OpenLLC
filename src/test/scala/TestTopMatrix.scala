package openLLC

import chisel3._
import chisel3.stage.ChiselGeneratorAnnotation
import circt.stage.{ChiselStage, FirtoolOption}
import chisel3.util._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tile.MaxHartIdBits
import org.chipsalliance.cde.config._
import coupledL2._
import coupledL2.tl2chi._
import cc.xiangshan.openncb._
import cc.xiangshan.openncb.chi._
import utility._
import utility.chiron._

import scala.collection.mutable.ArrayBuffer

class TestTopMatrix(
  numCores: Int = 1,
  numULAgents: Int = 0,
  numMAgents: Int = 1,
  banks: Int = 1,
  issue: String = Issue.Eb,
  extTime: Boolean = true
)(implicit p: Parameters) extends LazyModule with HasCHIMsgParameters {

  /*   L1D(L1I)* Matrix*  L1D(L1I)* Matrix*  ...
   *       \     /              \     /
   *        L2                  L2     ...
   *         \                 /
   *          \               /
   *                  L3
   */
  override lazy val desiredName: String = "TestTop"
  val l2Params = p(L2ParamKey)
  val l3Params = p(OpenLLCParamKey)

  val clogIdUpstream = "l3top"
  val clogIdDownstream = "l3top"

  def createClientNode(name: String, sources: Int) = {
    val masterNode = TLClientNode(Seq(
      TLMasterPortParameters.v2(
        masters = Seq(
          TLMasterParameters.v1(
            name = name,
            sourceId = IdRange(0, sources),
            supportsProbe = TransferSizes(l2Params.blockBytes)
          )
        ),
        channelBytes = TLChannelBeatBytes(l2Params.blockBytes),
        minLatency = 1,
        echoFields = Nil,
        requestFields = Seq(huancun.AliasField(2)),
        responseKeys = l2Params.respKey
      )
    ))
    masterNode
  }

  def createMatrixNode(name: String, sources: Int) = {
    implicit val valName: ValName = ValName(name)
    TLClientNode(Seq(
      TLMasterPortParameters.v2(
        masters = Seq(
          TLMasterParameters.v1(
            name = name,
            sourceId = IdRange(0, sources)
          )
        ),
        channelBytes = TLChannelBeatBytes(l2Params.blockBytes),
        minLatency = 1,
        echoFields = Nil,
        requestFields = Seq(MatrixField(2)),
        responseKeys = l2Params.respKey
      )
    ))
  }

  val l1d_nodes = (0 until numCores).map(i => createClientNode(s"l1d$i", 32))
  val l1i_nodes = (0 until numCores).map { i =>
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

  val matrix_nodes = (0 until numCores).map(i =>
    (0 until numMAgents).map(j => createMatrixNode(s"matrix${i}_$j", 32))
  )

  val l2_nodes = (0 until numCores).map(i => LazyModule(new TL2CHICoupledL2()(new Config((site, here, up) => {
    case L2ParamKey => l2Params.copy(
      name                = s"L2_$i",
      hartId              = i,
    )
    case EnableCHI => true
    case EnableMatrix => true
    case CHIIssue => issue
    case huancun.BankBitsKey => log2Ceil(banks)
    case MaxHartIdBits => log2Up(numCores)
    case LogUtilsOptionsKey => LogUtilsOptions(
      false,
      here(L2ParamKey).enablePerf,
      here(L2ParamKey).FPGAPlatform
    )
    case PerfCounterOptionsKey => PerfCounterOptions(
      here(L2ParamKey).enablePerf && !here(L2ParamKey).FPGAPlatform,
      here(L2ParamKey).enableRollingDB && !here(L2ParamKey).FPGAPlatform,
      XSPerfLevel.withName("VERBOSE"),
      i
    )
  }))))

  val l3Bridge = LazyModule(new OpenNCB()(new Config((site, here, up) => {
    case CHIIssue => issue
    case NCBParametersKey => new NCBParameters(
      axiMasterOrder      = EnumAXIMasterOrder.WriteAddress,
      readCompDMT         = false,
      writeCancelable     = false,
      writeNoError        = true,
      axiBurstAlwaysIncr  = true,
      chiDataCheck        = EnumCHIDataCheck.OddParity
    )
  })))

  val mem = new AXI4SlaveNode(Seq(new AXI4SlavePortParameters(
    slaves = Seq(new AXI4SlaveParameters(
      address = Seq(new AddressSet(0, 0xffff_ffffL)),
      supportsWrite = new TransferSizes(1, 64),
      supportsRead = new TransferSizes(1, 64)
    )),
    beatBytes = 32
  )))

  val bankBinders = (0 until numCores).map(_ => BankBinder(banks, 64))

  val l1xbars = l1d_nodes.zip(l2_nodes).zipWithIndex.map { case ((l1d, l2), i) =>
    val l1xbar = TLXbar()
    l1xbar :=
      TLBuffer() :=
      TLLogger(s"L2_L1[${i}].C[0]", !l2Params.FPGAPlatform && l2Params.enableTLLog) :=
      l1d

    l1i_nodes(i).zipWithIndex.foreach { case (l1i, j) =>
      l1xbar :=
        TLBuffer() :=
        TLLogger(s"L2_L1[${i}].UL[${j}]", !l2Params.FPGAPlatform && l2Params.enableTLLog) :=
        l1i
    }

    matrix_nodes(i).zipWithIndex.foreach { case (node, j) =>
      l1xbar :=
        TLBuffer() :=
        TLLogger(s"L2_Matrix[${i}][${j}]", !l2Params.FPGAPlatform && l2Params.enableTLLog) :=
        node
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
    l1xbar
  }

  mem :=
    AXI4Xbar() :=
    l3Bridge.axi4node

  lazy val module = new LazyModuleImp(this) {
    val time_sim = if (extTime) {
      IO(Input(UInt(64.W)))
    } else {
      WireDefault(0.U(64.W))
    }

    val log = IO(new Bundle {
      val dump = Input(Bool())
      val clean = Input(Bool())
    })

    val matrixDataOut = IO(Vec(numCores, Vec(banks, DecoupledIO(new MatrixDataBundle()))))

    val io_l1 = IO(Vec(numCores, new Bundle() {
      val l2Hint = Valid(new L2ToL1Hint)
    }))

    val cycle = RegInit(0.U(64.W))
    cycle := cycle + 1.U

    val timer = WireDefault(0.U(64.W))
    val logEnable = WireDefault(false.B)
    val clean = WireDefault(false.B)
    val dump = WireDefault(false.B)

    timer := { if (extTime) time_sim else cycle }
    logEnable := true.B
    clean := log.clean
    dump := log.dump

    dontTouch(timer)
    dontTouch(logEnable)
    dontTouch(clean)
    dontTouch(dump)

    l1d_nodes.zipWithIndex.foreach {
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
    matrix_nodes.zipWithIndex.foreach { case (nodes, i) =>
      nodes.zipWithIndex.foreach { case (node, j) =>
        node.makeIOs()(ValName(s"master_m_port_${i}_${j}"))
      }
    }

    val l3 = Module(new OpenLLC()(new Config((site, here, up) => {
      case CHIIssue => issue
      case OpenLLCParamKey => l3Params.copy(
        clientCaches    = Seq.fill(numCores)(l2Params.copy(ways = 2, sets = 2)),
        fullAddressBits = ADDR_WIDTH,
        hartIds         = 0 until numCores
      )
      case LogUtilsOptionsKey => LogUtilsOptions(
        false,
        here(OpenLLCParamKey).enablePerf,
        here(OpenLLCParamKey).FPGAPlatform
      )
      case PerfCounterOptionsKey => PerfCounterOptions(
        here(OpenLLCParamKey).enablePerf && !here(OpenLLCParamKey).FPGAPlatform,
        false,
        XSPerfLevel.withName("VERBOSE"),
        0
      )
    })))

    l2_nodes.zipWithIndex.foreach { case (l2, i) =>
      if (!l3Params.FPGAPlatform && l3Params.enableCHILog) {
        CLogB.logFlitsRNOfRNF(
          id    = clogIdUpstream,
          vTime = false,
          clock = l2.module.clock,
          reset = l2.module.reset,
          rnId  = l2.module.io_nodeID,
          txreqflit = l2.module.io_chi.tx.req.flit, txreqflitv = l2.module.io_chi.tx.req.flitv,
          rxrspflit = l2.module.io_chi.rx.rsp.flit, rxrspflitv = l2.module.io_chi.rx.rsp.flitv,
          rxdatflit = l2.module.io_chi.rx.dat.flit, rxdatflitv = l2.module.io_chi.rx.dat.flitv,
          rxsnpflit = l2.module.io_chi.rx.snp.flit, rxsnpflitv = l2.module.io_chi.rx.snp.flitv,
          txrspflit = l2.module.io_chi.tx.rsp.flit, txrspflitv = l2.module.io_chi.tx.rsp.flitv,
          txdatflit = l2.module.io_chi.tx.dat.flit, txdatflitv = l2.module.io_chi.tx.dat.flitv,
          time = time_sim, timev = extTime.B
        )
      }

      l2.module.io_chi <> l3.io.rn(i)
      dontTouch(l2.module.io)

      l2.module.io.l2_hint <> io_l1(i).l2Hint

      l2.module.io.hartId := i.U
      l2.module.io.pfCtrlFromCore := DontCare
      l2.module.io_nodeID := i.U(NODEID_WIDTH.W)
      l2.module.io.debugTopDown := DontCare
      matrixDataOut(i) <> l2.module.io.matrixDataOut.get
      l2.module.io.l2_tlb_req <> DontCare
    }

    if (!l3Params.FPGAPlatform && l3Params.enableCHILog) {
      CLogB.logFlitsSNOfHNF(
        id    = clogIdDownstream,
        vTime = false,
        clock = l3.clock,
        reset = l3.reset,
        hnId  = l3.io.nodeID,
        txreqflit = l3.io.sn.tx.req.flit, txreqflitv = l3.io.sn.tx.req.flitv,
        rxrspflit = l3.io.sn.rx.rsp.flit, rxrspflitv = l3.io.sn.rx.rsp.flitv,
        rxdatflit = l3.io.sn.rx.dat.flit, rxdatflitv = l3.io.sn.rx.dat.flitv,
        txdatflit = l3.io.sn.tx.dat.flit, txdatflitv = l3.io.sn.tx.dat.flitv,
        time = time_sim, timev = extTime.B
      )
    }

    l3.io.sn <> l3Bridge.module.io.chi
    l3.io.nodeID := numCores.U(NODEID_WIDTH.W)
    l3.io.debugTopDown := DontCare

    mem.makeIOs()(ValName("mem_axi"))

    if (!l3Params.FPGAPlatform && l3Params.enableCHILog) {
      Seq(clogIdUpstream, clogIdDownstream).distinct.foreach {
        CLogB.logParameters(_, this.clock, this.reset, true.B, new utility.chiron.CHIParameters(
          issue = p(CHIIssue) match {
            case Issue.B  => CLog.IssueB
            case Issue.Eb => CLog.IssueE
            case _ => {
              require(false, s"unknown or unsupported CHI Issue: ${p(CHIIssue)}")
              0
            }
          },
          nodeIdWidth = NODEID_WIDTH,
          reqAddrWidth = ADDR_WIDTH,
          reqRsvdcWidth = REQ_RSVDC_WIDTH,
          datRsvdcWidth = DAT_RSVDC_WIDTH,
          dataWidth = DATA_WIDTH,
          dataCheckPresent = enableDataCheck,
          poisonPresent = enablePoison,
          mpamPresent = MPAM_WIDTH != 0
        ))
      }
    }

    if (!l2Params.FPGAPlatform && l2Params.enablePerf) {
      XSLog.collect(timer, logEnable, clean, dump)
    }
  }
}

object TestTopMatrix extends App {
  val usage = """
Usage: TestTopMatrix [<--option> <values>]

      --core <core_count>       specify core count, 1 by default
      --tl-ul <tl_ul_count>     specify TileLink-UL agent count per core, 0 by default
    --m-agent <m_count>       specify matrix agent count per core, 1 by default
      --bank <bank_count>       specify bank (slice) count, 1 by default
      --fpga <1>                generate for FPGA platform
      --chiseldb <1>            enable ChiselDB
      --tllog <1>               enable TLLogger under ChiselDB
  """

  if (args.contains("--help")) {
    println(usage)
    System.exit(-1)
  }

  var varArgs = ArrayBuffer(args.toIndexedSeq: _*)
  var varArgsDropped = 0

  var numCores: Int = 1
  var numULAgents: Int = 0
  var numMAgents: Int = 1
  var numBanks: Int = 1
  var onFPGAPlatform: Boolean = false
  var enableChiselDB: Boolean = false
  var enableTLLog: Boolean = false

  val varArgsToDrop = args.sliding(2, 1).zipWithIndex.collect {
    case (Array("--core", value), i) => (numCores = value.toInt, i)
    case (Array("--tl-ul", value), i) => (numULAgents = value.toInt, i)
    case (Array("--m-agent", value), i) => (numMAgents = value.toInt, i)
    case (Array("--bank", value), i) => (numBanks = value.toInt, i)
    case (Array("--fpga", value), i) => (onFPGAPlatform = value.toInt != 0, i)
    case (Array("--chiseldb", value), i) => (enableChiselDB = value.toInt != 0, i)
    case (Array("--tllog", value), i) => (enableTLLog = value.toInt != 0, i)
  }

  varArgsToDrop.map(_._2).foreach { i =>
    varArgs.remove(i - varArgsDropped, 2)
    varArgsDropped = varArgsDropped + 2
  }
  varArgs.trimToSize()

  val FPGAPlatform    = onFPGAPlatform
  val enableChiselDB_ = !FPGAPlatform && enableChiselDB
  val enableCHILog    = !FPGAPlatform && true
  val enablePerf      = !FPGAPlatform && true

  val config = new Config((_, _, _) => {
    case L2ParamKey => L2Param(
      ways                = 4,
      sets                = 128,
      clientCaches        = Seq(L1Param(aliasBitsOpt = Some(2))),
      enablePerf          = enablePerf,
      enableRollingDB     = enableChiselDB_,
      enableMonitor       = enableChiselDB_,
      enableTLLog         = enableTLLog,
      enableCHILog        = enableCHILog,
      elaboratedTopDown   = enablePerf,
      FPGAPlatform        = FPGAPlatform,
      dataCheck           = Some("oddparity"),
      sam                 = Seq(AddressSet.everything -> 33)
    )
    case OpenLLCParamKey => OpenLLCParam(
      ways                = 2,
      sets                = 2,
      banks               = 1,
      clientCaches        = Seq(L2Param()),
      enablePerf          = enablePerf,
      enableRollingDB     = false,
      enableCHILog        = enableCHILog,
      elaboratedTopDown   = enablePerf,
      FPGAPlatform        = FPGAPlatform
    )
  })

  CLogB.init(enableCHILog)
  ChiselDB.init(enableChiselDB_)

  val top = DisableMonitors(p => LazyModule(new TestTopMatrix(numCores, numULAgents, numMAgents, numBanks)(p)))(config)

  (new ChiselStage).execute(varArgs.toArray, Seq(
    ChiselGeneratorAnnotation(() => top.module),
    FirtoolOption("--disable-annotation-unknown"),
    FirtoolOption("--default-layer-specialization=enable")
  ))

  ChiselDB.addToFileRegisters
  FileRegisters.write("./build")
}