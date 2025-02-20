import mill._
import scalalib._
import scalafmt._
import os.Path
import publish._
import $file.common
import $file.`rocket-chip`.common
import $file.`rocket-chip`.common
import $file.`rocket-chip`.cde.common
import $file.`rocket-chip`.hardfloat.build

val defaultScalaVersion = "2.13.15"

def defaultVersions = Map(
  "chisel"        -> ivy"org.chipsalliance::chisel:6.6.0",
  "chisel-plugin" -> ivy"org.chipsalliance:::chisel-plugin:6.6.0",
  "chiseltest"    -> ivy"edu.berkeley.cs::chiseltest:6.0.0"
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

object rocketchip extends `rocket-chip`.common.RocketChipModule with HasChisel {

  val rcPath = os.pwd / "rocket-chip"
  override def millSourcePath = rcPath

  def mainargsIvy = ivy"com.lihaoyi::mainargs:0.7.0"

  def json4sJacksonIvy = ivy"org.json4s::json4s-jackson:4.0.7"

  object macros extends `rocket-chip`.common.MacrosModule with HasChisel {
    def scalaReflectIvy = ivy"org.scala-lang:scala-reflect:${scalaVersion}"
  }

  object cde extends `rocket-chip`.cde.common.CDEModule with HasChisel {
    override def millSourcePath = rcPath / "cde" / "cde"
  }

  object hardfloat extends `rocket-chip`.hardfloat.common.HardfloatModule with HasChisel {
    override def millSourcePath = rcPath / "hardfloat" / "hardfloat"
  }

  def macrosModule = macros

  def hardfloatModule = hardfloat

  def cdeModule = cde

}

object utility extends SbtModule with HasChisel {
  override def millSourcePath = os.pwd / "utility"

  override def moduleDeps = super.moduleDeps ++ Seq(rocketchip)
 }

object huancun extends SbtModule with HasChisel {
  override def millSourcePath = os.pwd / "coupledL2" / "HuanCun"

  override def moduleDeps = super.moduleDeps ++ Seq(
    rocketchip, utility
  )
}

object coupledL2 extends SbtModule with HasChisel {
  override def millSourcePath = os.pwd / "coupledL2"
  override def moduleDeps = super.moduleDeps ++ Seq(
    rocketchip, utility, huancun
  )
}

object openNCB extends SbtModule with HasChisel {
  override def millSourcePath = os.pwd / "openNCB"
  override def moduleDeps = super.moduleDeps ++ Seq(rocketchip)
}

object OpenLLC extends SbtModule with HasChisel with millbuild.common.OpenLLCModule {

  override def millSourcePath = millOuterCtx.millSourcePath

  def rocketModule: ScalaModule = rocketchip

  def utilityModule: ScalaModule = utility

  def coupledL2Module: ScalaModule = coupledL2

  def openNCBModule: ScalaModule = openNCB

  object test extends SbtModuleTests with TestModule.ScalaTest {
    override def ivyDeps = super.ivyDeps() ++ Agg(
      defaultVersions("chiseltest"),
    )
  }

  override def scalacOptions = super.scalacOptions() ++ Agg("-deprecation", "-feature")

}
