import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class PA_V2_14bits(val config: DdsConfig) extends Module {
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
  val rawPhase = accReg(larg_accum - 4, 0)   // Pour 32 bits sur l'accu : val rawPhase = accReg(larg_accum - 4, larg_accum - 19)

  // --- MULTIPLICATEUR PAR PI/4 ---
  // pi/4 vaut environ 0.785398. 
  // En virgule fixe sur 16 bits : 0.785398 * (2^16) = 51472
  // En virgule fixe sur 14 bits : pi/4 ≈ 0.785398 -> 0.785398 * (2^14) = 12868
  val pi_over_4 = 12868.U(14.W)  //  Pour 16 bits : val pi_over_4 = 51472.U(16.W) 
  
  // Multiplication et retour à une largeur de 14 bits (Troncature)
  val multResult  = rawPhase * pi_over_4   // (16 + 16 bits = 32 bits, on tronque plus-bas en récupérant les bits de poids fort)
  
  val phase14Bits = multResult(24, 11) // Si on a 32 bits : val phase18Bits = multResult(31, 14) On prends les 18 bits de poids fort (comme sur le schéma)

  io.phase_tronq := phase14Bits // On tronque pour récupérer les 14 bits de poids fort (phaseOutWidth) à envoyer au PAC

}

  // Objet principal pour générer le composant individuel
  object PA_V2_14bits extends App {
  // On va chercher la configuration choisie dans ton fichier central
  val myConfig = DdsConfigs.activeConfig

  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    // On remplace les valeurs en dur par les variables de la configuration
    new PA_V2_14bits(myConfig),
    Array("--target-dir", "sortie_verilog")
  )

}

/*import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class PA_V2(val config: DdsConfig) extends Module {
  val io = IO(new Bundle {
    val fcw          = Input(UInt(config.accumWidth.W))
    val control      = Output(UInt(3.W))
    val phase_tronq  = Output(UInt(config.phaseOutWidth.W))
  })

  // --- VARIABLES DE CONFIGURATION ---
  val w_accum = config.accumWidth
  val w_out   = config.phaseOutWidth

  val accReg = RegInit(0.U(w_accum.W))
  accReg := accReg + io.fcw

  // 1. Extraction des 3 bits de contrôle (Signe, Quart, Huitième)
  io.control := accReg(w_accum - 1, w_accum - 3)

  // ==============================================================================
  // LOGIQUE DYNAMIQUE DE DIMENSIONNEMENT
  // ==============================================================================
  
  // Nombre de bits physiquement disponibles dans l'accumulateur après les bits de contrôle
  val w_available = w_accum - 3
  
  // On dimensionne la largeur de calcul interne (w_raw). 
  // Idéalement, on garde une marge de 2 bits de précision (w_out + 2).
  // Mais si l'accumulateur est trop petit (ex: configNeutre), on se limite à ce qui est disponible.
  val w_raw = math.min(w_available, w_out + 2)

  // 2. Extraction dynamique de la phase brute
  val rawPhase = accReg(w_accum - 4, w_accum - 3 - w_raw)

  // 3. Calcul dynamique de la constante pi/4 par le compilateur Scala
  // Scala calcule la valeur exacte en virgule flottante puis l'arrondit pour le hardware
  val pi_over_4_scala = math.round((math.Pi / 4.0) * math.pow(2, w_raw)).toLong
  val pi_over_4 = pi_over_4_scala.U(w_raw.W)

  // 4. Multiplication matérielle
  val multResult = rawPhase * pi_over_4

  // 5. Troncature et alignement adaptatifs
  if (w_raw >= w_out) {
    // Cas classique (ex: configDeBase) : On a assez de précision interne.
    // On extrait la fenêtre exacte qui élimine la partie fractionnaire.
    io.phase_tronq := multResult(w_raw + w_out - 1, w_raw)
  } else {
    // Cas extrême (ex: configNeutre) : Il manque des bits pour remplir la sortie.
    // On extrait la partie entière disponible et on complète avec des zéros (padding).
    val missingBits = w_out - w_raw
    val phaseExtracted = multResult(2 * w_raw - 1, w_raw)
    io.phase_tronq := Cat(phaseExtracted, 0.U(missingBits.W))
  }
}

// Objet principal pour générer le composant individuel
object PA_V2 extends App {
  val myConfig = DdsConfigs.activeConfig

  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new PA_V2(myConfig),
    Array("--target-dir", "sortie_verilog")
  )
} */