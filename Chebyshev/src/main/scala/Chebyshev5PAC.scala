import chisel3._
import chisel3.util._

// phaseWidth : 12 bits (venant de l'accumulateur)
// ampWidth : 12 bits (résolution de sortie pour le DAC)
class Chebyshev5PAC(val config: DdsConfig) extends Module {
  val io = IO(new Bundle {
    val phaseIn = Input(UInt(config.phaseOutWidth.W))
    val ampOut  = Output(SInt(config.ampWidth.W)) 
  })

  // Récupération des largeurs depuis la configuration
  val phaseWidth = config.phaseOutWidth
  val ampWidth   = config.ampWidth

  // 1. Extraction des bits de contrôle de phase (Convertis en Booléens)
  val signBit   = io.phaseIn(phaseWidth - 1).asBool
  val mirrorBit = io.phaseIn(phaseWidth - 2).asBool

  // 2. Logique miroir avec Registre (Pipeline Cycle 0)
  val addrWidth  = phaseWidth - 2
  val rawAddress = io.phaseIn(phaseWidth - 3, 0)
  
  // Le registre bloque l'optimisation combinatoire agressive de CIRCT
  val actualAddressReg = RegInit(0.U(addrWidth.W))
  
  when(mirrorBit) {
    actualAddressReg := ((1 << addrWidth) - 1).U(addrWidth.W) - rawAddress
  } .otherwise {
    actualAddressReg := rawAddress
  }

  // 3. Alignement du premier niveau de contrôle
  val signBitReg1 = RegNext(signBit) // Étape de synchronisation initiale

  // 4. Conversion de l'adresse en SInt pour le calcul de Tchebychev
  val x = Wire(SInt((addrWidth + 1).W))   
  x := actualAddressReg.zext // Extension de signe positive (11 bits signés)

  // --- COEFFICIENTS DE TCHEBYCHEV EN VIRGULE FIXE (ORDRE 5 - SHIFT = 52) ---
  // Remplacer impérativement ces valeurs par les sorties exactes du script MATLAB
  val coeff_A = BigInt("3705995957370480361216").S(80.W)  // Reçoit A_amp * 2^(52 - 10)    // 3619136677119609856L.S 
  val coeff_B = BigInt("-90323621605265").S(80.W)        // Reçoit B_amp * 2^(52 - 30)  // -88206661724L.S 
  val coeff_C = BigInt("602440").S(80.W)               // Reçoit C_amp * 2^(52 - 50)   // 588.S
  // ------------------------------------------------------------------------

  // 5. Pipeline d'Arithmétique Évolué à Haute Fréquence (100 MHz)
  // Chaque niveau de RegNext coupe le chemin critique pour stabiliser le timing.

  // --- Cycle +1 : Éléments quadratiques et terme linéaire ---
  val x_delayed_reg1 = RegNext(x)
  val x_sq_reg       = RegNext(x * x)         // x² -> Converti en DW02_squarer par Design Compiler
  val mult_A_reg1    = RegNext(coeff_A * x)   // A * x
  val signBitReg2    = RegNext(signBitReg1)

  // --- Cycle +2 : Construction des puissances supérieures (x³ et x⁴) ---
  val x_delayed_reg2 = RegNext(x_delayed_reg1)
  val x_cb_reg       = RegNext(x_sq_reg * x_delayed_reg1) // x³ = x² * x
  val x_pow4_reg     = RegNext(x_sq_reg * x_sq_reg)       // x⁴ = x² * x² -> Deuxième squarer optimisé
  val mult_A_reg2    = RegNext(mult_A_reg1)
  val signBitReg3    = RegNext(signBitReg2)

  // --- Cycle +3 : Évaluation de x⁵ et produit partiel de B ---
  val x_pow5_reg     = RegNext(x_pow4_reg * x_delayed_reg2) // x⁵ = x⁴ * x
  val mult_B_reg     = RegNext(coeff_B * x_cb_reg)          // B * x³
  val mult_A_reg3    = RegNext(mult_A_reg2)
  val signBitReg4    = RegNext(signBitReg3)

  // --- Cycle +4 : Produit de C et première somme partielle ---
  val mult_C_reg     = RegNext(coeff_C * x_pow5_reg)        // C * x⁵
  val sum_AB_reg     = RegNext(mult_A_reg3 + mult_B_reg)    // (A * x) + (B * x³)
  val signBitReg5    = RegNext(signBitReg4)

  // --- Cycle +5 : Accumulation finale de la structure polynomiale ---
  val sum_raw        = RegNext(sum_AB_reg + mult_C_reg)     // A * x + B * x³ + C * x⁵
  val signBitReg6    = RegNext(signBitReg5)
  
  

  // 6. Troncature et Ajustement du Format de Sortie
  // Le décalage doit être de 52 pour correspondre à la dynamique de codage du coefficient C
  val shift_amount = 72    //62  


  // Gestion du 1/2 LSB
  val half_lsb = (BigInt("1") << (shift_amount - 1)).S   // "1"
  val rounded_sum = sum_raw + half_lsb


  val amp_unsigned = (rounded_sum >> shift_amount)(ampWidth - 2, 0) 

  val final_amp = Wire(SInt(ampWidth.W))  
  final_amp := amp_unsigned.zext // Normalisation au format signé positif avant inversion

  // 7. Application du signe final (Synchrone avec l'étage final du pipeline)
  // Utilisation de signBitReg5 pour correspondre exactement au retard de sum_raw (5 cycles)
  io.ampOut := RegNext(Mux(signBitReg6, -final_amp, final_amp))
}

// Objet principal pour générer le Verilog
object Generate5PAC extends App {
  val myConfig = DdsConfigs.activeConfig

  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new Chebyshev5PAC(myConfig),
    Array("--target-dir", "sortie_verilog")
  )
}