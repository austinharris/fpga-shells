package sifive.fpgashells.devices.xilinx.xilinxvu190xdma

import Chisel._
import chisel3.experimental.{Analog,attach}
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.coreplex._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.interrupts._
import sifive.fpgashells.ip.xilinx.vu190xdma.{VU190XDMAIOClocksReset, VU190XDMAIODDR, vu190xdma}
import sifive.fpgashells.ip.xilinx.ibufds_gte3.IBUFDS_GTE3

case class XilinxVU190XDMAParams(
  address : Seq[AddressSet]
)

class XilinxVU190XDMAPads(depth : BigInt) extends VU190XDMAIODDR(depth) {
  def this(c : XilinxVU190XDMAParams) {
    this(AddressRange.fromSets(c.address).head.size)
  }
}

class XilinxVU190XDMAIO(depth : BigInt) extends VU190XDMAIODDR(depth) with VU190XDMAIOClocksReset {
  val pcie_sys_clk_p = Bool(INPUT)
  val pcie_sys_clk_n = Bool(INPUT)
}

class XilinxVU190XDMAIsland(c : XilinxVU190XDMAParams)(implicit p: Parameters) extends LazyModule with HasCrossing {
  val ranges = AddressRange.fromSets(c.address)
  val str = ranges.toString()
  require (ranges.size == 1, s"DDR range must be contiguous: $str")
  val offset = ranges.head.base
  val depth = ranges.head.size
  val crossing = SynchronousCrossing()
  require((depth<=0x800000000L),"vu190 supports upto 32GB depth configuraton")

  val device = new MemoryDevice
  val node = AXI4SlaveNode(Seq(AXI4SlavePortParameters(
      slaves = Seq(AXI4SlaveParameters(
      address       = c.address,
      resources     = device.reg,
      regionType    = RegionType.UNCACHED,
      executable    = true,
      supportsWrite = TransferSizes(1, 256*8),
      supportsRead  = TransferSizes(1, 256*8))),
    beatBytes = 32)))

