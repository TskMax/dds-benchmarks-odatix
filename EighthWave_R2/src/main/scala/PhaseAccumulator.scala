import chisel3._
import _root_.circt.stage.ChiselStage

class PhaseAccumulator(val config: DdsConfig) extends Module { 
  val io = IO(new Bundle {
    val increment = Input(UInt(config.accumWidth.W))    
    val PhaseOut  = Output(UInt(config.phaseOutWidth.W))  
  })

  val accReg = RegInit(0.U(config.accumWidth.W)) 

  accReg := accReg + io.increment  

  io.PhaseOut := accReg   // pas de troncature
}

object GeneratePhaseAccumulator extends App {
  val myConfig = DdsConfigs.activeConfig
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new PhaseAccumulator(myConfig),
    Array("--target-dir", "sortie_verilog")
  )
}