import chisel3._
import chisel3.util._

class Taylor3PAC_12_V3(val config: DdsConfig) extends Module {
  val io = IO(new Bundle {
    val control = Input(UInt(3.W))                           
    val phaseIn = Input(UInt(18.W))       
    val cosOut  = Output(SInt(12.W))      
    val sinOut  = Output(SInt(12.W))      
  })

  val segAddrWidth = 2 
  val romAmpWidth  = 18  
  val phaseWidth   = 18

  val signBit = io.control(2) 
  val qwBit   = io.control(1) 
  val ewBit   = io.control(0) 

  // STAGE 0 : ADAPTATION AU PHASE ACCUMULATOR
  val pi_over_4_rad = Math.round((Math.PI / 4.0) * (1L << phaseWidth)).U(phaseWidth.W)
  val pac_input_rad = RegNext(Mux(ewBit, pi_over_4_rad - io.phaseIn, io.phaseIn))

  val sinSignReg1 = RegNext(signBit)
  val cosSignReg1 = RegNext(signBit ^ qwBit)
  val isCosEqReg1 = RegNext(qwBit ^ ewBit)

  // GENERATION DE LA LUT 
  val B = phaseWidth - segAddrWidth   
  val segmentAddr = pac_input_rad(phaseWidth - 1, B)   
  val numSegments = 1 << segAddrWidth    
  val romMaxAmp = ((1L << (romAmpWidth - 1)) - 1).toDouble

  val evalPointTable = VecInit(Seq.tabulate(numSegments) { i =>    
    val x0_raw = (i.toLong << B) + (1L << (B - 1))                 
    x0_raw.U(phaseWidth.W)                                         
  })                                                               

  val cosTable = VecInit(Seq.tabulate(numSegments) { i =>
    val x0_raw = (i.toLong << B) + (1L << (B - 1))                  
    val rad = x0_raw.toDouble / (1L << phaseWidth).toDouble         
    Math.round(Math.cos(rad) * romMaxAmp).toLong.S(romAmpWidth.W)   
  })

  val sinTable = VecInit(Seq.tabulate(numSegments) { i =>
    val x0_raw = (i.toLong << B) + (1L << (B - 1))
    val rad = x0_raw.toDouble / (1L << phaseWidth).toDouble 
    Math.round(Math.sin(rad) * romMaxAmp).toLong.S(romAmpWidth.W)   
  })

  val x0_reg     = RegNext(evalPointTable(segmentAddr))           
  val cos_x0_reg = RegNext(cosTable(segmentAddr))
  val sin_x0_reg = RegNext(sinTable(segmentAddr))

  val phase_rad_delayed = RegNext(pac_input_rad)

  val sinSignReg2 = RegNext(sinSignReg1)
  val cosSignReg2 = RegNext(cosSignReg1)
  val isCosEqReg2 = RegNext(isCosEqReg1)


  val dx_full = phase_rad_delayed.zext - x0_reg.zext      
  val dx = RegNext(dx_full(17, 0).asSInt)                 

  val cos_x0_s3 = RegNext(cos_x0_reg)
  val sin_x0_s3 = RegNext(sin_x0_reg)

  val sinSignReg3 = RegNext(sinSignReg2)
  val cosSignReg3 = RegNext(cosSignReg2)
  val isCosEqReg3 = RegNext(isCosEqReg2)


  
  /*
  // L'ajout de (1L << 17) force un arrondi au plus proche pour éliminer l'erreur du 1er ordre
  val m_cos_s4 = RegNext((dx * cos_x0_s3 + (1L << 17).S) >> 18)     
  val m_sin_s4 = RegNext((dx * sin_x0_s3 + (1L << 17).S) >> 18)  */ 


  //// STAGE 1
  val m_cos_s4 = RegNext((dx * cos_x0_s3)  >> 18)    // 18 bits   
  val m_sin_s4 = RegNext((dx * sin_x0_s3)  >> 18)


  // Quadratique sur 23 bits
  val dx_sq_s4 = RegNext((dx * dx) >> 13)   

  val cos_x0_s4 = RegNext(cos_x0_s3)
  val sin_x0_s4 = RegNext(sin_x0_s3)

  val sinSignReg4 = RegNext(sinSignReg3)
  val cosSignReg4 = RegNext(cosSignReg3)
  val isCosEqReg4 = RegNext(isCosEqReg3)

  // STAGE 2
  val m2_sin_s5 = RegNext((dx_sq_s4 * sin_x0_s4) >> 24)     // On arrive sur 17 au lieu de 18, normal on fait la division par 2!
  val m2_cos_s5 = RegNext((dx_sq_s4 * cos_x0_s4) >> 24)

  val m_cos_s5 = RegNext(m_cos_s4)
  val m_sin_s5 = RegNext(m_sin_s4)

  val cos_x0_s5 = RegNext(cos_x0_s4)
  val sin_x0_s5 = RegNext(sin_x0_s4)

  val sinSignReg5 = RegNext(sinSignReg4)
  val cosSignReg5 = RegNext(cosSignReg4)
  val isCosEqReg5 = RegNext(isCosEqReg4)

  // STAGE 3 
  // On effectue TOUTE la somme sur le bus complet pour ne perdre aucune donnée fractionnaire
  val sin_taylor = RegNext(sin_x0_s5 +& m_cos_s5 -& m2_sin_s5)   // 20 bits ??
  val cos_taylor = RegNext(cos_x0_s5 -& m_sin_s5 -& m2_cos_s5)   

  val sinSignReg6 = RegNext(sinSignReg5)
  val cosSignReg6 = RegNext(cosSignReg5)
  val isCosEqReg6 = RegNext(isCosEqReg5)


  // STAGE 4 
  // Arrondi final + Troncature directement depuis le bus combiné (14 bits résultants)
  val pac_sin_raw = sin_taylor >> 6  // 12 bits ??  
  val pac_cos_raw = cos_taylor >> 6

  val targetAmpWidth = 12               
  val max_amp = ((1 << (targetAmpWidth - 1)) - 1).S 
  val min_amp = 0.S

  // Saturation appliquée sur le bus large
  val sin_sat = Mux(pac_sin_raw > max_amp, max_amp, 
                  Mux(pac_sin_raw < min_amp, min_amp, pac_sin_raw))
  val cos_sat = Mux(pac_cos_raw > max_amp, max_amp, 
                  Mux(pac_cos_raw < min_amp, min_amp, pac_cos_raw))

  // Extraction finale propre à 12 bits
  val pac_sin_out = sin_sat(targetAmpWidth-1, 0).asSInt   
  val pac_cos_out = cos_sat(targetAmpWidth-1, 0).asSInt

  val preSignCos = Mux(isCosEqReg6, pac_sin_out, pac_cos_out)
  val preSignSin = Mux(isCosEqReg6, pac_cos_out, pac_sin_out)

  io.cosOut := RegNext(Mux(cosSignReg6, -preSignCos, preSignCos))
  io.sinOut := RegNext(Mux(sinSignReg6, -preSignSin, preSignSin))
}

object GenerateTaylor3PAC_12_V3 extends App {
  val myConfig = DdsConfigs.activeConfig

  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new Taylor3PAC_12_V3(myConfig),
    Array("--target-dir", "sortie_verilog")
  )
}