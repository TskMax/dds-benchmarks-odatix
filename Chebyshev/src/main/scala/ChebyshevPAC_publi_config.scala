import chisel3._
import chisel3.util._

//version configurable, si la phase < 14 bits, on fait du zero-padding sur les LSB.
// Si la phase > 14 bits, on tronque les LSB pour garder 14 bits internes pour figer la virgule fixe.
// De toute façon sa sert à rien d'aller au dela de 14 bits (PhaseWidth >= AmpWidth+2)

class ChebyshevPAC_publi_config(val config: DdsConfig) extends Module {
  val io = IO(new Bundle {
    val control   = Input(UInt(3.W))      
    val phaseIn   = Input(UInt(config.phaseOutWidth.W))     
    val cosOut    = Output(SInt(config.ampWidth.W))
    val sinOut    = Output(SInt(config.ampWidth.W))
  })

  val ampWidth   = config.ampWidth
  val phaseWidth = config.phaseOutWidth // Variable : 8, 10, 12, 14, 16...

  // --- CYCLE 0 : Signaux de controle ---
  val signBit = io.control(2).asBool
  val qwBit   = io.control(1).asBool
  val ewBit   = io.control(0).asBool

  val sinSign = signBit
  val cosSign = signBit ^ qwBit
  val isCosEq = qwBit ^ ewBit

  // --- CYCLE 1 : Repliement de phase ---
  // S'adapte dynamiquement a la taille reelle de l'entree
  val actualAddressReg = RegInit(0.U(phaseWidth.W))
  val pi_over_4_val = Math.round((Math.PI / 4.0) * (1 << phaseWidth)).toInt
  val max_phase_hw  = pi_over_4_val.U(phaseWidth.W)   
  
  when(ewBit) {      
    actualAddressReg := max_phase_hw - io.phaseIn 
  } .otherwise {
    actualAddressReg := io.phaseIn 
  }

  // On normalise sur 14 bits internes pour figer la virgule fixe
  val internalPhaseWidth = 14
  val x_normalized = Wire(UInt(internalPhaseWidth.W))

  if (phaseWidth > internalPhaseWidth) {
    // Troncature des LSB si on a trop de precision (ex: 16 bits)
    x_normalized := actualAddressReg(phaseWidth - 1, phaseWidth - internalPhaseWidth)
  } else if (phaseWidth < internalPhaseWidth) {
    // Zero-padding des LSB si on manque de bits (ex: 8 bits)
    x_normalized := Cat(actualAddressReg, 0.U((internalPhaseWidth - phaseWidth).W))
  } else {
    // Connexion directe si on a exactement 14 bits
    x_normalized := actualAddressReg
  }

  val x = Wire(SInt((internalPhaseWidth + 1).W))    
  x := x_normalized.zext                

  // Valeurs exactes pour entree en radians [0, pi/4]
  val coeff_s1 = 115145.S(18.W)                          
  val coeff_s3 = (-5277).S(18.W)                        
  val coeff_c0 = 131071.S(18.W)                           
  val coeff_c2 = (-30096).S(18.W)                       
  val coeff_c4 = 665.S(18.W)                           

  // --- CYCLE 2 ---
  val x_reg    = RegNext(x)                 
  val x2_raw   = x * x                                                                            
  
  // Utilisation exclusive de la largeur interne (14) pour figer la virgule fixe
  val x2_msb   = (2 * internalPhaseWidth) + 1                       
  val x2_lsb   = x2_msb - 22                                
  val x2_trunc = RegNext(x2_raw(x2_msb, x2_lsb).asSInt)     

  // --- CYCLE 3 ---
  val x3_raw   = x2_trunc * x_reg                                      
  val x3_reg   = RegNext(x3_raw)            

  val x4_raw   = x2_trunc * x2_trunc                                     
  val x4_trunc = RegNext(x4_raw(45, 23).asSInt)               

  // --- CYCLE 4 ---
  val x_for_sub  = ShiftRegister(x_reg, 2)                    
  val x2_for_sub = ShiftRegister(x2_trunc, 2)   
  val x3_for_sub = RegNext(x3_reg)              
  val x4_for_sub = RegNext(x4_trunc)            

