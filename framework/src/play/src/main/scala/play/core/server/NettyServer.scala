package play.core.server

import org.jboss.netty.channel._
import org.jboss.netty.bootstrap._
import org.jboss.netty.channel.Channels._
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.channel.group._
import org.jboss.netty.handler.ssl._

import java.security._
import java.net.{ InetSocketAddress }
import javax.net.ssl._
import java.util.concurrent._

import play.core._
import play.api._
import play.core.server.netty._

import java.security.cert.X509Certificate
import java.io.{File, FileInputStream}
import utils.IO

/**
 * provides a stopable Server
 */
trait ServerWithStop {
  def stop(): Unit
  def mainAddress: InetSocketAddress
}

/**
 * creates a Server implementation based Netty
 */
class NettyServer(appProvider: ApplicationProvider, port: Int, sslPort: Option[Int] = None, address: String = "0.0.0.0", val mode: Mode.Mode = Mode.Prod) extends Server with ServerWithStop {

  def applicationProvider = appProvider

  private def newBootstrap = new ServerBootstrap(
    new org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory(
      Executors.newCachedThreadPool(),
      Executors.newCachedThreadPool()))

  class PlayPipelineFactory(secure: Boolean = false) extends ChannelPipelineFactory {

    def getPipeline = {
      val newPipeline = pipeline()
      if (secure) {
        sslContext.map { ctxt =>
            val sslEngine = ctxt.createSSLEngine
            sslEngine.setUseClientMode(false)
            newPipeline.addLast("ssl", new SslHandler(sslEngine))
        }
      }
      newPipeline.addLast("decoder", new HttpRequestDecoder(4096, 8192, 8192))
      newPipeline.addLast("encoder", new HttpResponseEncoder())
      newPipeline.addLast("compressor", new HttpContentCompressor())
      newPipeline.addLast("decompressor", new HttpContentDecompressor())
      newPipeline.addLast("handler", defaultUpStreamHandler)
      newPipeline
    }

    lazy val sslContext: Option[SSLContext] = {  //the sslContext should be reused on each connection
      for (
        keyStore <- loadKeyStore();
        keyManagers <- loadKeyManagers(keyStore);
        trustManagers <- loadTrustManagers(keyStore)
      ) yield {
        // Configure the SSL context
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagers, trustManagers, null)
        sslContext
      }
    }
  }

  private def loadKeyStore() = {
    Option(System.getProperty("https.keyStore")) match {
      case Some(path) => {
        // Load the configured key store
        val keyStore = KeyStore.getInstance(System.getProperty("https.keyStoreType", "JKS"))
        val password = System.getProperty("https.keyStorePassword", "").toCharArray
        val file = new File(path)
        if (file.isFile) {
          IO.use(new FileInputStream(file)) {
            in => keyStore.load(in, password)
          }
          Logger("play").debug("Using HTTPS keystore at " + file.getAbsolutePath)
          Some(keyStore)
        } else {
          Logger("play").error("Unable to find HTTPS keystore at \"" + file.getAbsolutePath + "\"")
          None
        }
      }
      case None => {
        Logger("play").warn("Using generated key with self signed certificate for HTTPS. This should not be used in production.")
        FakeKeyStore.keyStore(applicationProvider.path)
      }
    }
  }

  private def loadKeyManagers(keyStore: KeyStore) = {
    try {
      val algorithm = System.getProperty("https.keyStoreAlgorithm", KeyManagerFactory.getDefaultAlgorithm)
      val kmf = KeyManagerFactory.getInstance(algorithm)
      val password = System.getProperty("https.keyStoreKeyPassword", System.getProperty("https.keyStorePassword", "")).toCharArray
      kmf.init(keyStore, password)
      Some(kmf.getKeyManagers)
    } catch {
      case e: Exception => {
        Logger("play").error("Error loading key managers", e)
        None
      }
    }
  }

  private def loadTrustManagers(keyStore: KeyStore): Option[Array[TrustManager]] = {
    val algorithm = System.getProperty("https.trustStoreAlgorithm", TrustManagerFactory.getDefaultAlgorithm)

    System.getProperty("https.trustStore", "keystore") match {
      case "noCA" => {
        Logger("play").warn("HTTPS configured with no client " +
          "side CA verification. Requires http://webid.info/ for client certifiate verification.")
        Some(Array[TrustManager](noCATrustManager))
      }
      case "keystore" => {
        Logger("play").debug("Using configured key store as the trust store")
        try {
          val tmf = TrustManagerFactory.getInstance(algorithm)
          tmf.init(keyStore)
          Some(tmf.getTrustManagers)
        } catch {
          case e: Exception => {
            Logger("play").error("Error loading trust managers", e)
            None
          }
        }
      }
      case "default" => Some(null) // Use the Java default trust store
      case className => {
        try {
          val clazz = Class.forName(className)
          if (clazz.getInterfaces.toTraversable.exists(_ == classOf[X509TrustManager])) {
            try {
              val res = Some(Array(clazz.newInstance().asInstanceOf[TrustManager]))
              Logger("play").info("Loaded TLS Trust Manager implementation "+clazz)
              res
            } catch {
              case e: InstantiationException => {
                Logger("play").error("could not instantiate "+className)
                None
              }
            }
          } else {
            Logger("play").error("TrustManager class " + className+ " does not implement javax.net.ssl.TrustManager")
            None
          }
        } catch {
          case e: Exception => {
            Logger("play").error("Unknown trust store type, must be one of [keystore, noCA, default, class.Name]: "
              + className + " was not found." + e.getMessage)
            None
          }
        }
      }
    }
  }

  // Keep a reference on all opened channels (useful to close everything properly, especially in DEV mode)
  val allChannels = new DefaultChannelGroup

  // Our upStream handler is stateless. Let's use this instance for every new connection
  val defaultUpStreamHandler = new PlayDefaultUpstreamHandler(this, allChannels)

  // The HTTP server channel
  val HTTP = {
    val bootstrap = newBootstrap
    bootstrap.setPipelineFactory(new PlayPipelineFactory)
    val channel = bootstrap.bind(new InetSocketAddress(address, port))
    allChannels.add(channel)
    (bootstrap, channel)
  }

  // Maybe the HTTPS server channel
  val HTTPS = sslPort.map { port =>
    val bootstrap = newBootstrap
    bootstrap.setPipelineFactory(new PlayPipelineFactory(secure = true))
    val channel = bootstrap.bind(new InetSocketAddress(address, port))
    allChannels.add(channel)
    (bootstrap, channel)
  }

  mode match {
    case Mode.Test =>
    case _ => {
      Logger("play").info("Listening for HTTP on %s".format(HTTP._2.getLocalAddress))
      HTTPS.foreach { https =>
        Logger("play").info("Listening for HTTPS on port %s".format(https._2.getLocalAddress))
      }
    }
  }

  override def stop() {

    try {
      Play.stop()
    } catch {
      case e: Exception => Logger("play").error("Error while stopping the application", e)
    }

    try {
      super.stop()
    } catch {
      case e: Exception => Logger("play").error("Error while stopping akka", e)
    }

    mode match {
      case Mode.Test =>
      case _ => Logger("play").info("Stopping server...")
    }

    // First, close all opened sockets
    allChannels.close().awaitUninterruptibly()

    // Release the HTTP server
    HTTP._1.releaseExternalResources()

    // Release the HTTPS server if needed
    HTTPS.foreach(_._1.releaseExternalResources())

  }

  override lazy val mainAddress = HTTP._2.getLocalAddress.asInstanceOf[InetSocketAddress]

}

