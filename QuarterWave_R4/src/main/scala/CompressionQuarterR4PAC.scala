import chisel3._
import chisel3.util._

class CompressionQuarterR4PAC(val config: DdsConfig) extends Module {
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

  val addrWidth  = phaseWidth - 2  // On retire les 2 bits de contrôle
  
  val rawAddress = io.phaseIn(addrWidth - 1, 0)
  val actualAddressReg = RegNext(
    Mux(mirrorBit, ((1 << addrWidth) - 1).U(addrWidth.W) - rawAddress, rawAddress)
  )
  
  val sinSignReg1 = RegNext(sinSign)
  val cosSignReg1 = RegNext(cosSign)

  val partitions = config.romPartitions
  require(partitions.length == 4, "L'architecture R4 necessite exactement 4 tailles de partition dans DdsConfig.")
  require(partitions.sum == addrWidth, s"Erreur: La somme des partitions (${partitions.sum}) doit valoir addrWidth ($addrWidth).")

  // Affectation des largeurs depuis la configuration
  val W1 = partitions(0)
  val W2 = partitions(1)
  val W3 = partitions(2)
  val W4 = partitions(3)


  // On calcule les offsets pour couper correctement le bus d'adresse. Par exemple sh4 est le LSB, on prends donc les derniers bits, pas besoin d'offset
  val sh4 = 0              // LSB     
  val sh3 = W4
  val sh2 = W4 + W3
  val sh1 = W4 + W3 + W2   // MSB

  val tableMax = (1 << addrWidth).toDouble

  val guardBits = 0                                         // On rajoute 3 bits fractionnaires supplémentaires pour garder de la précision dans les calcules internes. 
  val romAmpWidth = ampWidth + guardBits                    // 12 + 3 = 15 bits > 12 bits donc on aura plus de précision. On tronquera le résultats final pour avoir les 12 bits. 
  val romMaxAmp = ((1 << (romAmpWidth - 1)) - 1).toDouble 

  def makeTable(depthBits: Int, shift: Int, addHalfLsb: Boolean) = { 
    val depth = 1 << depthBits
    val cosT = VecInit(Seq.tabulate(depth) { i =>
      val offset = if (addHalfLsb) 0.5 else 0.0
      val scaledVal = (i.toDouble + offset) * (1 << shift).toDouble      // i + 0,5 * 2**sh
      val rad = (scaledVal / tableMax) * (Math.PI / 2.0)                 // [(i+0,5)*2**sh]/2**addrWidth
      Math.round(Math.cos(rad) * romMaxAmp).toInt.S(romAmpWidth.W)
    })

    val sinT = VecInit(Seq.tabulate(depth) { i =>
      val offset = if (addHalfLsb) 0.5 else 0.0
      val scaledVal = (i.toDouble + offset) * (1 << shift).toDouble
      val rad = (scaledVal / tableMax) * (Math.PI / 2.0)
      Math.round(Math.sin(rad) * romMaxAmp).toInt.S(romAmpWidth.W)
    })
    (cosT, sinT)
  }


  // Générations des ROMs
  val (cosTable1, sinTable1) = makeTable(W1, sh1, false)                
  val (cosTable2, sinTable2) = makeTable(W2, sh2, false)
  val (cosTable3, sinTable3) = makeTable(W3, sh3, false)
  val (cosTable4, sinTable4) = makeTable(W4, sh4, true)

  val addr1 = actualAddressReg(addrWidth - 1, sh1)   // [13:10]
  val addr2 = actualAddressReg(sh1 - 1, sh2)         // [9:6]
  val addr3 = actualAddressReg(sh2 - 1, sh3)         // [5:3]
  val addr4 = actualAddressReg(sh3 - 1, 0)           // [2:0]

  val c1 = RegNext(cosTable1(addr1))  // cos(theta_1) 
  val s1 = RegNext(sinTable1(addr1))  // sin(theta_1) 
  val c2 = RegNext(cosTable2(addr2))  // cos(theta_2) 
  val s2 = RegNext(sinTable2(addr2))  // sin(theta_2) 
  val c3 = RegNext(cosTable3(addr3))  // cos(theta_3)
  val s3 = RegNext(sinTable3(addr3))  // sin(theta_3) 
  val c4 = RegNext(cosTable4(addr4))  // cos(theta_4) 
  val s4 = RegNext(sinTable4(addr4))  // sin(theta_4) 

  val sinSignReg2 = RegNext(sinSignReg1)
  val cosSignReg2 = RegNext(cosSignReg1)


  // Alpha = theta_1 + theta_2  et  Beta = theta_3 + theta_4
  // shift1 : Nombre de bits fractionnaires a eliminer apres produit (garde une precision romAmpWidth)
  val shift1 = romAmpWidth - 1   // car c1*c2 est de taille 15+15 Bits, on élimine donc les 14 fractionnaires de poids faibles    (1 bit de signe et 14 bits fractionnaires)
  // round1 : Constante d'arrondi convergent (+0.5 LSB vis-a-vis du decalage shift1)
  val round1 = (1 << (shift1 - 1)).S((romAmpWidth * 2).W)

