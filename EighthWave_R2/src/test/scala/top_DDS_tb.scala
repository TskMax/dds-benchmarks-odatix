import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import java.io.PrintWriter
import java.io.File

class top_DDS_tb extends AnyFlatSpec with ChiselSim {
  "Le DDS complet" should "exporter les points selon la configuration centralisee" in {
    
    // On charge la configuration depuis le fichier central
    val myConfig = DdsConfigs.activeConfig
    
    // Appel à Verilator avec les variables issues de myConfig
    simulate(new top_level_DDS(myConfig)) { dut =>
      
      // Application de l'incrément (FCW) issu de la configuration à chaque cycle d'horloge
      dut.io.increment.poke(myConfig.fcw.U)
      
      // Création des noms de fichiers dynamiques
      val cosFileName = s"cos_${myConfig.fileName}"
      val sinFileName = s"sin_${myConfig.fileName}"
      
      // Création de deux flux d'écriture distincts
      val writerCos = new PrintWriter(new File(cosFileName))
      val writerSin = new PrintWriter(new File(sinFileName))
      
      // Boucle d'échantillonnage
      for (_ <- 0 until myConfig.numSamples) {
        // Lecture des deux ports de sortie de ton architecture
        val sampleCos = dut.io.cosOut.peek().litValue  
        val sampleSin = dut.io.sinOut.peek().litValue  
        
        // Écriture synchronisée dans les deux fichiers
        writerCos.println(sampleCos)
        writerSin.println(sampleSin)
        
        // Avance d'un cycle d'horloge pour obtenir l'échantillon suivant
        dut.clock.step(1)  
      }
      
      // Clôture des fichiers pour libérer la mémoire et valider l'écriture
      writerCos.close()
      writerSin.close()
      
      println(s"Échantillons exportés avec succès dans $cosFileName et $sinFileName !")
    }
  }
}