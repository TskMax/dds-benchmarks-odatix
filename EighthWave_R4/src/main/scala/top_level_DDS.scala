import chisel3._

class top_level_DDS(val config: DdsConfig) extends Module {
  val io = IO(new Bundle {
    val increment = Input(UInt(config.accumWidth.W))

    val cosOut    = Output(SInt(config.ampWidth.W))
    val sinOut    = Output(SInt(config.ampWidth.W))
  })

  val accumulator = Module(new PhaseAccumulator(config))
  val pac         = Module(new CompressionEightR4PAC(config))

  accumulator.io.increment := io.increment       // Entrée externe vers accumulateur
  pac.io.phaseIn           := accumulator.io.PhaseOut // Accumulateur vers PAC

  io.cosOut                := pac.io.cosOut                  
  io.sinOut                := pac.io.sinOut                  
}


object GenerateTopDDS extends App {
  
  
  val myConfig = DdsConfigs.activeConfig

  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new top_level_DDS(myConfig),
    Array("--target-dir", "sortie_verilog")
  )
}