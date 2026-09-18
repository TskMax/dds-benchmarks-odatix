import chisel3._

class top_level_DDS(val config: DdsConfig) extends Module {
  val io = IO(new Bundle {
    val increment = Input(UInt(config.accumWidth.W))
    val cosOut    = Output(SInt(config.ampWidth.W))
    val sinOut    = Output(SInt(config.ampWidth.W))
  })


  // 1. Instanciation de tes deux sous-modules matériels
  val accumulator = Module(new PhaseAccumulator(config))
  val pac = Module(new FullWavePAC(config))

  // 2. Câblage interne et externe
  accumulator.io.increment := io.increment       // Entrée externe vers accumulateur
  pac.io.phaseIn := accumulator.io.PhaseOut      // Accumulateur vers PAC (le bus de 12 bits)
  io.cosOut                := pac.io.cosOut                  
  io.sinOut                := pac.io.sinOut       
}



object GenerateTopDDS extends App {
  

  val myConfig = DdsConfigs.activeConfig

s
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new top_level_DDS(myConfig),
    Array("--target-dir", "sortie_verilog")
  )
}