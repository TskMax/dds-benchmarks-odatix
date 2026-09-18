import chisel3._
import chisel3.util._

class CompressionQuarterPAC(val config: DdsConfig) extends Module {
  val io = IO(new Bundle {
    val phaseIn = Input(UInt(config.phaseOutWidth.W))
    val cosOut  = Output(SInt(config.ampWidth.W))
    val sinOut  = Output(SInt(config.ampWidth.W))
  })

  val phaseWidth = config.phaseOutWidth
  val ampWidth   = config.ampWidth

  val signBit   = io.phaseIn(phaseWidth - 1).asBool // MSB
  val mirrorBit = io.phaseIn(phaseWidth - 2).asBool // MSB-1

  val sinSign = signBit
  val cosSign = signBit ^ mirrorBit   

  val addrWidth  = phaseWidth - 2
  
  require(config.lsbWidth > 0 && config.lsbWidth < addrWidth, 
    s"lsbWidth (${config.lsbWidth}) doit etre superieur a 0 et inferieur a addrWidth ($addrWidth)")

  val rawAddress = io.phaseIn(addrWidth - 1, 0)

  // Inversion de l'adresse si Q2 ou Q4 =
  val actualAddressReg = RegNext(
    Mux(mirrorBit, ((1 << addrWidth) - 1).U(addrWidth.W) - rawAddress, rawAddress)
  )
  
  val sinSignReg1 = RegNext(sinSign)
  val cosSignReg1 = RegNext(cosSign)

  val B = config.lsbWidth
  val A = addrWidth - B

  val romDepthMSB = 1 << A
  val romDepthLSB = 1 << B

  val MSBMaxAmp = ((1 << (ampWidth - 1)) - 1).toDouble
  val LSBMaxAmp = ((1 << (ampWidth - 1)) - 1).toDouble 

 
  
  // --- Tables MSB : Phase Grossière theta_1 ---
  val cosMSBTable = VecInit(Seq.tabulate(romDepthMSB) { i =>                  // Table : cos(theta_1)
    val rad = (i.toDouble  / (1 << A).toDouble) * (Math.PI / 2.0)
    Math.round(Math.cos(rad) * MSBMaxAmp).toInt.S(ampWidth.W)
  })
  val sinMSBTable = VecInit(Seq.tabulate(romDepthMSB) { i =>                  // Table : sin(theta_1)
    val rad = (i.toDouble  / (1 << A).toDouble) * (Math.PI / 2.0)
    Math.round(Math.sin(rad) * MSBMaxAmp).toInt.S(ampWidth.W)
  })

  // --- Tables LSB : Phase Fine theta_2 ---
  val cosLSBTable = VecInit(Seq.tabulate(romDepthLSB) { i =>                  // Table : cos(theta_2)
    val rad = ((i.toDouble + 0.5) / (1 << addrWidth).toDouble) * (Math.PI / 2.0)   
    Math.round(Math.cos(rad) * LSBMaxAmp).toInt.S(ampWidth.W)
  })
  val sinLSBTable = VecInit(Seq.tabulate(romDepthLSB) { i =>                  // Table : sin(theta_2)
    val rad = ((i.toDouble + 0.5) / (1 << addrWidth).toDouble) * (Math.PI / 2.0)   
    Math.round(Math.sin(rad) * LSBMaxAmp).toInt.S(ampWidth.W)
  })

  val MSBAddr = actualAddressReg(addrWidth - 1, B)
  val LSBAddr = actualAddressReg(B - 1, 0)

  // Étage de lecture des registres de ROM
  val cosMSBReg = RegNext(cosMSBTable(MSBAddr))  // Contient cos(theta_1)
  val sinMSBReg = RegNext(sinMSBTable(MSBAddr))  // Contient sin(theta_1)
  val cosLSBReg = RegNext(cosLSBTable(LSBAddr))  // Contient cos(theta_2)
  val sinLSBReg = RegNext(sinLSBTable(LSBAddr))  // Contient sin(theta_2)

  val sinSignReg2 = RegNext(sinSignReg1)
  val cosSignReg2 = RegNext(cosSignReg1)

  // roundVal ajoute mathématiquement +0.5 au niveau de la coupure finale
  val roundVal = (1 << (ampWidth - 2)).S(ampWidth.W)

  val multCosCos = RegNext(((cosMSBReg * cosLSBReg) + roundVal) >> (ampWidth - 1)) // Calcule : cos(theta_1) * cos(theta_2)
  val multSinSin = RegNext(((sinMSBReg * sinLSBReg) + roundVal) >> (ampWidth - 1)) // Calcule : sin(theta_1) * sin(theta_2)
  val multSinCos = RegNext(((sinMSBReg * cosLSBReg) + roundVal) >> (ampWidth - 1)) // Calcule : sin(theta_1) * cos(theta_2)
  val multCosSin = RegNext(((cosMSBReg * sinLSBReg) + roundVal) >> (ampWidth - 1)) // Calcule : cos(theta_1) * sin(theta_2)

  val sinSignReg3 = RegNext(sinSignReg2)
  val cosSignReg3 = RegNext(cosSignReg2)


  val cosRaw = (multCosCos - multSinSin)(ampWidth - 1, 0).asSInt
  
  // sin(theta_1 + theta_2) = sin(theta_1)*cos(theta_2) + cos(theta_1)*sin(theta_2)
  val sinRaw = (multSinCos + multCosSin)(ampWidth - 1, 0).asSInt

  io.cosOut := RegNext(Mux(cosSignReg3, -cosRaw, cosRaw))
  io.sinOut := RegNext(Mux(sinSignReg3, -sinRaw, sinRaw))
}

object GenerateCompressionQuarterPAC extends App {
  val myConfig = DdsConfigs.activeConfig

  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new CompressionQuarterPAC(myConfig),
    Array("--target-dir", "sortie_verilog")
  )
}