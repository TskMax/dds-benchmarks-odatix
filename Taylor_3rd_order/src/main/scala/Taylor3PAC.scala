import chisel3._
import chisel3.util._

class Taylor3PAC(val config: DdsConfig) extends Module {
  val io = IO(new Bundle {
    val control = Input(UInt(3.W))                           
    val phaseIn = Input(UInt(config.phaseOutWidth.W)) // 18 bits attendus
    val cosOut  = Output(SInt(config.ampWidth.W))
    val sinOut  = Output(SInt(config.ampWidth.W))
  })

  // Paramétrage à 2 bits de segments (4 partitions)
  val segAddrWidth = 2        // 2 pour 4 seg, 3 pour 8 seg, 4 pour 16 seg et 5 pour 32 seg
  val romAmpWidth  = 18  
  
  val phaseWidth = config.phaseOutWidth
  val ampWidth   = config.ampWidth

  // ==========================================
  // RÉINTÉGRATION LOGIQUE +0.5 LSB
  // ==========================================
  val extPhaseWidth = phaseWidth + 1
  val phaseInExt = Cat(io.phaseIn, 1.U(1.W))

  val signBit = io.control(2) 
  val qwBit   = io.control(1) 
  val ewBit   = io.control(0) 

  val sinSign = signBit
  val cosSign = signBit ^ qwBit
  val isCosEq = qwBit ^ ewBit

  val pi_over_4_scaled = Math.round((Math.PI / 4.0) * (1L << extPhaseWidth)).toLong.U(extPhaseWidth.W)
  
  val actualPhaseReg = RegNext(
    Mux(ewBit, pi_over_4_scaled - phaseInExt, phaseInExt) 
  )

  val sinSignReg1 = RegNext(sinSign)
  val cosSignReg1 = RegNext(cosSign)
  val isCosEqReg1 = RegNext(isCosEq)

  val B = extPhaseWidth - segAddrWidth   
  val numSegments = 1 << segAddrWidth 

  val romMaxAmp = ((1L << (romAmpWidth - 1)) - 1).toDouble

  val evalPointTable = VecInit(Seq.tabulate(numSegments) { i => 
    val x0_raw = (i.toLong << B) + (1L << (B - 1))      
    x0_raw.U(extPhaseWidth.W)
  })

  val cosTable = VecInit(Seq.tabulate(numSegments) { i =>
    val x0_raw = (i.toLong << B) + (1L << (B - 1))
    val rad = x0_raw.toDouble / (1L << extPhaseWidth).toDouble 
    Math.round(Math.cos(rad) * romMaxAmp).toLong.S(romAmpWidth.W)
  })

  val sinTable = VecInit(Seq.tabulate(numSegments) { i =>
    val x0_raw = (i.toLong << B) + (1L << (B - 1))
    val rad = x0_raw.toDouble / (1L << extPhaseWidth).toDouble 
    Math.round(Math.sin(rad) * romMaxAmp).toLong.S(romAmpWidth.W)
  })

  val segmentAddr = actualPhaseReg(extPhaseWidth - 1, B) 

  val x0_reg     = RegNext(evalPointTable(segmentAddr))
  val cos_x0_reg = RegNext(cosTable(segmentAddr))
  val sin_x0_reg = RegNext(sinTable(segmentAddr))

  val x_delayed = RegNext(actualPhaseReg)

  val sinSignReg2 = RegNext(sinSignReg1)
  val cosSignReg2 = RegNext(cosSignReg1)
  val isCosEqReg2 = RegNext(isCosEqReg1)

  val dx_full = x_delayed.zext - x0_reg.zext  // dx = x - x0  

  val dx = RegNext(dx_full(extPhaseWidth - 1, 0).asSInt)  

  val cos_x0_s3 = RegNext(cos_x0_reg)
  val sin_x0_s3 = RegNext(sin_x0_reg)

  val sinSignReg3 = RegNext(sinSignReg2)
  val cosSignReg3 = RegNext(cosSignReg2)
  val isCosEqReg3 = RegNext(isCosEqReg2)

  
  val m_cos_s4 = RegNext(dx * cos_x0_s3)    // cos(x0)*dx
  val m_sin_s4 = RegNext(dx * sin_x0_s3)    // sin(x0)*dx
  
