import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class PhaseAccumulator(val config: DdsConfig) extends Module {
  val io = IO(new Bundle {
    val fcw       = Input(UInt(config.accumWidth.W))           // 32 bits
    val control   = Output(UInt(3.W))                          // Les 3 MSB (signe, quart d'onde, huitième d'onde)
    val phase_tronq    = Output(UInt(config.phaseOutWidth.W))  // La phase tronquée et normalisée pour le PAC
  })

  val larg_accum = config.accumWidth


  val accReg = RegInit(0.U(larg_accum.W))
  accReg := accReg + io.fcw

  // Extraction des 3 bits de contrôle pour la logique de symétrie (Octant)
  io.control := accReg(larg_accum - 1, larg_accum - 3)

  // Extraction de la phase brute (ex: les 16 bits suivants)  (2 bits de plus pour garder de la précision pour le calcul du sinus)
  val rawPhase = accReg(larg_accum - 4, larg_accum - 19)   // val rawPhase = accReg(larg_accum - 4, larg_accum - 14)

  // --- MULTIPLICATEUR PAR PI/4 ---
  // pi/4 vaut environ 0.785398. 
  // En virgule fixe sur 16 bits : 0.785398 * (2^16) = 51472
  val pi_over_4 = 51472.U(16.W)  // 16 bits 
  
  // Multiplication et retour à une largeur de 14 bits (Troncature)
  val multResult      = rawPhase * pi_over_4   // (16 + 16 bits = 32 bits, on tronque plus-bas en récupérant les bits de poids fort)
  
  val phaseBits = multResult(31, 17) // On prends les 15 bits de poids fort (comme sur le schéma)

  io.phase_tronq := phaseBits(14, 15 - config.phaseOutWidth) // On récupère finalement 14 Bits 

}

  // Objet principal pour générer le composant individuel
  object PhaseAccumulator extends App {
  // On va chercher la configuration choisie dans ton fichier central
  val myConfig = DdsConfigs.activeConfig

  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    // On remplace les valeurs en dur par les variables de la configuration
    new PhaseAccumulator(myConfig),
    Array("--target-dir", "sortie_verilog")
  )

}