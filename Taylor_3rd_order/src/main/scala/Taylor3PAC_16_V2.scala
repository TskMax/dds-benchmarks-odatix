// La seule modif par rapport à l'archi de la publi sont les opérande +& et -& qui font monter à 2à bits lignes 118 et 119. Cependant la figure ne précise pas la taille du bus à cet endroit donc oklm


import chisel3._
import chisel3.util._

class Taylor3PAC_16_V2(val config: DdsConfig) extends Module {
  val io = IO(new Bundle {
    val control = Input(UInt(3.W))                           
    val phaseIn = Input(UInt(18.W))       
    val cosOut  = Output(SInt(16.W))      
    val sinOut  = Output(SInt(16.W))      
  })

  val segAddrWidth = 3 ; val romAmpWidth  = 18  
  
  val phaseWidth   = 18

  val signBit = io.control(2) 
  val qwBit   = io.control(1) 
  val ewBit   = io.control(0) 

  // =====================================================================
  // STAGE 0 : ADAPTATION AU PHASE ACCUMULATOR
  // =====================================================================
  // Le PA fournit io.phaseIn DEJA multiplie par pi/4 (valeur max = ~205887).
  // La constante pi/4 normalisee sur 18 bits :
  val pi_over_4_rad = Math.round((Math.PI / 4.0) * (1L << phaseWidth)).U(phaseWidth.W)
  
  // Reflexion de l'octant appliquee sur le signal en radians purs
  val pac_input_rad = RegNext(Mux(ewBit, pi_over_4_rad - io.phaseIn, io.phaseIn))

  // Retards d'alignement pour les bits de controle
  val sinSignReg1 = RegNext(signBit)
  val cosSignReg1 = RegNext(signBit ^ qwBit)
  val isCosEqReg1 = RegNext(qwBit ^ ewBit)


  // =====================================================================
  // GENERATION DE LA LUT (TOUT EST CALCULE PAR LE PC)
  // =====================================================================
  val B = phaseWidth - segAddrWidth   // 18 - 2 = 16
  val segmentAddr = pac_input_rad(phaseWidth - 1, B)   // Extraction des bits d adressage de segments 
  val numSegments = 1 << segAddrWidth    // = 4
  val romMaxAmp = ((1L << (romAmpWidth - 1)) - 1).toDouble

  val evalPointTable = VecInit(Seq.tabulate(numSegments) { i =>    // Chaque segment à une largeur de 2^B
    val x0_raw = (i.toLong << B) + (1L << (B - 1))                 // i.toLong << B, premiere adresse du i-eme seg. 
    x0_raw.U(phaseWidth.W)                                         // 1L << (B - 1) : ajoute la valeure 2^(B-1) pour centrer le point d'evaluation dans le segment
  })                                                               // X_0 est donc ainsi centré. 

  // Les tables ROM génèrent les coefficients directement depuis la valeur d'angle
  val cosTable = VecInit(Seq.tabulate(numSegments) { i =>
    val x0_raw = (i.toLong << B) + (1L << (B - 1))                  // on recalcule x0 car juste plus facile et puis calculé par le PC 
    val rad = x0_raw.toDouble / (1L << phaseWidth).toDouble         // Conversion en radians normalisés (0 à 1)
    Math.round(Math.cos(rad) * romMaxAmp).toLong.S(romAmpWidth.W)   // cos(rad) * ampli 
  })

  val sinTable = VecInit(Seq.tabulate(numSegments) { i =>
    val x0_raw = (i.toLong << B) + (1L << (B - 1))
    val rad = x0_raw.toDouble / (1L << phaseWidth).toDouble 
    Math.round(Math.sin(rad) * romMaxAmp).toLong.S(romAmpWidth.W)   // sin(rad) * ampli 
  })


  // On récupère les valeurs de x0, cos(x0) et sin(x0) pour le segment courant 
  val x0_reg     = RegNext(evalPointTable(segmentAddr))           
  val cos_x0_reg = RegNext(cosTable(segmentAddr))
  val sin_x0_reg = RegNext(sinTable(segmentAddr))

  val phase_rad_delayed = RegNext(pac_input_rad)

  val sinSignReg2 = RegNext(sinSignReg1)
  val cosSignReg2 = RegNext(cosSignReg1)
  val isCosEqReg2 = RegNext(isCosEqReg1)

  // -- Soustracteur (dx) --
  val dx_full = phase_rad_delayed.zext - x0_reg.zext      // dx = x - x0, on utillise .zext pour ajouter un bit de poids fort et passer en signé
  val dx = RegNext(dx_full(17, 0).asSInt)                 // On peut remmetre sur 18 bits car dx est toujours compris entre -2^17 et 2^17 (car x0 est centré dans le segment)

  val cos_x0_s3 = RegNext(cos_x0_reg)
  val sin_x0_s3 = RegNext(sin_x0_reg)

  val sinSignReg3 = RegNext(sinSignReg2)
  val cosSignReg3 = RegNext(cosSignReg2)
  val isCosEqReg3 = RegNext(isCosEqReg2)