object noCATrustManager extends X509TrustManager {
  val nullArray = Array[X509Certificate]()
  def checkClientTrusted(x509Certificates: Array[X509Certificate], s: String) {}
  def checkServerTrusted(x509Certificates: Array[X509Certificate], s: String) {}
  def getAcceptedIssuers() = nullArray
}

/**
 * bootstraps Play application with a NettyServer backened
 */
object NettyServer {

  import java.io._

  /**
   * creates a NettyServer based on the application represented by applicationPath
   * @param applicationPath path to application
   */
  def createServer(applicationPath: File): Option[NettyServer] = {
    // Manage RUNNING_PID file
    java.lang.management.ManagementFactory.getRuntimeMXBean.getName.split('@').headOption.map { pid =>
      val pidFile = Option(System.getProperty("pidfile.path")).map(new File(_)).getOrElse(new File(applicationPath.getAbsolutePath, "RUNNING_PID"))

      // The Logger is not initialized yet, we print the Process ID on STDOUT
      println("Play server process ID is " + pid)

      if (pidFile.getAbsolutePath != "/dev/null") {
        if (pidFile.exists) {
          println("This application is already running (Or delete " + pidFile.getAbsolutePath + " file).")
          System.exit(-1)
        }

        new FileOutputStream(pidFile).write(pid.getBytes)
        Runtime.getRuntime.addShutdownHook(new Thread {
          override def run {
            pidFile.delete()
          }
        })
      }
    }

    try {
      val server = new NettyServer(
        new StaticApplication(applicationPath),
        Option(System.getProperty("http.port")).map(Integer.parseInt(_)).getOrElse(9000),
        Option(System.getProperty("https.port")).map(Integer.parseInt(_)),
        Option(System.getProperty("http.address")).getOrElse("0.0.0.0")
      )

      Runtime.getRuntime.addShutdownHook(new Thread {
        override def run {
          server.stop()
        }
      })

      Some(server)
    } catch {
      case e: Exception => {
        println("Oops, cannot start the server.")
        e.printStackTrace()
        None
      }
    }

  }

  /**
   * attempts to create a NettyServer based on either
   * passed in argument or `user.dir` System property or current directory
   * @param args
   */
  def main(args: Array[String]) {
    args.headOption.orElse(
      Option(System.getProperty("user.dir"))).map(new File(_)).filter(p => p.exists && p.isDirectory).map { applicationPath =>
        createServer(applicationPath).getOrElse(System.exit(-1))
      }.getOrElse {
        println("Not a valid Play application")
      }
  }

  /**
   * provides a NettyServer for the dev environment
   */
  def mainDev(sbtLink: SBTLink, port: Int): NettyServer = {
    play.utils.Threads.withContextClassLoader(this.getClass.getClassLoader) {
      try {
        val appProvider = new ReloadableApplication(sbtLink)
        new NettyServer(appProvider, port,
          Option(System.getProperty("https.port")).map(Integer.parseInt(_)),
          mode = Mode.Dev)
      } catch {
        case e: ExceptionInInitializerError => throw e.getCause
      }

    }
  }

}
