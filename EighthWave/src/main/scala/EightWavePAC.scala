import chisel3._
import chisel3.util._

class EightWavePAC(val config: DdsConfig) extends Module {
  val io = IO(new Bundle {
    val phaseIn = Input(UInt(config.phaseOutWidth.W))
    val ampOut  = Output(SInt(config.ampWidth.W)) 
  })

  val phaseWidth = config.phaseOutWidth
  val ampWidth   = config.ampWidth


 // 1. Extraction des bits de contrôle de phase (Convertis en Booléens)
  val signBit   = io.phaseIn(phaseWidth - 1).asBool
  val qwBit     = io.phaseIn(phaseWidth - 2).asBool
  val ewBit     = io.phaseIn(phaseWidth - 3).asBool // Eighth Wave (0 a pi/4 vs pi/4 a pi/2)

 //si on est dans un octant impair, on doit lire a l'envers et utiliser l'equation du Cosinus
  val isCosEq = qwBit ^ ewBit // XOR entre les deux bits de controle 


  // 2. Logique miroir avec Registre (Pipeline)
  val addrWidth  = phaseWidth - 3  // Il nous reste 11 bits d'adressage restant   // def de la largeur de l'adresse
  val rawAddress = io.phaseIn(addrWidth - 1, 0)                                   // def de l'adrese en soi
  
  
  val actualAddressReg = RegInit(0.U(addrWidth.W))
  val isCosEqReg       = RegNext(isCosEq)
  val signBitReg       = RegNext(signBit)
  
  // Assignation propre dans le registre
  when(ewBit) {  
    actualAddressReg := ((1 << addrWidth) - 1).U(addrWidth.W) - rawAddress  //Si partie cos, on lit a l'envers 
  } .otherwise {
    actualAddressReg := rawAddress
  }

  // 4. Génération de la table ROM en Scala (Calculée à la compilation)
  val romDepth = 1 << addrWidth              
  val maxAmp   = (1 << (ampWidth - 1)) - 1  


  //Table SINUS
    val sineTable = VecInit(Seq.tabulate(romDepth) { i =>   

    val rad = ((i.toDouble + 0.5) / romDepth.toDouble) * (Math.PI / 4.0)
    val amp_v = Math.round(Math.sin(rad) * maxAmp).toInt
    amp_v.S(ampWidth.W) 
  }) 
  
  //Table COSINUS
  val cosTable = VecInit(Seq.tabulate(romDepth) { i =>

    val rad = ((i.toDouble + 0.5) / romDepth.toDouble) * (Math.PI / 4.0)
    val amp_v = Math.round(Math.cos(rad) * maxAmp).toInt
    amp_v.S(ampWidth.W)
  })

  val sinData = sineTable(actualAddressReg)
  val cosData = cosTable(actualAddressReg)

  val romData = Mux(isCosEqReg, cosData, sinData)

  io.ampOut := RegNext(Mux(signBitReg, -romData, romData))
  }

// Objet principal pour générer le Verilog
object GenerateEightWavePAC extends App {
  // On va chercher la meme configuration
  val myConfig = DdsConfigs.activeConfig

  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    // On utilise les largeurs definies dans le catalogue
    new EightWavePAC(myConfig),
    Array("--target-dir", "sortie_verilog")
  )
}