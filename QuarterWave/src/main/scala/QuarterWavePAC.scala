import chisel3._
import chisel3.util._

class QuarterWavePAC(val config: DdsConfig) extends Module {
  val io = IO(new Bundle {
    val phaseIn = Input(UInt(config.phaseOutWidth.W)) 
    val cosOut  = Output(SInt(config.ampWidth.W))    // Ajout de la sortie Cosinus
    val sinOut  = Output(SInt(config.ampWidth.W))    // Ancien ampOut renommé    
  })

  val phaseWidth = config.phaseOutWidth
  val ampWidth   = config.ampWidth

  // 1. Extraction des 2 MSB (Bits de contrôle)
  val signBit   = io.phaseIn(phaseWidth - 1).asBool // 1er MSB : Signe (0 à pi vs pi à 2pi)
  val mirrorBit = io.phaseIn(phaseWidth - 2).asBool // 2ème MSB : Miroir (0 à pi/2 vs pi/2 à pi)

  // Logique de signe pour le cosinus : négatif dans les quadrants 2 et 3
  // Un simple XOR entre le MSB et le MSB-1 donne exactement cette séquence (0, 1, 1, 0)
  val cosSign = signBit ^ mirrorBit

  // 2. Définition de l'adresse restante
  val addrWidth  = phaseWidth - 2
  val rawAddress = io.phaseIn(addrWidth - 1, 0)

  // 3. Logique Miroir avec Registre (Pipeline)
  val actualAddressReg = RegInit(0.U(addrWidth.W))
  val signBitReg       = RegNext(signBit)
  val cosSignReg       = RegNext(cosSign) // Nouveau registre de pipeline pour le signe du cos

  // Si on est dans le 2ème ou 4ème quadrant, on lit la table à l'envers
  when(mirrorBit) {
    actualAddressReg := ((1 << addrWidth) - 1).U(addrWidth.W) - rawAddress
  } .otherwise {
    actualAddressReg := rawAddress
  }

  // 4. Dimensions de la table (Quart de cercle)
  val romDepth = 1 << addrWidth               // Divisé par 4 par rapport à FullWave
  val maxAmp   = (1 << (ampWidth - 1)) - 1    

  // 5. Remplissage des ROM en Scala
  val sineTable = VecInit(Seq.tabulate(romDepth) { i =>
    // ATTENTION : Le + 0.5 redevient obligatoire ici pour la symétrie miroir !
    val rad = ((i.toDouble + 0.5) / romDepth.toDouble) * (Math.PI / 2.0)
    val amp_v = Math.round(Math.sin(rad) * maxAmp).toInt
    amp_v.S(ampWidth.W)  
  })

  // Nouvelle table pour le cosinus générée à la compilation
  val cosineTable = VecInit(Seq.tabulate(romDepth) { i =>
    val rad = ((i.toDouble + 0.5) / romDepth.toDouble) * (Math.PI / 2.0)
    val amp_v = Math.round(Math.cos(rad) * maxAmp).toInt
    amp_v.S(ampWidth.W)  
  })

  // 6. Lecture matérielle et application du signe
  val sinData = sineTable(actualAddressReg)
  val cosData = cosineTable(actualAddressReg)

  io.sinOut := RegNext(Mux(signBitReg, -sinData, sinData))
  io.cosOut := RegNext(Mux(cosSignReg, -cosData, cosData))
}

object GenerateQuarterPAC extends App {
  val myConfig = DdsConfigs.activeConfig

  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new QuarterWavePAC(myConfig),
    Array("--target-dir", "sortie_verilog")
  )
}





























/*import chisel3._
import chisel3.util._

class QuarterWavePAC(val config: DdsConfig) extends Module {
  val io = IO(new Bundle {
    val phaseIn = Input(UInt(config.phaseOutWidth.W)) 
    val ampOut  = Output(SInt(config.ampWidth.W))    
  })

  val phaseWidth = config.phaseOutWidth
  val ampWidth   = config.ampWidth

  // 1. Extraction des 2 MSB (Bits de contrôle)
  val signBit   = io.phaseIn(phaseWidth - 1).asBool // 1er MSB : Signe (0 à pi vs pi à 2pi)
  val mirrorBit = io.phaseIn(phaseWidth - 2).asBool // 2ème MSB : Miroir (0 à pi/2 vs pi/2 à pi)

  // 2. Définition de l'adresse restante
  val addrWidth  = phaseWidth - 2
  val rawAddress = io.phaseIn(addrWidth - 1, 0)

  // 3. Logique Miroir avec Registre (Pipeline)
  val actualAddressReg = RegInit(0.U(addrWidth.W))
  val signBitReg       = RegNext(signBit)

  // Si on est dans le 2ème ou 4ème quadrant, on lit la table à l'envers
  when(mirrorBit) {
    actualAddressReg := ((1 << addrWidth) - 1).U(addrWidth.W) - rawAddress
  } .otherwise {
    actualAddressReg := rawAddress
  }

  // 4. Dimensions de la table (Quart de cercle)
  val romDepth = 1 << addrWidth               // Divisé par 4 par rapport à FullWave
  val maxAmp   = (1 << (ampWidth - 1)) - 1    

  // 5. Remplissage de la ROM en Scala
  val sineTable = VecInit(Seq.tabulate(romDepth) { i =>
    // ATTENTION : Le + 0.5 redevient obligatoire ici pour la symétrie miroir !
    val rad = ((i.toDouble + 0.5) / romDepth.toDouble) * (Math.PI / 2.0)
    val amp_v = Math.round(Math.sin(rad) * maxAmp).toInt
    amp_v.S(ampWidth.W)  
  })

  // 6. Lecture matérielle et application du signe
  val romData = sineTable(actualAddressReg)
  io.ampOut := RegNext(Mux(signBitReg, -romData, romData))
}

// Objet principal pour générer le Verilog
object GenerateQuarterPAC extends App {
  val myConfig = DdsConfigs.activeConfig

  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new QuarterWavePAC(myConfig),
    Array("--target-dir", "sortie_verilog")
  )
}
*/