  lazy val module = new LazyModuleImp(this) {
    val io = IO(new Bundle {
      val port = new XilinxVU190XDMAIO(depth)
    })

    //MIG black box instantiation
    val blackbox = Module(new vu190xdma(depth))
    val (axi_sync, _) = node.in(0)

    val host_done_reg = Reg(Bool())
    when(!blackbox.io.c0_init_calib_complete) {
      host_done_reg := Bool(false)
    }
    .elsewhen(blackbox.io.host_done) {
      host_done_reg := !host_done_reg
    }

    //PCIe Reference Clock
    val ibufds_gte3 = Module(new IBUFDS_GTE3)
    blackbox.io.pcie_refclk := ibufds_gte3.io.ODIV2
    blackbox.io.pcie_sys_clk_gt := ibufds_gte3.io.O
    ibufds_gte3.io.CEB := UInt(0)
    ibufds_gte3.io.I := io.port.pcie_sys_clk_p
    ibufds_gte3.io.IB := io.port.pcie_sys_clk_n

    //pins to top level
    io.port.host_done := !host_done_reg || !blackbox.io.c0_init_calib_complete
    blackbox.io.core_clk := io.port.core_clk

    //inouts
    attach(io.port.c0_ddr4_dq, blackbox.io.c0_ddr4_dq)
    attach(io.port.c0_ddr4_dqs_t, blackbox.io.c0_ddr4_dqs_t)
    attach(io.port.c0_ddr4_dqs_c, blackbox.io.c0_ddr4_dqs_c)

    //outputs
    io.port.c0_ddr4_act_n        := blackbox.io.c0_ddr4_act_n
    io.port.c0_ddr4_adr          := blackbox.io.c0_ddr4_adr
    io.port.c0_ddr4_ba           := blackbox.io.c0_ddr4_ba
    io.port.c0_ddr4_bg           := blackbox.io.c0_ddr4_bg
    io.port.c0_ddr4_cke          := blackbox.io.c0_ddr4_cke
    io.port.c0_ddr4_odt          := blackbox.io.c0_ddr4_odt
    io.port.c0_ddr4_cs_n         := blackbox.io.c0_ddr4_cs_n
    io.port.c0_ddr4_ck_t         := blackbox.io.c0_ddr4_ck_t
    io.port.c0_ddr4_ck_c         := blackbox.io.c0_ddr4_ck_c
    io.port.c0_ddr4_reset_n      := blackbox.io.c0_ddr4_reset_n
    io.port.c0_ddr4_par          := blackbox.io.c0_ddr4_par

    //inputs
    //differential system clock
    blackbox.io.c0_sys_clk_n     := io.port.c0_sys_clk_n
    blackbox.io.c0_sys_clk_p     := io.port.c0_sys_clk_p

    val awaddr = axi_sync.aw.bits.addr - UInt(offset)
    val araddr = axi_sync.ar.bits.addr - UInt(offset)

    //slave AXI interface write address ports
    blackbox.io.c0_ddr4_s_axi_awid    := axi_sync.aw.bits.id
    blackbox.io.c0_ddr4_s_axi_awaddr  := awaddr
    blackbox.io.c0_ddr4_s_axi_awlen   := axi_sync.aw.bits.len
    blackbox.io.c0_ddr4_s_axi_awsize  := axi_sync.aw.bits.size
    blackbox.io.c0_ddr4_s_axi_awburst := axi_sync.aw.bits.burst
    blackbox.io.c0_ddr4_s_axi_awlock  := axi_sync.aw.bits.lock
    blackbox.io.c0_ddr4_s_axi_awcache := UInt("b0011")
    blackbox.io.c0_ddr4_s_axi_awprot  := axi_sync.aw.bits.prot
    blackbox.io.c0_ddr4_s_axi_awqos   := axi_sync.aw.bits.qos
    blackbox.io.c0_ddr4_s_axi_awvalid := axi_sync.aw.valid
    axi_sync.aw.ready        := blackbox.io.c0_ddr4_s_axi_awready

    //slave interface write data ports
    blackbox.io.c0_ddr4_s_axi_wdata   := axi_sync.w.bits.data
    blackbox.io.c0_ddr4_s_axi_wstrb   := axi_sync.w.bits.strb
    blackbox.io.c0_ddr4_s_axi_wlast   := axi_sync.w.bits.last
    blackbox.io.c0_ddr4_s_axi_wvalid  := axi_sync.w.valid
    axi_sync.w.ready         := blackbox.io.c0_ddr4_s_axi_wready

    //slave interface write response
    blackbox.io.c0_ddr4_s_axi_bready  := axi_sync.b.ready
    axi_sync.b.bits.id       := blackbox.io.c0_ddr4_s_axi_bid
    axi_sync.b.bits.resp     := blackbox.io.c0_ddr4_s_axi_bresp
    axi_sync.b.valid         := blackbox.io.c0_ddr4_s_axi_bvalid

    //slave AXI interface read address ports
    blackbox.io.c0_ddr4_s_axi_arid    := axi_sync.ar.bits.id
    blackbox.io.c0_ddr4_s_axi_araddr  := araddr
    blackbox.io.c0_ddr4_s_axi_arlen   := axi_sync.ar.bits.len
    blackbox.io.c0_ddr4_s_axi_arsize  := axi_sync.ar.bits.size
    blackbox.io.c0_ddr4_s_axi_arburst := axi_sync.ar.bits.burst
    blackbox.io.c0_ddr4_s_axi_arlock  := axi_sync.ar.bits.lock
    blackbox.io.c0_ddr4_s_axi_arcache := UInt("b0011")
    blackbox.io.c0_ddr4_s_axi_arprot  := axi_sync.ar.bits.prot
    blackbox.io.c0_ddr4_s_axi_arqos   := axi_sync.ar.bits.qos
    blackbox.io.c0_ddr4_s_axi_arvalid := axi_sync.ar.valid
    axi_sync.ar.ready        := blackbox.io.c0_ddr4_s_axi_arready

    //slace AXI interface read data ports
    blackbox.io.c0_ddr4_s_axi_rready  := axi_sync.r.ready
    axi_sync.r.bits.id       := blackbox.io.c0_ddr4_s_axi_rid
    axi_sync.r.bits.data     := blackbox.io.c0_ddr4_s_axi_rdata
    axi_sync.r.bits.resp     := blackbox.io.c0_ddr4_s_axi_rresp
    axi_sync.r.bits.last     := blackbox.io.c0_ddr4_s_axi_rlast
    axi_sync.r.valid         := blackbox.io.c0_ddr4_s_axi_rvalid

    //misc
    blackbox.io.sys_reset             :=io.port.sys_reset
    blackbox.io.pcie_sys_reset_l       :=io.port.pcie_sys_reset_l
    io.port.s01_aresetn := blackbox.io.s01_aresetn
    io.port.s01_aclk := blackbox.io.s01_aclk

    blackbox.io.pcie_7x_mgt_rtl_rxn := io.port.pcie_7x_mgt_rtl_rxn
    blackbox.io.pcie_7x_mgt_rtl_rxp := io.port.pcie_7x_mgt_rtl_rxp
    io.port.pcie_7x_mgt_rtl_txn := blackbox.io.pcie_7x_mgt_rtl_txn
    io.port.pcie_7x_mgt_rtl_txp := blackbox.io.pcie_7x_mgt_rtl_txp

    //ctl
    //axi-lite slave interface write address
    blackbox.io.c0_ddr4_s_axi_ctrl_awaddr    := UInt(0)
    blackbox.io.c0_ddr4_s_axi_ctrl_awvalid   := Bool(false)
    // c.aw.ready                 := blackbox.io.c0_ddr4_s_axi_ctrl_awready
    //axi-lite slave interface write data ports
    blackbox.io.c0_ddr4_s_axi_ctrl_wdata     := UInt(0)
    blackbox.io.c0_ddr4_s_axi_ctrl_wvalid    := Bool(false)
    // c.w.ready                  := blackbox.io.c0_ddr4_s_axi_ctrl_wready
    //axi-lite slave interface write response
    blackbox.io.c0_ddr4_s_axi_ctrl_bready    := Bool(false)
    // c.b.bits.id                := UInt(0)
    // c.b.bits.resp              := blackbox.io.c0_ddr4_s_axi_ctrl_bresp
    // c.b.valid                  := blackbox.io.c0_ddr4_s_axi_ctrl_bvalid
    //axi-lite slave AXI interface read address ports
    blackbox.io.c0_ddr4_s_axi_ctrl_araddr    := UInt(0)
    blackbox.io.c0_ddr4_s_axi_ctrl_arvalid   := Bool(false)
    // c.ar.ready                 := blackbox.io.c0_ddr4_s_axi_ctrl_arready
    //slave AXI interface read data ports
    blackbox.io.c0_ddr4_s_axi_ctrl_rready    := Bool(false)
  }
}

class XilinxVU190XDMA(c : XilinxVU190XDMAParams)(implicit p: Parameters) extends LazyModule {
  val ranges = AddressRange.fromSets(c.address)
  val depth = ranges.head.size

  val buffer  = LazyModule(new TLBuffer)
  val toaxi4  = LazyModule(new TLToAXI4(adapterName = Some("mem"), stripBits = 1))
  val indexer = LazyModule(new AXI4IdIndexer(idBits = 4))
  val deint   = LazyModule(new AXI4Deinterleaver(p(CacheBlockBytes)))
  val yank    = LazyModule(new AXI4UserYanker)
  val island  = LazyModule(new XilinxVU190XDMAIsland(c))

  val node: TLInwardNode =
    island.node := island.crossAXI4In := yank.node := deint.node := indexer.node := toaxi4.node := buffer.node

  lazy val module = new LazyModuleImp(this) {
    val io = IO(new Bundle {
                  val port = new XilinxVU190XDMAIO(depth)
                })

    io.port <> island.module.io.port

    // Shove the island
    //TODO fix this
    island.module.clock := io.port.core_clk
    island.module.reset := io.port.sys_reset
  }
}
