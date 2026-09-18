import chisel3._
import chisel3.util._

class CompressionEightPAC(val config: DdsConfig) extends Module {
  val io = IO(new Bundle {
    val phaseIn = Input(UInt(config.phaseOutWidth.W))
    val cosOut  = Output(SInt(config.ampWidth.W))
    val sinOut  = Output(SInt(config.ampWidth.W))
  })

  val phaseWidth = config.phaseOutWidth
  val ampWidth   = config.ampWidth

  val signBit = io.phaseIn(phaseWidth - 1).asBool // MSB : Demi-cercle
  val qwBit   = io.phaseIn(phaseWidth - 2).asBool // MSB-1 : Quadrant
  val ewBit   = io.phaseIn(phaseWidth - 3).asBool // MSB-2 : Octant (Eighth Wave)

  val sinSign = signBit
  val cosSign = signBit ^ qwBit
  val isCosEq = qwBit ^ ewBit 

  val addrWidth  = phaseWidth - 3  // 16 - 3 = 13 bits
  
  require(config.lsbWidth > 0 && config.lsbWidth < addrWidth, 
    s"lsbWidth (${config.lsbWidth}) doit etre superieur a 0 et inferieur a addrWidth ($addrWidth)")

  val rawAddress = io.phaseIn(addrWidth - 1, 0)
  
  val actualAddressReg = RegInit(0.U(addrWidth.W))
  
  // Si ewBit = 1 (Octant impair) -> theta_local = pi/4 - theta_initial
  // En binaire : actualAddress = (2^addrWidth - 1) - rawAddress
  when(ewBit) {  
    actualAddressReg := ((1 << addrWidth) - 1).U(addrWidth.W) - rawAddress
  } .otherwise {
    actualAddressReg := rawAddress
  }

  val sinSignReg1 = RegNext(sinSign)
  val cosSignReg1 = RegNext(cosSign)
  val isCosEqReg1 = RegNext(isCosEq)

  val B = config.lsbWidth
  val A = addrWidth - B

  val romDepthMSB = 1 << A
  val romDepthLSB = 1 << B

  val MSBMaxAmp = ((1 << (ampWidth - 1)) - 1).toDouble
  val LSBMaxAmp = ((1 << (ampWidth - 1)) - 1).toDouble 

  
  // MSB (theta_1) : cos/sin ( (pi/4) * (i / 2^A) )
  val cosMSBTable = VecInit(Seq.tabulate(romDepthMSB) { i =>                  
    val rad = (i.toDouble  / (1 << A).toDouble) * (Math.PI / 4.0) 
    Math.round(Math.cos(rad) * MSBMaxAmp).toInt.S(ampWidth.W)
  })
  val sinMSBTable = VecInit(Seq.tabulate(romDepthMSB) { i =>                  
    val rad = (i.toDouble  / (1 << A).toDouble) * (Math.PI / 4.0)
    Math.round(Math.sin(rad) * MSBMaxAmp).toInt.S(ampWidth.W)
  })

  // LSB (theta_2) : cos/sin ( (pi/4) * ((i + 0.5) / 2^addrWidth) ) 
  val cosLSBTable = VecInit(Seq.tabulate(romDepthLSB) { i =>                  
    val rad = ((i.toDouble + 0.5) / (1 << addrWidth).toDouble) * (Math.PI / 4.0)   
    Math.round(Math.cos(rad) * LSBMaxAmp).toInt.S(ampWidth.W)
  })
  val sinLSBTable = VecInit(Seq.tabulate(romDepthLSB) { i =>                  
    val rad = ((i.toDouble + 0.5) / (1 << addrWidth).toDouble) * (Math.PI / 4.0)   
    Math.round(Math.sin(rad) * LSBMaxAmp).toInt.S(ampWidth.W)
  })

  val MSBAddr = actualAddressReg(addrWidth - 1, B)
  val LSBAddr = actualAddressReg(B - 1, 0)

  val cosMSBReg = RegNext(cosMSBTable(MSBAddr))
  val sinMSBReg = RegNext(sinMSBTable(MSBAddr))
  val cosLSBReg = RegNext(cosLSBTable(LSBAddr))
  val sinLSBReg = RegNext(sinLSBTable(LSBAddr))

  val sinSignReg2 = RegNext(sinSignReg1)
  val cosSignReg2 = RegNext(cosSignReg1)
  val isCosEqReg2 = RegNext(isCosEqReg1)

  val roundVal = (1 << (ampWidth - 2)).S(ampWidth.W) // + 0,5 pour l'arrondi convergent 

 
  val multCosCos = RegNext(((cosMSBReg * cosLSBReg) + roundVal) >> (ampWidth - 1)) // cos(theta_1) * cos(theta_2)
  val multSinSin = RegNext(((sinMSBReg * sinLSBReg) + roundVal) >> (ampWidth - 1)) // sin(theta_1) * sin(theta_2)
  val multSinCos = RegNext(((sinMSBReg * cosLSBReg) + roundVal) >> (ampWidth - 1)) // sin(theta_1) * cos(theta_2)
  val multCosSin = RegNext(((cosMSBReg * sinLSBReg) + roundVal) >> (ampWidth - 1)) // cos(theta_1) * sin(theta_2)

  val sinSignReg3 = RegNext(sinSignReg2)
  val cosSignReg3 = RegNext(cosSignReg2)
  val isCosEqReg3 = RegNext(isCosEqReg2)

  val cosRaw = (multCosCos - multSinSin)(ampWidth - 1, 0).asSInt    //  cos(theta_1 + theta_2) = cos(theta_1)cos(theta_2) - sin(theta_1)sin(theta_2)
  val sinRaw = (multSinCos + multCosSin)(ampWidth - 1, 0).asSInt    //  sinRaw = sin(theta_1 + theta_2) = sin(theta_1)cos(theta_2) + cos(theta_1)sin(theta_2)

  // Si isCosEq = 1, l'angle cible se situe dans un octant complémentaire (ex: pi/4 à pi/2)
  // On applique les identités de co-fonctions trigonométriques :
  // preSignCos = cos(pi/2 - x) = sin(x) -> Reçoit sinRaw
  // preSignSin = sin(pi/2 - x) = cos(x) -> Reçoit cosRaw
  val preSignCos = Mux(isCosEqReg3, sinRaw, cosRaw)
  val preSignSin = Mux(isCosEqReg3, cosRaw, sinRaw)

  io.cosOut := RegNext(Mux(cosSignReg3, -preSignCos, preSignCos))
  io.sinOut := RegNext(Mux(sinSignReg3, -preSignSin, preSignSin))
}

object GenerateCompressionEightPAC extends App {
  val myConfig = DdsConfigs.activeConfig

  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new CompressionEightPAC(myConfig),
    Array("--target-dir", "sortie_verilog")
  )
}