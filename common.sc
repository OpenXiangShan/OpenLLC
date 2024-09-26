import mill._
import scalalib._

trait OpenLLCModule extends ScalaModule {

  def rocketModule: ScalaModule

  def utilityModule: ScalaModule

  def coupledL2Module: ScalaModule

  def openNCBModule: ScalaModule

  override def moduleDeps = super.moduleDeps ++ Seq(rocketModule, utilityModule, coupledL2Module, openNCBModule)
}
