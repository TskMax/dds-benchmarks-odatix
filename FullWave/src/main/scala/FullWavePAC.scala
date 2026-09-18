import chisel3._
import chisel3.util._

class FullWavePAC(val config: DdsConfig) extends Module {
  val io = IO(new Bundle {
    val phaseIn = Input(UInt(config.phaseOutWidth.W)) 
    val cosOut  = Output(SInt(config.ampWidth.W))    // Sortie Cosinus
    val sinOut  = Output(SInt(config.ampWidth.W))    // Sortie Sinus
  })

  val phaseWidth = config.phaseOutWidth
  val ampWidth   = config.ampWidth

  val addressReg = RegNext(io.phaseIn)

  // Dimensions de la table complete
  val romDepth = 1 << phaseWidth              // Ex: 2^14 = 16384 profondeurs
  val maxAmp   = (1 << (ampWidth - 1)) - 1    // Borne haute (ex: 2047)

  // Remplissage de la ROM Sinus en Scala (calcule a la compilation)
  val sineTable = VecInit(Seq.tabulate(romDepth) { i =>
    // Echantillonnage sur 2*PI complet
    val rad = ((i.toDouble) / romDepth.toDouble) * (2.0 * Math.PI) 
    val amp_v = Math.round(Math.sin(rad) * maxAmp).toInt
    amp_v.S(ampWidth.W)  
  })

  // Remplissage de la ROM Cosinus en Scala (calcule a la compilation)
  val cosineTable = VecInit(Seq.tabulate(romDepth) { i =>
    // Echantillonnage sur 2*PI complet
    val rad = ((i.toDouble) / romDepth.toDouble) * (2.0 * Math.PI) 
    val amp_v = Math.round(Math.cos(rad) * maxAmp).toInt
    amp_v.S(ampWidth.W)  
  })

  // Connexion directe des memoires aux sorties
  // Aucun Mux, aucune porte logique de signe ou de miroir n'est generee
  io.cosOut := RegNext(cosineTable(addressReg))
  io.sinOut := RegNext(sineTable(addressReg))
}

// Objet principal pour generer le Verilog
object GenerateFullPAC extends App {
  val myConfig = DdsConfigs.activeConfig

  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new FullWavePAC(myConfig),
    Array("--target-dir", "sortie_verilog")
  )
}