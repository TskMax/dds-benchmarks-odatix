import chisel3._

// 1. La classe qui definit quels sont les parametres modifiables
case class DdsConfig(
  accumWidth: Int,         // Correspond à accumWidth dans top_level_DDS
  phaseOutWidth: Int,      // Correspond à phaseOutWidth dans top_level_DDS
  ampWidth: Int,           // Correspond à ampWidth dans top_level_DDS
  fcw: Int,                // Le mot de commande de frequence (increment)
  numSamples: Int,         // Nombre d'echantillons pour la FFT (ex: 65536)
  fileName: String         // Le nom du fichier de sortie
)

// 2. Le "catalogue" de tes configurations de test
object DdsConfigs {

    
  
  // Ta configuration actuelle (16 bits, 65536 echantillons)
  val configDeBase = DdsConfig(
    accumWidth = 21,
    phaseOutWidth = 18,
    ampWidth = 16,
    fcw = 20971,
    numSamples = 8192,     // 131072   131072
    fileName = "dds_output_base.txt"
  )


    val config16 = DdsConfig(
    accumWidth = 21,
    phaseOutWidth = 18,
    ampWidth = 16,
    fcw = 20971,
    numSamples = 8192,     // 131072   131072
    fileName = "dds_output_16.txt"
  )

  // Une autre configuration pour tester une tres haute precision plus tard
  val configHauteResolution = DdsConfig( //permet d'avoir le meme f_out que la config de base ! 
    accumWidth = 32,
    phaseOutWidth = 12,
    ampWidth = 12,
    fcw = 42949672,
    numSamples = 65536,
    fileName = "dds_output_hires.txt"
  )

  val configNeutre = DdsConfig(
    accumWidth = 14,
    phaseOutWidth = 14,
    ampWidth = 12,
    fcw = 164, // Pas de fréquence peu précis : 100 MHz / 2^14 = 6103 Hz, donc on choisit une valeur de FCW qui correspond à une fréquence d'environ 1 MHz.
    numSamples = 131072,
    fileName = "dds_output_neutre.txt"
  )


  val activeConfig = configDeBase; 

}   