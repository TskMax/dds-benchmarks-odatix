import chisel3._
import chisel3.util._

// phaseWidth : 12 bits (venant de l'accumulateur)
// ampWidth : 12 bits (résolution de sortie pour ton DAC)
class ChebyshevPAC_8wave(val config: DdsConfig) extends Module {
  val io = IO(new Bundle {
    val phaseIn = Input(UInt(config.phaseOutWidth.W))
    val ampOut  = Output(SInt(config.ampWidth.W)) 
  })

// Recuperation des largeurs depuis la configuration
  val phaseWidth = config.phaseOutWidth
  val ampWidth   = config.ampWidth

 // 1. Extraction des bits de contrôle de phase (Convertis en Booléens)
  val signBit   = io.phaseIn(phaseWidth - 1).asBool
  val qwBit     = io.phaseIn(phaseWidth - 2).asBool
  val ewBit     = io.phaseIn(phaseWidth - 3).asBool // Eighth Wave (0 a pi/4 vs pi/4 a pi/2)

  //si on est dans un octant impair, on doit lire a l'envers et utiliser l'equation du Cosinus
  val isCosEq = qwBit ^ ewBit // XOR entre les deux bits de controle 


  // 2. Logique miroir avec Registre (Pipeline)
  val addrWidth  = phaseWidth - 3
  val rawAddress = io.phaseIn(addrWidth - 1, 0)
  

  val actualAddressReg = RegInit(0.U(addrWidth.W))
  val isCosEqReg1      = RegNext(isCosEq)
  val signBitReg1      = RegNext(signBit)
  
  
  // Assignation propre dans le registre

  when(ewBit) {
    actualAddressReg := ((1 << addrWidth) - 1).U(addrWidth.W) - rawAddress
  } .otherwise {
    actualAddressReg := rawAddress
  }

  val x = Wire(SInt((addrWidth + 1).W))   
  x := actualAddressReg.zext  

  // --- COEFFICIENTS DE TCHEBYCHEV EN VIRGULE FIXE (SHIFT = 44) ---
  // Valeurs exactes a inserer depuis le script MATLAB
  val coeff_Sin_A = 13606712577507L.S   
  val coeff_Sin_B = (-289274L).S       
  
  val coeff_Cos_C0 =  36011204832919552L.S // Constante 1.0 * 2^44 (Exemple)
  val coeff_Cos_C2 = (-2644033945L).S 
  val coeff_Cos_C4 =  31L.S            // Ton coefficient optimisé
  // ---------------------------------------------------------------

  // 5. Pipeline d'Arithmetique Optimise (y = A*x + B*x^3)

  // Cycle +1 apres actualAddressReg :
  val x_delayed_reg1 = RegNext(x)
  val x_sq_reg       = RegNext(x * x)  // x²   //DesignCompiler grace à DesignWare va directement transformer en square : DWO2_squarer
  val mult_Sin_A_reg = RegNext(coeff_Sin_A * x)  // A*x


  val isCosEqReg2    = RegNext(isCosEqReg1)
  val signBitReg2    = RegNext(signBitReg1)

  // Cycle +2 
  val x_cb_reg        = RegNext(x_sq_reg * x_delayed_reg1)  // x^3
  val x_pow4_reg      = RegNext(x_sq_reg * x_sq_reg)        // x^4
  
  val mult_Cos_C2_reg = RegNext(coeff_Cos_C2 * x_sq_reg)
  val mult_Sin_A_reg2 = RegNext(mult_Sin_A_reg) // Propagation de A*x
  
  val isCosEqReg3     = RegNext(isCosEqReg2)
  val signBitReg3     = RegNext(signBitReg2)

// --- Cycle +3 ---
  val mult_Sin_B_reg  = RegNext(coeff_Sin_B * x_cb_reg)                 
  val mult_Cos_C4_reg = RegNext(coeff_Cos_C4 * x_pow4_reg)
  
  val mult_Cos_C2_reg2= RegNext(mult_Cos_C2_reg) // Propagation de C2*x^2
  val mult_Sin_A_reg3 = RegNext(mult_Sin_A_reg2) // Propagation de A*x
  
  val isCosEqReg4     = RegNext(isCosEqReg3)
  val signBitReg4     = RegNext(signBitReg3)

  // --- Cycle +4 : Additions finales ---
  val sum_Sin_raw = RegNext(mult_Sin_A_reg3 + mult_Sin_B_reg)                     // Ax + Bx^3
  val sum_Cos_raw = RegNext(coeff_Cos_C0 + mult_Cos_C2_reg2 + mult_Cos_C4_reg)    // C0 + C2x^2 + C4x^4
  
  val isCosEqReg5 = RegNext(isCosEqReg4)
  val signBitReg5 = RegNext(signBitReg4)


  //Sélection et Troncature (Cycle +5)
  val selected_sum = RegNext(Mux(isCosEqReg5, sum_Cos_raw, sum_Sin_raw))
  val signBitReg6  = RegNext(signBitReg5)


  val shift_amount = 44  


  //ajout du demi LSB
  val half_lsb = (1L << (shift_amount - 1)).S
  val rounded_sum = selected_sum + half_lsb
  val amp_unsigned = (rounded_sum >> shift_amount)(ampWidth - 2, 0)   // c est une simple opération de routage, pas de contraintes
   // Cette opération n'utilise pas de portes logiques, de puissances ou bien de sillicium !  Fais 14 bits, les 2 MSB supplémentaires etaient nécessaire aux multiplieurs. Le sinus ne peut pas dépasser +2047, ces 2 bits ne conteignes que des bits 0 de guarde.


  val final_amp = Wire(SInt(ampWidth.W))  // wire() sert ici de passerelle de typage et de préparation  
  final_amp := amp_unsigned.zext  // rajout d un zéro pour obtenir les 12 bits nécessaires. (signed positif car c est apres le role du multiplexeur qui permet de rendre négatif ou non)

  // 7. Application du signe final (Synchrone avec la fin du pipeline de calcul)
  io.ampOut := RegNext(Mux(signBitReg6, -final_amp, final_amp))
}



// Objet principal pour générer le Verilog
object GeneratePAC extends App {
  // On va chercher la meme configuration
  val myConfig = DdsConfigs.activeConfig

  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    // On utilise la nouvelle architecture polynomiale de Tchebychev
    new ChebyshevPAC_8wave(myConfig),
    Array("--target-dir", "sortie_verilog")
  )
}

 






  