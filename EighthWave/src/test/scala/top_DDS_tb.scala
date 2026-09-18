import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import java.io.PrintWriter
import java.io.File

class top_DDS_tb extends AnyFlatSpec with ChiselSim {
  "Le DDS complet" should "exporter les points selon la configuration centralisee" in {
    
   
    val myConfig = DdsConfigs.activeConfig
    
    
    // Appel à Verilator avec les variables issues de myConfig
    simulate(new top_level_DDS(myConfig)) { dut =>
      
      // Application de l'incrément (FCW) issu de la configuration à chaque cycle d'horloge
      dut.io.increment.poke(myConfig.fcw.U)
      
      // Création du fichier texte avec le nom dynamique
      val writer = new PrintWriter(new File(myConfig.fileName))
      
      // Boucle d'échantillonnage dynamique
      for (_ <- 0 until myConfig.numSamples) {
        val sample = dut.io.signalOut.peek().litValue  //peek() pour lire la valeur actuelle de signalOut, litValue pour obtenir la valeur entière brute (scala) (en tenant compte du format signé)
        writer.println(sample)  // Ecriture de l'échantillon dans le fichier texte
        dut.clock.step(1)  // Avance d'un cycle d'horloge pour obtenir le prochain échantillon
      }
      
      writer.close()
      println(s"Échantillons exportés avec succès dans ${myConfig.fileName} !")
    }
  }
}