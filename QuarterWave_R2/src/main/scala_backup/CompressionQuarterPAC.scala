
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
  val cosSign = signBit ^ mirrorBit   //obligé parceque l'on sort également un cos en sortie

  val addrWidth  = phaseWidth - 2
  val rawAddress = io.phaseIn(addrWidth - 1, 0)

  // Inversion de l'adresse si Q2 ou Q4 =
  val actualAddressReg = RegNext(
    Mux(mirrorBit, ((1 << addrWidth) - 1).U(addrWidth.W) - rawAddress, rawAddress)
  )
  
  // On commence à faire glisser les bits de signe dans le pipeline
  val sinSignReg1 = RegNext(sinSign)
  val cosSignReg1 = RegNext(cosSign)

// Paramètres importants...
  val B = addrWidth / 2
  val A = addrWidth - B

  val romDepthMSB = 1 << A
  val romDepthLSB   = 1 << B

  /*val val_matlab  = 0.99609375
  val MSBMaxAmp = ((1 << (ampWidth - 1)) - 1).toDouble * val_matlab
  val LSBMaxAmp   = ((1 << (ampWidth - 1)) - 1).toDouble * val_matlab */

  val MSBMaxAmp = ((1 << (ampWidth - 1)) - 1).toDouble
  val LSBMaxAmp   = ((1 << (ampWidth - 1)) - 1).toDouble 




  // On trace sur pi/2 (Quart de cercle). Pas d'offset 0.5 ici !
  val cosMSBTable = VecInit(Seq.tabulate(romDepthMSB) { i =>                   //  cos(θ1)
    val rad = (i.toDouble  / (1 << A).toDouble) * (Math.PI / 2.0)
    Math.round(Math.cos(rad) * MSBMaxAmp).toInt.S(ampWidth.W)
  })
  val sinMSBTable = VecInit(Seq.tabulate(romDepthMSB) { i =>                   //  sin(θ1)
    val rad = (i.toDouble  / (1 << A).toDouble) * (Math.PI / 2.0)
    Math.round(Math.sin(rad) * MSBMaxAmp).toInt.S(ampWidth.W)
  })

 
  val cosLSBTable = VecInit(Seq.tabulate(romDepthLSB) { i =>
    val rad = ((i.toDouble + 0.5) / (1 << addrWidth).toDouble) * (Math.PI / 2.0)   //  cos(θ2)
    Math.round(Math.cos(rad) * LSBMaxAmp).toInt.S(ampWidth.W)
  })
  val sinLSBTable = VecInit(Seq.tabulate(romDepthLSB) { i =>
    val rad = ((i.toDouble + 0.5) / (1 << addrWidth).toDouble) * (Math.PI / 2.0)   //  sin(θ2)
    Math.round(Math.sin(rad) * LSBMaxAmp).toInt.S(ampWidth.W)
  })

  val MSBAddr = actualAddressReg(addrWidth - 1, B)
  val LSBAddr   = actualAddressReg(B - 1, 0)

  val cosMSBReg = RegNext(cosMSBTable(MSBAddr))  
  val sinMSBReg = RegNext(sinMSBTable(MSBAddr))
  val cosLSBReg   = RegNext(cosLSBTable(LSBAddr))
  val sinLSBReg   = RegNext(sinLSBTable(LSBAddr))

  val sinSignReg2 = RegNext(sinSignReg1)
  val cosSignReg2 = RegNext(cosSignReg1)


                                                                         // cos(θ1​+θ2​)=cos(θ1​)cos(θ2​)−sin(θ1​)sin(θ2​)     sin(θ1​+θ2​)=sin(θ1​)cos(θ2​)+cos(θ1​)sin(θ2​)
  /*val multCosCos = RegNext((cosMSBReg * cosLSBReg) >> (ampWidth - 1))    // cos(θ1​)cos(θ2​)      >> (ampWidth - 1)) permet de diviser le signal par 2¹¹ et ramener les signal sur 12 bits
  val multSinSin = RegNext((sinMSBReg * sinLSBReg) >> (ampWidth - 1))    // sin(θ1​)sin(θ2​)
  val multSinCos = RegNext((sinMSBReg * cosLSBReg) >> (ampWidth - 1))    // sin(θ1​)cos(θ2​)
  val multCosSin = RegNext((cosMSBReg * sinLSBReg) >> (ampWidth - 1))    // cos(θ1​)sin(θ2​)  */

  // la version ci-dessus réalise une tronc pure avec >> (ampWidth - 1)), on perds les bit de poids faible. 
  // dans la version c-dessous, on réalise donc plutot à un arrondi convergent, ce qui permet de garder une meilleur précision. 

  // ==========================================
  // ETAGE 3 : MULTIPLICATEURS AVEC ARRONDI SINT DIRECT
  // ==========================================
  // En SInt, pour faire +0.5 avant le décalage de (ampWidth - 1) :
  val roundVal = (1 << (ampWidth - 2)).S(ampWidth.W)

  // On fait l'opération entièrement en SInt
  val multCosCos = RegNext(((cosMSBReg * cosLSBReg) + roundVal) >> (ampWidth - 1))
  val multSinSin = RegNext(((sinMSBReg * sinLSBReg) + roundVal) >> (ampWidth - 1))
  val multSinCos = RegNext(((sinMSBReg * cosLSBReg) + roundVal) >> (ampWidth - 1))
  val multCosSin = RegNext(((cosMSBReg * sinLSBReg) + roundVal) >> (ampWidth - 1))

  val sinSignReg3 = RegNext(sinSignReg2)
  val cosSignReg3 = RegNext(cosSignReg2)

  // ==========================================
  // ETAGE 4 & 5 : RECOMBINAISON ET EMPECHEMENT D'OVERFLOW
  // ==========================================
  // Le calcul brut peut faire +1 bit, on le force à rester sur la bonne taille
  val cosRaw = (multCosCos - multSinSin)(ampWidth - 1, 0).asSInt
  val sinRaw = (multSinCos + multCosSin)(ampWidth - 1, 0).asSInt

  io.cosOut := RegNext(Mux(cosSignReg3, -cosRaw, cosRaw))
  io.sinOut := RegNext(Mux(sinSignReg3, -sinRaw, sinRaw))
}

// Objet principal pour la génération Verilog
object GenerateCompressionQuarterPAC extends App {
  val myConfig = DdsConfigs.activeConfig

  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new CompressionQuarterPAC(myConfig),
    Array("--target-dir", "sortie_verilog")
  )
}