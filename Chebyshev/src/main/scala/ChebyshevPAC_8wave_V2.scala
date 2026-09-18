import chisel3._
import chisel3.util._

class ChebyshevPAC_8wave_V2(val config: DdsConfig) extends Module {
  val io = IO(new Bundle {
    val controlIn = Input(UInt(3.W))      
    val phaseIn   = Input(UInt(config.phaseOutWidth.W))     
    val cosOut    = Output(SInt(config.ampWidth.W))
    val sinOut    = Output(SInt(config.ampWidth.W))
  })

  val ampWidth   = config.ampWidth
  val phaseWidth = config.phaseOutWidth

  // --- SECURITES DE GENERATION ---
  require(phaseWidth >= 11, "Erreur : La taille de phase doit etre >= 11 bits pour permettre la troncature interne a 23 bits.")
  require(ampWidth == 12, "Erreur : Les coefficients 18-bits en dur sont calibres pour une amplitude DAC de 12 bits.")

  // --- CYCLE 0 : Signaux de controle ---
  val signBit   = io.controlIn(2).asBool
  val qwBit     = io.controlIn(1).asBool
  val ewBit     = io.controlIn(0).asBool

  val sinSign = signBit
  val cosSign = signBit ^ qwBit
  val isCosEq = qwBit ^ ewBit

  // --- CYCLE 1 : Repliement de phase ---
  val actualAddressReg = RegInit(0.U(phaseWidth.W))
  
  // Calcul dynamique de pi/4 selon la largeur de la phase
  val pi_over_4_val = Math.round((Math.PI / 4.0) * (1 << phaseWidth)).toInt
  val max_phase_hw  = pi_over_4_val.U(phaseWidth.W)   
  
  when(ewBit) {      
    actualAddressReg := max_phase_hw - io.phaseIn 
  } .otherwise {
    actualAddressReg := io.phaseIn 
  }

  val x = Wire(SInt((phaseWidth + 1).W))   
  x := actualAddressReg.zext  

  // --- COEFFICIENTS MATERIELS (18 BITS SIGNES) ---
  val coeff_s1 = 115245.S(18.W)
  val coeff_s3 = (-5274).S(18.W)

  val coeff_c0 = 131008.S(18.W)
  val coeff_c2 = (-60166).S(18.W)
  val coeff_c4 = 663.S(18.W)

  // --- PIPELINE ARITHMETIQUE ---

  // CYCLE 2 : Calcul de x et x2
  val x_reg    = RegNext(x)                 
  val x2_raw   = x * x                                              
  
  // Extraction dynamique de 23 bits (garde toujours 21 bits fractionnaires)
  val x2_msb   = (2 * phaseWidth) + 1    // 2*18 + 1 = 37
  val x2_lsb   = x2_msb - 22
  val x2_trunc = RegNext(x2_raw(x2_msb, x2_lsb).asSInt)       

  // CYCLE 3 : Calcul de x3 et x4
  val x3_raw   = x2_trunc * x_reg                    
  val x3_reg   = RegNext(x3_raw)            

  val x4_raw   = x2_trunc * x2_trunc                 
  // x4_raw fait TOUJOURS 46 bits car x2_trunc fait TOUJOURS 23 bits. Indices constants.
  val x4_trunc = RegNext(x4_raw(45, 23).asSInt)      

  // CYCLE 4 : Alignement temporel
  val x_for_sub  = ShiftRegister(x_reg, 2)      
  val x2_for_sub = ShiftRegister(x2_trunc, 2)   
  val x3_for_sub = RegNext(x3_reg)              
  val x4_for_sub = RegNext(x4_trunc)            

  // CYCLE 5 : Soustractions
  // Ces decalages sont mathematiquement constants grace a la troncature fixe de x2
  val x_times_3   = (x_for_sub * 3.S) << 21            
  val x3_times_4  = x3_for_sub * 4.S                   
  val base_sin_s3 = RegNext(x3_times_4 - x_times_3)    
  
  val x4_times_8  = (x4_for_sub * 8.S) << 2                         
  val x2_times_8  = x2_for_sub * 8.S                   
  val base_cos_c4 = RegNext(x4_times_8 - x2_times_8)   

  // CYCLE 6 : Multiplications par les coeffs
  val x_for_coeff  = ShiftRegister(x_reg, 3)    
  val x2_for_coeff = ShiftRegister(x2_trunc, 3) 

  val mult_s1     = RegNext(coeff_s1 * x_for_coeff)   
  val mult_s3     = RegNext(coeff_s3 * base_sin_s3)   
  
  val mult_c2     = RegNext(coeff_c2 * x2_for_coeff)  
  val mult_c4     = RegNext(coeff_c4 * base_cos_c4)   

  // CYCLE 7 : Alignement dynamique selon la largeur de phase
  // La cible dynamique est (27 + phaseWidth) bits fractionnaires
  val s1_aligned = mult_s1 << 21                 
  val c2_aligned = mult_c2 << phaseWidth         
  val c4_aligned = mult_c4 << phaseWidth         
  val c0_aligned = coeff_c0 << (21 + phaseWidth) 

  val sum_sin     = RegNext(s1_aligned +& mult_s3)                                              
  val sum_cos     = RegNext(c0_aligned +& c2_aligned +& c4_aligned)      

  // Retard des signaux de controle
  val isCosEq_pipe = ShiftRegister(isCosEq, 8)    
  val sinSign_pipe = ShiftRegister(sinSign, 8)
  val cosSign_pipe = ShiftRegister(cosSign, 8)

  // CYCLE 8 : Arrondi et Croisement
  val shift_amount = 27 + phaseWidth  
  val half_lsb     = (1L << (shift_amount - 1)).S

  val rounded_sin  = RegNext(sum_sin +& half_lsb)  
  val rounded_cos  = RegNext(sum_cos +& half_lsb)
  
  val sin_shifted = rounded_sin >> shift_amount  
  val cos_shifted = rounded_cos >> shift_amount   

  val final_sin_eq = Wire(SInt(ampWidth.W))  
  val final_cos_eq = Wire(SInt(ampWidth.W))  
  
  final_sin_eq := sin_shifted(ampWidth - 2, 0).zext   
  final_cos_eq := cos_shifted(ampWidth - 2, 0).zext   

  val preSignCos = Mux(isCosEq_pipe, final_sin_eq, final_cos_eq)
  val preSignSin = Mux(isCosEq_pipe, final_cos_eq, final_sin_eq)

  // CYCLE 9 : Sortie finale
  io.cosOut := RegNext(Mux(cosSign_pipe, -preSignCos, preSignCos))
  io.sinOut := RegNext(Mux(sinSign_pipe, -preSignSin, preSignSin))
}

object GenerateChebyshevPAC_8wave_V2 extends App {
  val myConfig = DdsConfigs.activeConfig

  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new ChebyshevPAC_8wave_V2(myConfig),
    Array("--target-dir", "sortie_verilog")
  )
}