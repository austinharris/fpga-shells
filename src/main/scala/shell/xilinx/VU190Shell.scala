// See LICENSE for license details.
package sifive.fpgashells.shell.xilinx.vu190shell

import Chisel._
import chisel3.core.{Input, Output, attach}
import chisel3.experimental.{RawModule, Analog, withClockAndReset}

import freechips.rocketchip.config._
import freechips.rocketchip.devices.debug._
import freechips.rocketchip.util.{SyncResetSynchronizerShiftReg}

import sifive.blocks.devices.uart._

import sifive.fpgashells.devices.xilinx.xilinxvu190xdma._
import sifive.fpgashells.ip.xilinx.{IBUFDS, PowerOnResetFPGAOnly, sdio_spi_bridge, vu190_sys_clock_mmcm0}

//-------------------------------------------------------------------------
// VU190Shell
//-------------------------------------------------------------------------

trait HasDDR4XDMA { this: VU190Shell =>

  require(!p.lift(MemoryXilinxDDRKey).isEmpty)
  val xdma = IO(new XilinxVU190XDMAPads(p(MemoryXilinxDDRKey)))

  def connectXDMA(dut: HasMemoryXilinxVU190XDMAModuleImp): Unit = {
    dut.xilinxvu190xdma.core_clk := dut_clock
    dut.xilinxvu190xdma.sys_reset               := !sys_rst_l
    dut.xilinxvu190xdma.pcie_sys_reset_l        := pcie_sys_reset_l
    dut.xilinxvu190xdma.c0_sys_clk_p            := ddr4_sys_clk_1_p
    dut.xilinxvu190xdma.c0_sys_clk_n            := ddr4_sys_clk_1_n
    dut.xilinxvu190xdma.pcie_sys_clk_n := pcie_sys_clkn
    dut.xilinxvu190xdma.pcie_sys_clk_p := pcie_sys_clkp

    do_reset := dut.xilinxvu190xdma.host_done

    xdma <> dut.xilinxvu190xdma
  }
}

abstract class VU190Shell(implicit val p: Parameters) extends RawModule {

  //Clocks
  val clk_48_mhz = IO(Input(Clock())) //Direct 48MHz clock pin on board
  val sys_rst_l = IO(Input(Bool()))
  val ddr4_sys_clk_1_n = IO(Input(Bool()))
  val ddr4_sys_clk_1_p = IO(Input(Bool()))
  val pcie_sys_clkp = IO(Input(Bool()))
  val pcie_sys_clkn = IO(Input(Bool()))

  //Reset
  val pcie_sys_reset_l = IO(Input(Bool()))

  // UART
  val uart_tx              = IO(Output(Bool()))
  val uart_rx              = IO(Input(Bool()))
  val uart_rtsn            = IO(Output(Bool()))
  val uart_ctsn            = IO(Input(Bool()))

  //-----------------------------------------------------------------------
  // Wire declrations
  //-----------------------------------------------------------------------

  val clk_100_mhz = Wire(Clock())
  val clk_125_mhz = Wire(Clock())
  val clk_144_mhz = Wire(Clock())
  val vu190_sys_clock_mmcm0 = Module(new vu190_sys_clock_mmcm0)
  vu190_sys_clock_mmcm0.io.clk_in1 := clk_48_mhz
  clk_100_mhz := vu190_sys_clock_mmcm0.io.clk_out1
  clk_125_mhz := vu190_sys_clock_mmcm0.io.clk_out1
  clk_144_mhz := vu190_sys_clock_mmcm0.io.clk_out1

  val dut_clock = Wire(Clock())
  val dut_reset = Wire(Bool())

  val do_reset            = Wire(Bool())
  // val top_clock           = Wire(Clock())
  // val top_reset           = Wire(Bool())

  dut_reset := do_reset
  dut_clock := clk_100_mhz

  uart_rtsn := false.B
  def connectUART(dut: HasPeripheryUARTModuleImp): Unit = {
    val uartParams = p(PeripheryUARTKey)
    if (!uartParams.isEmpty) {
      // uart connections
      dut.uart(0).rxd := SyncResetSynchronizerShiftReg(uart_rx, 2, init = Bool(true), name=Some("uart_rxd_sync"))
      uart_tx         := dut.uart(0).txd
    }
  }

  def connectDebug(dut: HasPeripheryDebugModuleImp): ClockedDMIIO = {
    val dmi     = dut.debug.clockeddmi.get

    dmi.dmiReset    := true.B
    //dut_ndreset    := dut.debug.ndreset
    dmi
  }
}
