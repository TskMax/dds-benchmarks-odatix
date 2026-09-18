import chisel3._

case class DdsConfig(
  accumWidth: Int,         
  phaseOutWidth: Int,      
  ampWidth: Int,           
  lsbWidth: Int,           
  fcw: Int,                
  numSamples: Int,         
  fileName: String         
)

object DdsConfig {

  def apply(accumWidth: Int, ampWidth: Int, lsbWidth: Int, fcw: Int, numSamples: Int, fileName: String): DdsConfig =
    new DdsConfig(accumWidth, accumWidth, ampWidth, lsbWidth, fcw, numSamples, fileName)
}


object DdsConfigs {
    
  val configOdatix = DdsConfig(
    accumWidth = 16,  
    ampWidth = 12,   
    lsbWidth = 8,     
    fcw = 655,       
    numSamples = 2048, 
    fileName = "dds_output_odatix.txt"
  )
  
  val configDeBase = DdsConfig(
    accumWidth = 16,
    phaseOutWidth = 12,
    ampWidth = 12,
    lsbWidth = 5,     
    fcw = 655,
    numSamples = 131072,
    fileName = "dds_output_base.txt"
  )

  val configHauteResolution = DdsConfig( 
    accumWidth = 32,
    phaseOutWidth = 14,
    ampWidth = 14,
    lsbWidth = 6,     
    fcw = 42949672,
    numSamples = 65536,
    fileName = "dds_output_hires.txt"
  )

  val configNeutre = DdsConfig(
    accumWidth = 14,
    phaseOutWidth = 14,
    ampWidth = 12,
    lsbWidth = 6,
    fcw = 164, 
    numSamples = 1024,
    fileName = "dds_output_neutre.txt"
  )

  val activeConfig = configOdatix; 
}