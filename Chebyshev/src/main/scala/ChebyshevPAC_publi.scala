import chisel3._
import chisel3.util._

class ChebyshevPAC_publi(val config: DdsConfig) extends Module {
  val io = IO(new Bundle {
    val control = Input(UInt(3.W))      
    val phaseIn   = Input(UInt(config.phaseOutWidth.W))     
    val cosOut    = Output(SInt(config.ampWidth.W))
    val sinOut    = Output(SInt(config.ampWidth.W))
  })

  val ampWidth   = config.ampWidth
  val phaseWidth = config.phaseOutWidth

  // --- CYCLE 0 : Signaux de controle ---
  val signBit = io.control(2).asBool
  val qwBit   = io.control(1).asBool
  val ewBit   = io.control(0).asBool

  val sinSign = signBit
  val cosSign = signBit ^ qwBit
  val isCosEq = qwBit ^ ewBit

  // CYCLE 1 : Repliement de phase (Onde triangulaire)
  val actualAddressReg = RegInit(0.U(phaseWidth.W))
  val pi_over_4_val = Math.round((Math.PI / 4.0) * (1 << phaseWidth)).toInt
  val max_phase_hw  = pi_over_4_val.U(phaseWidth.W)   
  
  when(ewBit) {      
    actualAddressReg := max_phase_hw - io.phaseIn 
  } .otherwise {
    actualAddressReg := io.phaseIn 
  }

  val x = Wire(SInt((phaseWidth + 1).W))    // x est entre 0 et pi/4(=0,785<1), donc que des bits fractionnaires + 1 bit de signe 
  x := actualAddressReg.zext                // x sur 15 bits     


  // Valeurs exactes pour entree en radians [0, pi/4]
  val coeff_s1 = 115145.S(18.W)                          // Q17       114737
  val coeff_s3 = (-5277).S(18.W)                        // -5436
  
  // 1.0 mathematique au format Q17 (2^17)
  val coeff_c0 = 131071.S(18.W)                           // 131008
  val coeff_c2 = (-30096).S(18.W)                       // -30061
  val coeff_c4 = 665.S(18.W)                           // 670



  // CYCLE 2 : Calcul de x et x2
  val x_reg    = RegNext(x)                 
  val x2_raw   = x * x                                      // x²  ; 30 bits    (Q28)                                     
  

  val x2_msb   = (2 * phaseWidth) + 1                       // = 29
  val x2_lsb   = x2_msb - 22                                // 29-22 = 7
  val x2_trunc = RegNext(x2_raw(x2_msb, x2_lsb).asSInt)     // 23 bits (publi)    (Q21) 

  // CYCLE 3 : Calcul de x3 et x4
  val x3_raw   = x2_trunc * x_reg                           // x³  ;   38 bits    (Q35)          
  val x3_reg   = RegNext(x3_raw)            

  val x4_raw   = x2_trunc * x2_trunc                        // x⁴  ; 46 bits      (Q42)             
  val x4_trunc = RegNext(x4_raw(45, 23).asSInt)             // 23 bits (publi)    (Q19)  

  // CYCLE 4 : Alignement temporel des etages
  val x_for_sub  = ShiftRegister(x_reg, 2)                    
  val x2_for_sub = ShiftRegister(x2_trunc, 2)   
  val x3_for_sub = RegNext(x3_reg)              
  val x4_for_sub = RegNext(x4_trunc)            

  // CYCLE 5 : Soustractions et preparation polynomes
  // Voie Sinus : 4x^3 - 3x 
  val x_times_3   = (x_for_sub +& x_for_sub +& x_for_sub) << 21      //  3x      15 + 21 + 2 = 38 bits    (14Q + 21Q = 35Q)
  val x3_times_4  = x3_for_sub << 2                                  //  4x³     38+2 = 40 bits           (21Q + 14Q = 35Q)  le <<2 ici est une multi par 4 et non un alignement de la virgule. on ne le compte donc pas dans les bits fraqutionnaires
  val base_sin_s3 = RegNext(x3_times_4 - x_times_3)                  //  40 bits   (35Q)    chisel est capable de tolerer la différence de taille entre les 2 bus. 
  
  // Voie Cosinus : 8x^4 - 8x^2 
  val x4_times_8 = x4_for_sub << 5                                   // 23+5 = 28 bits     (19Q + 2Q = 21Q)   2Q pour l'allignement et 3pasQ pour la multi par 8
 // val x4_times_8_trunc = RegNext(x4_times_8(22, 0).asSInt)                         
  val x2_times_8  = x2_for_sub << 3                                  // 23 + 3 = 26 bits   (21Q)
 // val x2_times_8_trunc = RegNext(x2_times_8(22, 0).asSInt)        

 val x4_times_8_trunc = x4_times_8(22, 0).asSInt                     // 23 bits      (21Q)
  val x2_times_8_trunc = x2_times_8(22, 0).asSInt                    // 23 bits      (21Q)