  // --- CYCLE 5 ---
  val x_times_3   = (x_for_sub +& x_for_sub +& x_for_sub) << 21      
  val x3_times_4  = x3_for_sub << 2                                  
  val base_sin_s3 = RegNext(x3_times_4 - x_times_3)                  
  
  val x4_times_8 = x4_for_sub << 5                                   
  val x2_times_8 = x2_for_sub << 3                                  
  
  // J'ai reintegre la protection "23, 0" obligatoire pour eviter l'overflow du +3.04
  val x4_times_8_trunc = x4_times_8(22, 0).asSInt                     
  val x2_times_8_trunc = x2_times_8(22, 0).asSInt                    

  val base_cos_c4_raw = x4_times_8_trunc - x2_times_8_trunc          
  val base_cos_c4 = RegNext(base_cos_c4_raw(22, 0).asSInt)          

  // --- CYCLE 6 ---
  val x_for_coeff  = ShiftRegister(x_reg, 3)    
  val x2_for_coeff = ShiftRegister(x2_trunc, 3) 

  val mult_s1 = RegNext(coeff_s1 * x_for_coeff)                     
  val mult_s3 = RegNext(coeff_s3 * base_sin_s3)                     
  
  val x2_times_2 = x2_for_coeff << 1                                
  val x2_times_2_trunc = x2_times_2(22, 0).asSInt                    
  val mult_c2 = RegNext(coeff_c2 * x2_times_2_trunc)                
  val mult_c4 = RegNext(coeff_c4 * base_cos_c4)                     

  // --- CYCLE 7 ---
  val s1_aligned = mult_s1 << 21                                      
  val c2_aligned = mult_c2 << internalPhaseWidth                            
  val c4_aligned = mult_c4 << internalPhaseWidth                            
  val c0_aligned = coeff_c0 << (21 + internalPhaseWidth)                    

  val sum_sin = RegNext(s1_aligned + mult_s3)                                           
  val sum_cos = RegNext(c0_aligned +& c2_aligned +& c4_aligned)     

  val isCosEq_pipe = ShiftRegister(isCosEq, 8)    
  val sinSign_pipe = ShiftRegister(sinSign, 8)
  val cosSign_pipe = ShiftRegister(cosSign, 8)

  // --- CYCLE 8 ---
  // J'ai retabli le code avec l'arrondi (half_lsb) pour garantir un spectre pur
  val shift_amount = 41                                     
  
  /*
  val half_lsb     = (1L << (shift_amount - 1)).S                   

  val rounded_sin = RegNext(sum_sin +& half_lsb)                                    
  val rounded_cos = RegNext(sum_cos +& half_lsb)                                    
  */
 // alignemt de la virgule fixe
  val reg_sin = RegNext(sum_sin)                                    // 58 bits                 
  val reg_cos = RegNext(sum_cos)                                    // 58 bits
  
  val sin_shifted = reg_sin >> shift_amount                     // 58 - 41 = 17 bits    (52Q - 41Q = 11Q)
  val cos_shifted = reg_cos >> shift_amount                     // 57 - 41 = 16 bits    (52Q - 41Q = 11Q)


  val final_sin_eq = Wire(SInt(ampWidth.W))  
  val final_cos_eq = Wire(SInt(ampWidth.W))  
  
  final_sin_eq := sin_shifted(ampWidth - 1, 0).asSInt               
  final_cos_eq := cos_shifted(ampWidth - 1, 0).asSInt               

  val preSignCos = Mux(isCosEq_pipe, final_sin_eq, final_cos_eq)
  val preSignSin = Mux(isCosEq_pipe, final_cos_eq, final_sin_eq)

  // --- CYCLE 9 ---
  io.cosOut := RegNext(Mux(cosSign_pipe, -preSignCos, preSignCos))
  io.sinOut := RegNext(Mux(sinSign_pipe, -preSignSin, preSignSin))
}

object GenerateChebyshevPAC_publi_config extends App {
  val myConfig = DdsConfigs.activeConfig

  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new ChebyshevPAC_publi_config(myConfig),
    Array("--target-dir", "sortie_verilog")
  )
}