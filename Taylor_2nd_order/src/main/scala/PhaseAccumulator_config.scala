import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class PhaseAccumulator_config(val config: DdsConfig) extends Module {
  val io = IO(new Bundle {
    val fcw         = Input(UInt(config.accumWidth.W))
    val control     = Output(UInt(3.W))
    val phase_tronq = Output(UInt(config.phaseOutWidth.W))
  })

  val larg_accum = config.accumWidth
  val phaseOutW  = config.phaseOutWidth

  val accReg = RegInit(0.U(larg_accum.W))
  accReg := accReg + io.fcw
  io.control := accReg(larg_accum - 1, larg_accum - 3)


  val maxAvailableBits = larg_accum - 3
  val desiredBits      = phaseOutW + 2 
  val W                = if (maxAvailableBits > desiredBits) desiredBits else maxAvailableBits

  val rawPhase = accReg(larg_accum - 4, larg_accum - 3 - W)

  
  val pi_over_4 = Math.round((Math.PI / 4.0) * (1L << W)).toLong.U(W.W)

  val multResult = rawPhase * pi_over_4         // 2*W bits
  
  val shiftAmount = (2 * W) - phaseOutW




  io.phase_tronq := multResult(shiftAmount + phaseOutW - 1, shiftAmount)
  //io.phase_tronq := multResult_offset(shiftAmount + phaseOutW - 1, shiftAmount)
}

object PhaseAccumulator_config extends App {
  val myConfig = DdsConfigs.activeConfig

  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new PhaseAccumulator_config(myConfig),
    Array("--target-dir", "sortie_verilog")
  )
}