  val base_cos_c4_raw = x4_times_8_trunc - x2_times_8_trunc          // 23 bits : 8x⁴ - 8x²   (21Q)
  val base_cos_c4 = RegNext(base_cos_c4_raw(22, 0).asSInt)          

  // CYCLE 6 : Multiplications par les coefficients
  val x_for_coeff  = ShiftRegister(x_reg, 3)    
  val x2_for_coeff = ShiftRegister(x2_trunc, 3) 

  val mult_s1 = RegNext(coeff_s1 * x_for_coeff)                     // 18 + 15 = 33 bits   (17Q + 14Q = 31Q)
  val mult_s3 = RegNext(coeff_s3 * base_sin_s3)                     // 18 + 40 = 58 bits   (17Q + 35Q = 52Q)
  
  // Multiplication par 2x^2 
  val x2_times_2 = x2_for_coeff << 1                                // 23 + 1 = 24 bits    (21Q)
  val x2_times_2_trunc = x2_times_2(22, 0).asSInt                    // 23 bits            (21Q)
  val mult_c2 = RegNext(coeff_c2 * x2_times_2_trunc)                // 18 + 23 = 41 bits   (17Q + 21Q = 38Q)
  val mult_c4 = RegNext(coeff_c4 * base_cos_c4)                     // 18 + 23 = 41 bits   (17Q + 21Q = 38Q)

  // CYCLE 7 : Alignement dynamique global 
  val s1_aligned = mult_s1 << 21                                    // 33 + 21 = 54 bits    (31Q + 21Q = 52Q)  
  val c2_aligned = mult_c2 << phaseWidth                            // 41 + 14 = 55 bits    (38Q + 14Q = 52Q)
  val c4_aligned = mult_c4 << phaseWidth                            // 41 + 14 = 55 bits    (38Q + 14Q = 52Q)
  val c0_aligned = coeff_c0 << (21 + phaseWidth)                    // 18 + 23 + 14 = 55 bits  (17Q + 21Q + 14Q = 52Q)

  val sum_sin = RegNext(s1_aligned + mult_s3)                       //  58 bits (52Q)                     
  val sum_cos = RegNext(c0_aligned +& c2_aligned +& c4_aligned)     //  57 bits (52Q)

  // Retard des signaux de controle pour synchronisation (8 cycles)
  val isCosEq_pipe = ShiftRegister(isCosEq, 8)    
  val sinSign_pipe = ShiftRegister(sinSign, 8)
  val cosSign_pipe = ShiftRegister(cosSign, 8)

  // CYCLE 8 : Arrondi, Troncature et Croisement
  val shift_amount = 41                                // 52Q - 41Q = 11Q ce que le'on recherche...      


/*
  val half_lsb     = (1L << (shift_amount - 1)).S                   
  val rounded_sin = RegNext(sum_sin +& half_lsb)                    // 58 bits                 
  val rounded_cos = RegNext(sum_cos +& half_lsb)                    // 58 bits
*/
  

  // alignemt de la virgule fixe
  val reg_sin = RegNext(sum_sin)                                    // 58 bits                 
  val reg_cos = RegNext(sum_cos)                                    // 58 bits
  
  val sin_shifted = reg_sin >> shift_amount                     // 58 - 41 = 17 bits    (52Q - 41Q = 11Q)
  val cos_shifted = reg_cos >> shift_amount                     // 57 - 41 = 16 bits    (52Q - 41Q = 11Q)

  // Wire designe un noeud purement combinatoire (= signal en VHDL) donc ne nécessite pas de registre pour le stockage. Il est donc plus rapide que RegNext qui lui est un registre synchrone.
  val final_sin_eq = Wire(SInt(ampWidth.W))  
  val final_cos_eq = Wire(SInt(ampWidth.W))  
  
  // Extraction stricte sur 12 bits signés
  final_sin_eq := sin_shifted(ampWidth - 1, 0).asSInt               // 12 bits signés (11Q)
  final_cos_eq := cos_shifted(ampWidth - 1, 0).asSInt               // 12 bits signés (11Q)

  val preSignCos = Mux(isCosEq_pipe, final_sin_eq, final_cos_eq)
  val preSignSin = Mux(isCosEq_pipe, final_cos_eq, final_sin_eq)

  // CYCLE 9 : Registres de sortie
  io.cosOut := RegNext(Mux(cosSign_pipe, -preSignCos, preSignCos))
  io.sinOut := RegNext(Mux(sinSign_pipe, -preSignSin, preSignSin))
}


object GenerateChebyshevPAC_publi extends App {
  val myConfig = DdsConfigs.activeConfig

  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new ChebyshevPAC_publi(myConfig),
    Array("--target-dir", "sortie_verilog")
  )
}