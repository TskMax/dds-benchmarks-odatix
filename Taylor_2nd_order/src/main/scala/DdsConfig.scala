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
    accumWidth = 17,
    phaseOutWidth = 14,
    ampWidth = 12,
    fcw = 2624,
    numSamples = 8192,     // 131072   131072
    fileName = "dds_output_base.txt"
  )

    val config18bits = DdsConfig(
    accumWidth = 21,
    phaseOutWidth = 18,
    ampWidth = 16,
    fcw = 20971,
    numSamples = 8192,     // 131072   131072
    fileName = "dds_output_18bits.txt"
  )

   val config14bits = DdsConfig(
    accumWidth = 17,
    phaseOutWidth = 14,
    ampWidth = 12,
    fcw = 1311,
    numSamples = 8192,     // 131072   131072
    fileName = "dds_output_14bits.txt"
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
    fcw = 164, 
    numSamples = 131072,
    fileName = "dds_output_neutre.txt"
  )


  val activeConfig = config18bits

}   