  // Multiplications pour l'angle Alpha (theta_1 + theta_2)
  val m_c1c2 = RegNext(((c1 * c2) + round1) >> shift1) // cos(theta_1) * cos(theta_2) 
  val m_s1s2 = RegNext(((s1 * s2) + round1) >> shift1) // sin(theta_1) * sin(theta_2)
  val m_s1c2 = RegNext(((s1 * c2) + round1) >> shift1) // sin(theta_1) * cos(theta_2)
  val m_c1s2 = RegNext(((c1 * s2) + round1) >> shift1) // cos(theta_1) * sin(theta_2)

  // Multiplications pour l'angle Beta (theta_3 + theta_4)
  val m_c3c4 = RegNext(((c3 * c4) + round1) >> shift1) // cos(theta_3) * cos(theta_4)
  val m_s3s4 = RegNext(((s3 * s4) + round1) >> shift1) // sin(theta_3) * sin(theta_4)
  val m_s3c4 = RegNext(((s3 * c4) + round1) >> shift1) // sin(theta_3) * cos(theta_4)
  val m_c3s4 = RegNext(((c3 * s4) + round1) >> shift1) // cos(theta_3) * sin(theta_4)

  val sinSignReg3 = RegNext(sinSignReg2)
  val cosSignReg3 = RegNext(cosSignReg2)

  // Dynamic interne portee a interWidth (romAmpWidth + 1) pour eviter tout debordement d'addition.
  val interWidth = romAmpWidth + 1  // +1 Bits pour les additions afin d'éviter les overflows
  
  val c12 = RegNext((m_c1c2 - m_s1s2)(interWidth - 1, 0).asSInt)    // cos(Alpha) = cos(theta_1)cos(theta_2) - sin(theta_1)sin(theta_2)
  val s12 = RegNext((m_s1c2 + m_c1s2)(interWidth - 1, 0).asSInt)    // sin(Alpha) = sin(theta_1)cos(theta_2) + cos(theta_1)sin(theta_2)
  val c34 = RegNext((m_c3c4 - m_s3s4)(interWidth - 1, 0).asSInt)    // cos(Beta) = cos(theta_3)cos(theta_4) - sin(theta_3)sin(theta_4)
  val s34 = RegNext((m_s3c4 + m_c3s4)(interWidth - 1, 0).asSInt)    // sin(Beta) = sin(theta_3)cos(theta_4) + cos(theta_3)sin(theta_4)

  val sinSignReg4 = RegNext(sinSignReg3)
  val cosSignReg4 = RegNext(cosSignReg3)

  // Alpha * Beta
  val shift2 = 2 * (romAmpWidth - 1) - (ampWidth - 1)       // décalage final pour normaliser : c12*c14 = 2 * (romAmpWidth - 1) bits   et  ampWidth - 1 bits représente la taille de la sortie
  val round2 = (1 << (shift2 - 1)).S((interWidth * 2).W)    // Arrondi convergent de fin de chaine (+0,5)

  val m_c12c34 = RegNext(((c12 * c34) + round2) >> shift2) // cos(Alpha) * cos(Beta)
  val m_s12s34 = RegNext(((s12 * s34) + round2) >> shift2) // sin(Alpha) * sin(Beta)
  val m_s12c34 = RegNext(((s12 * c34) + round2) >> shift2) // sin(Alpha) * cos(Beta)
  val m_c12s34 = RegNext(((c12 * s34) + round2) >> shift2) // cos(Alpha) * sin(Beta)

  val sinSignReg5 = RegNext(sinSignReg4)
  val cosSignReg5 = RegNext(cosSignReg4)

  val sumCos = m_c12c34 - m_s12s34 // cos(theta) = cos(Alpha)cos(Beta) - sin(Alpha)sin(Beta)
  val sumSin = m_s12c34 + m_c12s34 // sin(theta) = sin(Alpha)cos(Beta) + cos(Alpha)sin(Beta)

  
  // Pour 12 bits, maxVal = +2047 et minVal = -2048
  val maxVal = ((1 << (ampWidth - 1)) - 1).S
  val minVal = (-(1 << (ampWidth - 1))).S

  // Si le calcul provoque un debordement du a l'arrondi (ex: +2048), on bloque la valeur a la borne extreme (maxVal) pour eviter le basculement destructeur vers le negatif (-2048).
  val cosRaw = Mux(sumCos > maxVal, maxVal, Mux(sumCos < minVal, minVal, sumCos(ampWidth - 1, 0).asSInt))
  val sinRaw = Mux(sumSin > maxVal, maxVal, Mux(sumSin < minVal, minVal, sumSin(ampWidth - 1, 0).asSInt))

  io.cosOut := RegNext(Mux(cosSignReg5, -cosRaw, cosRaw))
  io.sinOut := RegNext(Mux(sinSignReg5, -sinRaw, sinRaw))
}

object GenerateCompressionQuarterR4PAC extends App {
  val myConfig = DdsConfigs.activeConfig

  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new CompressionQuarterR4PAC(myConfig),
    Array("--target-dir", "sortie_verilog")
  )
}