  // -- Multiplicateurs Stage 1 & Quadratique brut --
  val m_cos_s4 = RegNext((dx * cos_x0_s3) >> 1)     // dx * cos(x0) : 35 bits (18+18-1=35)
  val m_sin_s4 = RegNext((dx * sin_x0_s3) >> 1)     // dx * sin(x0) : 35 bits


  // dx*dx génère 36 bits. On décale de 13 (on jette les 13 LSB) 
  // Il reste exactement 23 bits utiles sans aucun overflow   (36-13=23).
  val dx_sq_s4 = RegNext((dx * dx) >> 13)   // dx^2  : 23 bits

  val cos_x0_s4 = RegNext(cos_x0_s3)
  val sin_x0_s4 = RegNext(sin_x0_s3)

  val sinSignReg4 = RegNext(sinSignReg3)
  val cosSignReg4 = RegNext(cosSignReg3)
  val isCosEqReg4 = RegNext(isCosEqReg3)


  // -- Multiplicateurs Stage 2 (Terme Quadratique sur 35 bits) --
  // dx_sq (23b) * sin_x0 (18b)/2! = 41 bits. 
  // 41 bits >> 7 = 34 bits. On utilise .pad(35.W) pour atteindre 35 bits.
  val m2_sin_s5 = RegNext(((dx_sq_s4 * sin_x0_s4) >> 7).pad(35))    
  val m2_cos_s5 = RegNext(((dx_sq_s4 * cos_x0_s4) >> 7).pad(35))


  val m_cos_s5 = RegNext(m_cos_s4)
  val m_sin_s5 = RegNext(m_sin_s4)

  // 35 bits - 18 bits = 17 bits de décalage à gauche
  val cos_x0_s5 = RegNext(cos_x0_s4 << 17)  // 35 bits, on prérare la soustractions sous 35 bits
  val sin_x0_s5 = RegNext(sin_x0_s4 << 17)  // 35 bits


  val sinSignReg5 = RegNext(sinSignReg4)
  val cosSignReg5 = RegNext(cosSignReg4)
  val isCosEqReg5 = RegNext(isCosEqReg4)

  // -- Additions Finales --
  // sin(x0) + cos(x0)*dx - sin(x0)*(dx^2)/2!
  // cos(x0) - sin(x0)*dx - cos(x0)*(dx^2)/2!
  val sin_taylor_35 = RegNext(sin_x0_s5 +& m_cos_s5 -& m2_sin_s5)   // 37 bits (35+2) pour éviter l'overflow
  val cos_taylor_35 = RegNext(cos_x0_s5 -& m_sin_s5 -& m2_cos_s5)   // 37 bits (35+2) pour éviter l'overflow

  val sinSignReg6 = RegNext(sinSignReg5)
  val cosSignReg6 = RegNext(cosSignReg5)
  val isCosEqReg6 = RegNext(isCosEqReg5)


  // Troncature à 16 bits juste avant les multiplexeurs de sortie. 
  // 35 - 19 = 16 bits. 
  val shift_to_16 = 19
  val round_bit_16 = (1 << (shift_to_16 - 1)).S
  
  // Les signaux tombent directement sur ~18 bits (16 + 2 bits de garde liés aux additions +&/-&)
  val sin_16b_raw = (sin_taylor_35 + round_bit_16) >> shift_to_16     // 37 - 19 = 18 bits, on garde les 2 bits de garde pour l'overflow
  val cos_16b_raw = (cos_taylor_35 + round_bit_16) >> shift_to_16     // 37 - 19 = 18 bits, on garde les 2 bits de garde pour l'overflow




  // =====================================================================
  // SINE AND COSINE SYMMETRY LOGIC
  // =====================================================================
  val targetAmpWidth = 16               
  
  val max_amp = ((1 << (targetAmpWidth - 1)) - 1).S 
  val min_amp = 0.S

  // Saturation directe (le signal a déjà été réduit depuis l'échelle 35 bits)
  val sin_sat = Mux(sin_16b_raw > max_amp, max_amp, 
                  Mux(sin_16b_raw < min_amp, min_amp, sin_16b_raw))
  val cos_sat = Mux(cos_16b_raw > max_amp, max_amp, 
                  Mux(cos_16b_raw < min_amp, min_amp, cos_16b_raw))

  // Extraction stricte des bits
  val pac_sin_out = sin_sat(targetAmpWidth - 1, 0).asSInt   
  val pac_cos_out = cos_sat(targetAmpWidth - 1, 0).asSInt

  // Swap et Signe
  val preSignCos = Mux(isCosEqReg6, pac_sin_out, pac_cos_out)
  val preSignSin = Mux(isCosEqReg6, pac_cos_out, pac_sin_out)

  io.cosOut := RegNext(Mux(cosSignReg6, -preSignCos, preSignCos))
  io.sinOut := RegNext(Mux(sinSignReg6, -preSignSin, preSignSin))
}

object GenerateTaylor3PAC_16_V2 extends App {
  val myConfig = DdsConfigs.activeConfig

  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new Taylor3PAC_16_V2(myConfig),
    Array("--target-dir", "sortie_verilog")
  )
}