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

object DdsConfig {
  def apply(accumWidth: Int, ampWidth: Int, fcw: Int, numSamples: Int, fileName: String): DdsConfig =
    new DdsConfig(accumWidth, accumWidth, ampWidth, fcw, numSamples, fileName)
}


object DdsConfigs {

    
  val configOdatix = DdsConfig(
    accumWidth = 14,  
    ampWidth = 12,
    fcw = 164, 
    numSamples = 2048,
    fileName = "dds_output_odatix.txt"
  )

  val configDeBase = DdsConfig(
    accumWidth = 16,
    phaseOutWidth = 12,
    ampWidth = 12,
    fcw = 655,
    numSamples = 131072,
    fileName = "dds_output_base.txt"
  )

  val configHauteResolution = DdsConfig(
    accumWidth = 32,
    phaseOutWidth = 14,
    ampWidth = 14,
    fcw = 42949672,
    numSamples = 65536,
    fileName = "dds_output_hires.txt"
  )

  val configNeutre = DdsConfig(
    accumWidth = 14,
    phaseOutWidth = 14,
    ampWidth = 12,
    fcw = 164,
    numSamples = 1024,
    fileName = "dds_output_neutre.txt"
  )

  val activeConfig = configOdatix; 

}   