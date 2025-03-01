import mill._
import scalalib._
import $file.common
import $file.`rocket-chip`.common
import $file.`rocket-chip`.common
import $file.`rocket-chip`.cde.common
import $file.`rocket-chip`.hardfloat.build

val defaultScalaVersion = "2.13.15"

def defaultVersions = Map(
  "chisel"        -> ivy"org.chipsalliance::chisel:6.6.0",
  "chisel-plugin" -> ivy"org.chipsalliance:::chisel-plugin:6.6.0",
  "chiseltest"    -> ivy"edu.berkeley.cs::chiseltest:6.0.0",
  "sourcecode"    -> ivy"com.lihaoyi::sourcecode:0.4.2",
)

trait HasChisel extends ScalaModule {
  def chiselModule: Option[ScalaModule] = None

  def chiselPluginJar: T[Option[PathRef]] = None

  def chiselIvy: Option[Dep] = Some(defaultVersions("chisel"))

  def chiselPluginIvy: Option[Dep] = Some(defaultVersions("chisel-plugin"))

  override def scalaVersion = defaultScalaVersion

  override def scalacOptions = super.scalacOptions() ++
    Agg("-language:reflectiveCalls", "-Ymacro-annotations", "-Ytasty-reader")

  override def ivyDeps = super.ivyDeps() ++ Agg(chiselIvy.get)

  override def scalacPluginIvyDeps = super.scalacPluginIvyDeps() ++ Agg(chiselPluginIvy.get)
}

object `rocket-chip` extends millbuild.`rocket-chip`.common.RocketChipModule with HasChisel {

  def mainargsIvy = ivy"com.lihaoyi::mainargs:0.7.0"

  def json4sJacksonIvy = ivy"org.json4s::json4s-jackson:4.0.7"

  object macros extends millbuild.`rocket-chip`.common.MacrosModule with HasChisel {
    def scalaReflectIvy = ivy"org.scala-lang:scala-reflect:${scalaVersion}"
  }

  object cde extends millbuild.`rocket-chip`.cde.common.CDEModule with HasChisel {
    override def millSourcePath = super.millSourcePath / "cde"
  }

  object hardfloat extends millbuild.`rocket-chip`.hardfloat.common.HardfloatModule with HasChisel {
    override def millSourcePath = super.millSourcePath / "hardfloat"
  }

  def macrosModule = macros

  def hardfloatModule = hardfloat

  def cdeModule = cde

}

object utility extends SbtModule with HasChisel {

  override def moduleDeps = super.moduleDeps ++ Seq(`rocket-chip`)

  override def ivyDeps = super.ivyDeps() ++ Agg(defaultVersions("sourcecode"))
 }

object coupledL2 extends SbtModule with HasChisel {

  object HuanCun extends SbtModule with HasChisel {
    override def moduleDeps = super.moduleDeps ++ Seq(
      `rocket-chip`, utility
    )
  }

  override def moduleDeps = super.moduleDeps ++ Seq(
    `rocket-chip`, utility, HuanCun
  )
}

object openNCB extends SbtModule with HasChisel {
  override def moduleDeps = super.moduleDeps ++ Seq(`rocket-chip`)
}

object OpenLLC extends SbtModule with HasChisel with millbuild.common.OpenLLCModule {

  override def millSourcePath = millOuterCtx.millSourcePath

  def rocketModule: ScalaModule = `rocket-chip`

  def utilityModule: ScalaModule = utility

  def coupledL2Module: ScalaModule = coupledL2

  def openNCBModule: ScalaModule = openNCB

  object test extends SbtTests with TestModule.ScalaTest {
    override def ivyDeps = super.ivyDeps() ++ Agg(
      defaultVersions("chiseltest"),
    )
  }

  override def scalacOptions = super.scalacOptions() ++ Agg("-deprecation", "-feature")

}
