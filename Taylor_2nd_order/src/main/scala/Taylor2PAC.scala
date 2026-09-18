import chisel3._
import chisel3.util._

class Taylor2PAC(val config: DdsConfig) extends Module {
  val io = IO(new Bundle {
    val control = Input(UInt(3.W))                           
    val phaseIn = Input(UInt(config.phaseOutWidth.W))        
    val cosOut  = Output(SInt(config.ampWidth.W))
    val sinOut  = Output(SInt(config.ampWidth.W))
  })

  val phaseWidth = config.phaseOutWidth
  val ampWidth   = config.ampWidth

  val signBit = io.control(2) 
  val qwBit   = io.control(1) 
  val ewBit   = io.control(0) 

  val sinSign = signBit
  val cosSign = signBit ^ qwBit
  val isCosEq = qwBit ^ ewBit

  val pi_over_4_scaled = Math.round((Math.PI / 4.0) * (1 << phaseWidth)).toInt.U(phaseWidth.W)
  
  val actualPhaseReg = RegNext(
    Mux(ewBit, pi_over_4_scaled - io.phaseIn, io.phaseIn)  // si on lit en inverse... (symmetrie 8th wave)
  )

  val sinSignReg1 = RegNext(sinSign)
  val cosSignReg1 = RegNext(cosSign)
  val isCosEqReg1 = RegNext(isCosEq)


  val segAddrWidth = 5                // Les segments sont codés sur 5 bits
  val B = phaseWidth - segAddrWidth   // Bits d'adressage restant (14 - 5 = 9 bits)
  val numSegments = 1 << segAddrWidth // 32 segments (2⁵)

  val romAmpWidth = 14                // On travaille sur 14 Bits pour respecter la publi
  val romMaxAmp = ((1 << (romAmpWidth - 1)) - 1).toDouble

  
  val evalPointTable = VecInit(Seq.tabulate(numSegments) { i => // la table va de 0 à 31
    val x0_raw = (i << B) + (1 << (B - 1))      // x0_raw​=i×2⁹+2⁸
    x0_raw.U(phaseWidth.W)
  })

  val cosTable = VecInit(Seq.tabulate(numSegments) { i =>
    val x0_raw = (i << B) + (1 << (B - 1))
    val rad = x0_raw.toDouble / (1 << phaseWidth).toDouble // On divise par 2¹⁴ pour normaliser
    Math.round(Math.cos(rad) * romMaxAmp).toInt.S(romAmpWidth.W)
  })

  val sinTable = VecInit(Seq.tabulate(numSegments) { i =>
    val x0_raw = (i << B) + (1 << (B - 1))
    val rad = x0_raw.toDouble / (1 << phaseWidth).toDouble
    Math.round(Math.sin(rad) * romMaxAmp).toInt.S(romAmpWidth.W)
  })

  val segmentAddr = actualPhaseReg(phaseWidth - 1, B) // On prends les 5 MSBs

  val x0_reg     = RegNext(evalPointTable(segmentAddr))
  val cos_x0_reg = RegNext(cosTable(segmentAddr))
  val sin_x0_reg = RegNext(sinTable(segmentAddr))

  val x_delayed = RegNext(actualPhaseReg)

  val sinSignReg2 = RegNext(sinSignReg1)
  val cosSignReg2 = RegNext(cosSignReg1)
  val isCosEqReg2 = RegNext(isCosEqReg1)


  // On effectue la soustraction (qui génère un SInt de 15 bits en Chisel car on doit passer en signé)
  val dx_full = x_delayed.zext - x0_reg.zext    // dx = x - x0

  // On tronque à 14 bits pour respecter scrupuleusement la publi
  val dx = RegNext(dx_full(phaseWidth - 1, 0).asSInt)  // On repasse à 14 bits

  val cos_x0_delayed = RegNext(cos_x0_reg)
  val sin_x0_delayed = RegNext(sin_x0_reg)

  val sinSignReg3 = RegNext(sinSignReg2)
  val cosSignReg3 = RegNext(cosSignReg2)
  val isCosEqReg3 = RegNext(isCosEqReg2)

  
  // Multiplieurs   dx*cos(x0)   et   dx*sin(x0)
  val m_cos = RegNext(dx * cos_x0_delayed)   // sur 28 bits (mais 27 bits en magnitude ??)
  val m_sin = RegNext(dx * sin_x0_delayed)


  val cos_x0_final = RegNext(cos_x0_delayed)
  val sin_x0_final = RegNext(sin_x0_delayed)

  val sinSignReg4 = RegNext(sinSignReg3)
  val cosSignReg4 = RegNext(cosSignReg3)
  val isCosEqReg4 = RegNext(isCosEqReg3)

  val sin_rom_aligned = (sin_x0_final << 14).asSInt 
  val cos_rom_aligned = (cos_x0_final << 14).asSInt 

  val sin_taylor = sin_rom_aligned + m_cos  //  sin(x0) + dx*cos(x0)
  val cos_taylor = cos_rom_aligned - m_sin  //  cos(x0) + dx*sin(x0)

  val shift_out = 16 
  //val round_out = (1 << (shift_out - 1)).S      //décommenter ces lignes si on veut ganger 5 de SFDR
  
  val sin_rounded = sin_taylor //+& round_out   
  val cos_rounded = cos_taylor //+& round_out  

  // Décalage final : on passe d'un bus large à l'amplitude cible de 12 bits
  val sin_trunc = sin_rounded >> shift_out
  val cos_trunc = cos_rounded >> shift_out

  // Assignation directe sans logique de saturation
  val sin_sat = sin_trunc(ampWidth - 1, 0).asSInt
  val cos_sat = cos_trunc(ampWidth - 1, 0).asSInt
 
  val preSignCos = Mux(isCosEqReg4, sin_sat, cos_sat)
  val preSignSin = Mux(isCosEqReg4, cos_sat, sin_sat)

  io.cosOut := RegNext(Mux(cosSignReg4, -preSignCos, preSignCos))
  io.sinOut := RegNext(Mux(sinSignReg4, -preSignSin, preSignSin))
}

object GenerateTaylor2PAC extends App {
  val myConfig = DdsConfigs.activeConfig

  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new Taylor2PAC(myConfig),
    Array("--target-dir", "sortie_verilog")
  )
}