import chisel3._
import chisel3.util._

class Taylor2PAC_24bits(val config: DdsConfig) extends Module {
  val io = IO(new Bundle {
    val control = Input(UInt(3.W))                           
    val phaseIn = Input(UInt(config.phaseOutWidth.W))        
    val cosOut  = Output(SInt(config.ampWidth.W))
    val sinOut  = Output(SInt(config.ampWidth.W))
  })


  val segAddrWidth = 5  // 2 pour 4 segments, 3 pour 8, 4 pour 16, 5 pour 32...
  val romAmpWidth  = 18  // Largeur de la ROM (peut être augmentée à 24 si besoin)
  
  val phaseWidth = config.phaseOutWidth
  val ampWidth   = config.ampWidth
  

  val signBit = io.control(2) 
  val qwBit   = io.control(1) 
  val ewBit   = io.control(0) 

  val sinSign = signBit
  val cosSign = signBit ^ qwBit
  val isCosEq = qwBit ^ ewBit

  val pi_over_4_scaled = Math.round((Math.PI / 4.0) * (1L << phaseWidth)).toLong.U(phaseWidth.W)
  
  val actualPhaseReg = RegNext(
    Mux(ewBit, pi_over_4_scaled - io.phaseIn, io.phaseIn) 
  )

  val sinSignReg1 = RegNext(sinSign)
  val cosSignReg1 = RegNext(cosSign)
  val isCosEqReg1 = RegNext(isCosEq)

  val B = phaseWidth - segAddrWidth   
  val numSegments = 1 << segAddrWidth 

  val romMaxAmp = ((1L << (romAmpWidth - 1)) - 1).toDouble





  val evalPointTable = VecInit(Seq.tabulate(numSegments) { i =>   // largeur de l'adresse = 9 bits (B)
    val x0_raw = (i.toLong << B) + (1L << (B - 1))   // i*2⁹ + 2*8  
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

  val segmentAddr = actualPhaseReg(phaseWidth - 1, B) 

  val x0_reg     = RegNext(evalPointTable(segmentAddr))
  val cos_x0_reg = RegNext(cosTable(segmentAddr))
  val sin_x0_reg = RegNext(sinTable(segmentAddr))

  val x_delayed = RegNext(actualPhaseReg)

  val sinSignReg2 = RegNext(sinSignReg1)
  val cosSignReg2 = RegNext(cosSignReg1)
  val isCosEqReg2 = RegNext(isCosEqReg1)

  val dx_full = x_delayed.zext - x0_reg.zext    // dx = x - x0   Besoin de .zext car dx peut être négatif donc on doit passer en signé

  val dx = RegNext(dx_full(phaseWidth - 1, 0).asSInt)  

  val cos_x0_delayed = RegNext(cos_x0_reg)
  val sin_x0_delayed = RegNext(sin_x0_reg)

  val sinSignReg3 = RegNext(sinSignReg2)
  val cosSignReg3 = RegNext(cosSignReg2)
  val isCosEqReg3 = RegNext(isCosEqReg2)

  val m_cos = RegNext(dx * cos_x0_delayed)   
  val m_sin = RegNext(dx * sin_x0_delayed)

  val cos_x0_final = RegNext(cos_x0_delayed)
  val sin_x0_final = RegNext(sin_x0_delayed)

  val sinSignReg4 = RegNext(sinSignReg3)
  val cosSignReg4 = RegNext(cosSignReg3)
  val isCosEqReg4 = RegNext(isCosEqReg3)

  val sin_rom_aligned = (sin_x0_final << romAmpWidth).asSInt   // phaseWidth
  val cos_rom_aligned = (cos_x0_final << romAmpWidth).asSInt    // phaseWidth

  val sin_taylor = sin_rom_aligned +& m_cos  
  val cos_taylor = cos_rom_aligned -& m_sin  

  val shift_out = phaseWidth + romAmpWidth - ampWidth   
  
  // val round_out = (1L << (shift_out - 1)).S      // Arrondi convergent (recommandé)
  val sin_rounded = sin_taylor // +& round_out   
  val cos_rounded = cos_taylor // +& round_out  

  val sin_trunc = sin_rounded >> shift_out
  val cos_trunc = cos_rounded >> shift_out

  //val sin_sat = sin_trunc(ampWidth - 1, 0).asSInt
  //val cos_sat = cos_trunc(ampWidth - 1, 0).asSInt


  
  // Saturation finale aux bornes du DAC 
  val max_cmp = ((1 << (ampWidth - 1)) - 1).S
  val min_cmp = (-(1 << (ampWidth - 1))).S

  val max_out = ((1 << (ampWidth - 1)) - 1).S(ampWidth.W)
  val min_out = (-(1 << (ampWidth - 1))).S(ampWidth.W)

  val sin_sat = Mux(sin_trunc > max_cmp, max_out, 
                  Mux(sin_trunc < min_cmp, min_out, sin_trunc(ampWidth - 1, 0).asSInt))
                  
  val cos_sat = Mux(cos_trunc > max_cmp, max_out, 
                  Mux(cos_trunc < min_cmp, min_out, cos_trunc(ampWidth - 1, 0).asSInt))






  val preSignCos = Mux(isCosEqReg4, sin_sat, cos_sat)
  val preSignSin = Mux(isCosEqReg4, cos_sat, sin_sat)

  io.cosOut := RegNext(Mux(cosSignReg4, -preSignCos, preSignCos))
  io.sinOut := RegNext(Mux(sinSignReg4, -preSignSin, preSignSin))
}

object GenerateTaylor2PAC_24bits extends App {
  val myConfig = DdsConfigs.activeConfig

  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new Taylor2PAC_24bits(myConfig),
    Array("--target-dir", "sortie_verilog")
  )
}