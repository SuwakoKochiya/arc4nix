package main

import java.io.{ByteArrayOutputStream, File}
import java.nio.charset.Charset
import java.util

import akka.actor.{Actor, ActorSystem, Props}
import akka.event.Logging
import arcgis.LocalServerContainer
import com.esri.core.tasks.ags.geoprocessing.GPJobResource.JobStatus
import com.esri.core.tasks.ags.geoprocessing._
import com.esri.runtime.ArcGISRuntime
import org.apache.commons.codec.binary.Base64
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream

import scala.io.Source

/**
  * Created by sugar on 12/26/15.
  */

class GeoprocessingApp(val gpkPath: String) extends Actor {

  var geoprocessingUrl: String = _

  val log = Logging.getLogger(context.system, this)

  override def receive: Receive = {

    // If a string is received, it is a command
    case x: String =>
      x match {
        case "shutdown" =>
          context.system.actorSelection("/user/LocalServerInstance") ! LocalServerContainer.exit
        case "exit" =>
          System.exit(0)
        case "start" =>
          context.system.actorSelection("/user/LocalServerInstance") ! LocalServerContainer.start(gpkPath)
        case s if s.startsWith("start ") =>
          val r = s.split(' ')
          // If the input is empty. Let us forget about it.
          if(r(1).trim.isEmpty || !new File(r(1)).exists()) {
            log.error("Cannot start an empty or non-existed package");
          } else
            context.system.actorSelection("/user/LocalServerInstance") ! LocalServerContainer.start(r(1))
        // FIXME: We cannot simply assume there will always be 3 parameters input. In case some are missing, it cases problem.
        case s if s.startsWith("execute ") => // We put a " " here to ensure execute will be a complete command
          val r = s.split(' ')
          println(s"execute (${r(1)}, ${r(2)}, ${r(3)})")
          self ! GeoprocessingApp.execute(r(1), r(2), r(3))
        case s if s.startsWith("[CMD]") =>
          System.err.println(s)
        case _@s =>
          println(s"[INFO]$s")
      }

    case GeoprocessingApp.execute(input: String, block: String, script: String) =>
      if (geoprocessingUrl != null) {
        val gp = new Geoprocessor(geoprocessingUrl + "/Execute Script")
        val globalVariables = new GPString("GlobalVars")
        globalVariables.setValue(GeoprocessingApp.decode(input))
        val predefinedBlock = new GPString("PreBlock")
        predefinedBlock.setValue(GeoprocessingApp.decode(block))
        val scriptBody = new GPString("ScriptBody")
        scriptBody.setValue(GeoprocessingApp.decode(script))
        val parameters = new util.ArrayList[GPParameter]()
        parameters.add(globalVariables)
        parameters.add(predefinedBlock)
        parameters.add(scriptBody)

        gp.submitJobAndGetResultsAsync(parameters, Array("Result"), null, new GPJobResultCallbackListener {
          override def onError(throwable: Throwable): Unit = {
            System.err.println("[ERROR]:" + throwable.getMessage)
          }

          override def onCallback(gpJobResource: GPJobResource, gpParameters: Array[GPParameter]): Unit = {
            if(gpJobResource.getJobStatus == JobStatus.SUCCEEDED) {
              val result = gpParameters(0).asInstanceOf[GPString]
              log.info(result.getValue)
              log.info(gpJobResource.getMessages.map(p => p.getDescription).mkString("\n"))
              // Put result in std err stream so python client can read it.
              System.err.println(s"[JSON]${result.getValue}")
              System.err.flush()
            } else {
              log.error(gpJobResource.getMessages.map(_.getDescription).mkString("\n"))
              System.err.println("[JOBFAILED]")
              System.err.flush()
            }

          }
        })
      } else {
        log.error("Service is not ready. Try again later!")
      }

    case GeoprocessingApp.messageUrl(url: String) => {
      log.info(s"service is at $url")
      geoprocessingUrl = url
    }



  }

}


object GeoprocessingApp {

  case class messageUrl(url: String)

  case class execute(input: String, block: String, script: String)

  def decode(inputString: String): String = {
    if (!inputString.startsWith("b64:"))
      inputString
    else {
      new String(Base64.decodeBase64(inputString.substring(4).getBytes(Charset.forName("UTF-8"))))
    }
  }

  def encode(inputString: String, compressed: Boolean): String = {
    if (!compressed) {
      return new String(Base64.encodeBase64(inputString.getBytes(Charset.forName("UTF-8"))))
    }
    val r = new ByteArrayOutputStream()
    val b = new GzipCompressorOutputStream(r)
    b.write(inputString.getBytes(Charset.forName("UTF-8")))
    b.flush()
    b.finish()
    val s = r.toByteArray
    r.flush()
    val t = Base64.encodeBase64(s)
    b.close()
    r.close()
    new String(t)
  }

  def main(args: Array[String]) {

    if(args.length >= 2) {
      ArcGISRuntime.setClientID(args(1))
      val license = args(2)
      val extLicenses = args.slice(3, args.length)
      ArcGISRuntime.License.setLicense(license, extLicenses)
    }

    ArcGISRuntime.initialize()

    val system = ActorSystem("LocalGP")
    val app = system.actorOf(Props(new GeoprocessingApp(args(0))), "MainApp")
    val server = system.actorOf(Props[LocalServerContainer], "LocalServerInstance")

    // Give MainApp a signal says it is ready to start
    app ! "[CMD]READY"

    while (!system.isTerminated) {
      app ! readLine()
    }
  }

}