  val dx_sq_s4 = RegNext((dx * dx) >> 1)    // (x-x0)²/2!

  val cos_x0_s4 = RegNext(cos_x0_s3)
  val sin_x0_s4 = RegNext(sin_x0_s3)

  val sinSignReg4 = RegNext(sinSignReg3)
  val cosSignReg4 = RegNext(cosSignReg3)
  val isCosEqReg4 = RegNext(isCosEqReg3)

  val m2_sin_s5 = RegNext(dx_sq_s4 * sin_x0_s4)   // sin(x0) * (x-x0)²/2!
  val m2_cos_s5 = RegNext(dx_sq_s4 * cos_x0_s4)   // cos(x0) * (x-x0)²/2!

  val m_cos_s5 = RegNext(m_cos_s4)
  val m_sin_s5 = RegNext(m_sin_s4)
  val cos_x0_s5 = RegNext(cos_x0_s4)
  val sin_x0_s5 = RegNext(sin_x0_s4)

  val sinSignReg5 = RegNext(sinSignReg4)
  val cosSignReg5 = RegNext(cosSignReg4)
  val isCosEqReg5 = RegNext(isCosEqReg4)

  // Le terme quadratique m2 est intrinsèquement décalé de 2*extPhaseWidth (car dx * dx)
  // On aligne donc la composante ROM et la composante linéaire sur cette même échelle.
  val sin_rom_aligned = (sin_x0_s5 << (2 * extPhaseWidth)).asSInt   
  val cos_rom_aligned = (cos_x0_s5 << (2 * extPhaseWidth)).asSInt    
 
  val m_cos_aligned = (m_cos_s5 << extPhaseWidth).asSInt       
  val m_sin_aligned = (m_sin_s5 << extPhaseWidth).asSInt

  // Utilisation des opérateurs extensifs (+&, -&) pour éviter le débordement de signe
  val sin_taylor = RegNext(sin_rom_aligned +& m_cos_aligned -& m2_sin_s5)   // sin(x) = sin(x0) + dx*cos(x0) - (dx²/2)*sin(x0)
  val cos_taylor = RegNext(cos_rom_aligned -& m_sin_aligned -& m2_cos_s5)   // cos(x) = cos(x0) - dx*sin(x0) - (dx²/2)*cos(x0)

  val sinSignReg6 = RegNext(sinSignReg5)
  val cosSignReg6 = RegNext(cosSignReg5)
  val isCosEqReg6 = RegNext(isCosEqReg5)

  val shift_out = (2 * extPhaseWidth) + romAmpWidth - ampWidth   
  
  val sin_trunc = sin_taylor >> shift_out    //  On remet sur 12 bits...
  val cos_trunc = cos_taylor >> shift_out

  val max_cmp = ((1 << (ampWidth - 1)) - 1).S
  val min_cmp = (-(1 << (ampWidth - 1))).S

  val max_out = ((1 << (ampWidth - 1)) - 1).S(ampWidth.W)
  val min_out = (-(1 << (ampWidth - 1))).S(ampWidth.W)

  val sin_sat = Mux(sin_trunc > max_cmp, max_out, 
                  Mux(sin_trunc < min_cmp, min_out, sin_trunc(ampWidth - 1, 0).asSInt))
                  
  val cos_sat = Mux(cos_trunc > max_cmp, max_out, 
                  Mux(cos_trunc < min_cmp, min_out, cos_trunc(ampWidth - 1, 0).asSInt))

  val preSignCos = Mux(isCosEqReg6, sin_sat, cos_sat)
  val preSignSin = Mux(isCosEqReg6, cos_sat, sin_sat)

  io.cosOut := RegNext(Mux(cosSignReg6, -preSignCos, preSignCos))
  io.sinOut := RegNext(Mux(sinSignReg6, -preSignSin, preSignSin))
}

object GenerateTaylor3PAC extends App {
  // Config. recommandée : accumWidth = 21, phaseOutWidth = 18
  val myConfig = DdsConfigs.activeConfig

  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new Taylor3PAC(myConfig),
    Array("--target-dir", "sortie_verilog")
  )
}