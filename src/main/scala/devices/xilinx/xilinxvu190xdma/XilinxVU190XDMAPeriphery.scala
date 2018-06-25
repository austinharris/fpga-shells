// See LICENSE for license details.
package sifive.fpgashells.devices.xilinx.xilinxvu190xdma

import Chisel._
import freechips.rocketchip.config._
import freechips.rocketchip.subsystem.BaseSubsystem
import freechips.rocketchip.diplomacy.{LazyModule, LazyModuleImp, AddressRange}

case object MemoryXilinxDDRKey extends Field[XilinxVU190XDMAParams]

trait HasMemoryXilinxVU190XDMA { this: BaseSubsystem =>
  val module: HasMemoryXilinxVU190XDMAModuleImp

  val xilinxvu190xdma = LazyModule(new XilinxVU190XDMA(p(MemoryXilinxDDRKey)))

  require(nMemoryChannels == 1, "Coreplex must have 1 master memory port")
  xilinxvu190xdma.node := memBuses.head.toDRAMController(Some("xilinxvu190xdma"))()
}

trait HasMemoryXilinxVU190XDMABundle {
  val xilinxvu190xdma: XilinxVU190XDMAIO
  def connectXilinxVU190XDMAToPads(pads: XilinxVU190XDMAPads) {
    pads <> xilinxvu190xdma
  }
}

trait HasMemoryXilinxVU190XDMAModuleImp extends LazyModuleImp
    with HasMemoryXilinxVU190XDMABundle {
  val outer: HasMemoryXilinxVU190XDMA
  val ranges = AddressRange.fromSets(p(MemoryXilinxDDRKey).address)
  require (ranges.size == 1, "DDR range must be contiguous")
  val depth = ranges.head.size
  val xilinxvu190xdma = IO(new XilinxVU190XDMAIO(depth))

  xilinxvu190xdma <> outer.xilinxvu190xdma.module.io.port
}
