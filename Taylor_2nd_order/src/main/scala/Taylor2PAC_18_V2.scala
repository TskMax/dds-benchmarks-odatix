import chisel3._
import chisel3.util._

class Taylor2PAC_18_V2(val config: DdsConfig) extends Module {
  val io = IO(new Bundle {
    val control = Input(UInt(3.W))
    val phaseIn = Input(UInt(18.W))
    val cosOut  = Output(SInt(16.W))
    val sinOut  = Output(SInt(16.W))
  })

 

  val segAddrWidth = 5 ;  val romAmpWidth = 18 ;   val phaseWidth = 18
 



  val signBit = io.control(2)
  val qwBit   = io.control(1)
  val ewBit   = io.control(0)
  

  val pi_over_4_rad = Math.round((Math.PI / 4.0) * (1L << phaseWidth)).U(phaseWidth.W)
  val pac_input_rad = RegNext(Mux(ewBit, pi_over_4_rad - io.phaseIn, io.phaseIn))

  val sinSignReg1 = RegNext(signBit)
  val cosSignReg1 = RegNext(signBit ^ qwBit)
  val isCosEqReg1 = RegNext(qwBit ^ ewBit)

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


  // dx = x - x0
  val dx_full = phase_rad_delayed.zext - x0_reg.zext
  val dx = RegNext(dx_full(17, 0).asSInt)

  val cos_x0_s3 = RegNext(cos_x0_reg)
  val sin_x0_s3 = RegNext(sin_x0_reg)

 val sinSignReg3 = RegNext(sinSignReg2)
  val cosSignReg3 = RegNext(cosSignReg2)
  val isCosEqReg3 = RegNext(isCosEqReg2)

  val sinSignReg4 = RegNext(sinSignReg3)
  val cosSignReg4 = RegNext(cosSignReg3)
  val isCosEqReg4 = RegNext(isCosEqReg3)

  val sinSignReg5 = RegNext(sinSignReg4)
  val cosSignReg5 = RegNext(cosSignReg4)
  val isCosEqReg5 = RegNext(isCosEqReg4)

  val p_cos = dx * cos_x0_s3  // 18x18 -> 36 bits
  val p_sin = dx * sin_x0_s3

  /*val m_cos_32 = RegNext(p_cos(35, 4).asSInt) // 32 bits
  val m_sin_32 = RegNext(p_sin(35, 4).asSInt) // 32 bits */

  //  (2^3 = 8) 
  val m_cos_32 = RegNext(((p_cos + 8.S)(35, 4)).asSInt) 
  val m_sin_32 = RegNext(((p_sin + 8.S)(35, 4)).asSInt)

  // 18 bits natifs + 13 bits de decalage = 31 bits, etendu a 32 bits
  val sin_x0_aligned = RegNext(sin_x0_s3 << 14) // 32 bits     
  val cos_x0_aligned = RegNext(cos_x0_s3 << 14) // 32 bits

  //(32 bits + 32 bits -> 32 bits) 
  val sin_sum = RegNext(sin_x0_aligned.pad(34) + m_cos_32.pad(34))      // 32 bits   
  val cos_sum = RegNext(cos_x0_aligned.pad(34) - m_sin_32.pad(34))      // 32 bits   
  
  val shift_to_16 = 16
  val round_bit = (1L << (shift_to_16 - 1)).S(34.W)
  //val round_bit = 0.S

  // Troncature 
  val sin_shifted = (sin_sum + round_bit) >> shift_to_16   // 32 - 15 = 17 bits
  val cos_shifted = (cos_sum + round_bit) >> shift_to_16   

 
  val targetAmpWidth = 16               
  val max_amp = ((1 << (targetAmpWidth - 1)) - 1).S 
  val min_amp = 0.S

 
  val sin_sat = Mux(sin_shifted > max_amp, max_amp, 
                  Mux(sin_shifted < min_amp, min_amp, sin_shifted))
  val cos_sat = Mux(cos_shifted > max_amp, max_amp, 
                  Mux(cos_shifted < min_amp, min_amp, cos_shifted))


  val pac_sin_out_final = sin_sat(15, 0).asSInt   
  val pac_cos_out_final = cos_sat(15, 0).asSInt

 
  val preSignCos = Mux(isCosEqReg5, pac_sin_out_final, pac_cos_out_final)
  val preSignSin = Mux(isCosEqReg5, pac_cos_out_final, pac_sin_out_final)

  io.cosOut := RegNext(Mux(cosSignReg5, -preSignCos, preSignCos))
  io.sinOut := RegNext(Mux(sinSignReg5, -preSignSin, preSignSin))
}

object GenerateTaylor2PAC_18_V2 extends App {
  val myConfig = DdsConfigs.activeConfig

  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new Taylor2PAC_18_V2(myConfig),
    Array("--target-dir", "sortie_verilog")
  )
}