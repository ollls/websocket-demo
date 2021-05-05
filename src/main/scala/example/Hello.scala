package example

import zio.App
import zio.ZEnv
import zio.ZIO
import zio.json._
import zio.ZQueue
import zio.ZRef
import zio.duration._

import zhttp.TcpServer
import zhttp.HttpRouter
import zhttp.MyLogging.MyLogging
import zhttp.MyLogging
import zhttp.LogLevel
import zhttp.HttpRoutes
import zhttp.dsl._
import zhttp.Response
import zhttp.Method._
import zhttp.StatusCode
import zhttp.StatusCode._

import zhttp.FileUtils
import zio.stream.ZStream
import zio.Chunk
import zhttp.Websocket
import zio.blocking.effectBlocking
import zio.stream.ZTransducer

//Please see URL, for more examples/use cases.
//https://github.com/ollls/zio-tls-http/blob/dev/examples/start/src/main/scala/MyServer.scala

/*
   How to switch to secure channel

   import zhttp.TLSServer
   val myHttp = new TLSServer[MyEnv]( port = 8443,
                                      keepAlive = 4000,
                                      serverIP = "0.0.0.0",
                                      keystore = "keystore.jks", "password",
                                      tlsVersion = "TLSv1.2" )
 */

object ServerExample extends zio.App {

  // def local_op = {
  //   val process = Runtime
  //     .getRuntime().exec( "cmd.exe" ).
  //.exec(String.format("cmd.exe /c dir %s", "." ) )
  //process.

  // }

  object param1 extends QueryParam("param1")

  /*
  def readProcessOutput2(proc: Process, cmd : String ): String = {

      val rd0 = proc.getInputStream()


      rd0.

  }  */

  def readStringFromProcess(proc: Process, cmd: String) = ZIO.effect {

    val bb = new StringBuilder()
    if (cmd.length() > 1) {
      val in = new Array[Byte](cmd.length())
      proc.getInputStream().read(in)
    }

    val nb = proc.getInputStream().available()
    val in2 = new Array[Byte](nb)
    proc.getInputStream().read(in2)
    bb.append(new String(in2))
    val bbb = bb.toString()
    //println( bbb )
    bbb
  }

  def writeStringToProcess(proc: Process, line: String) = {
    ZIO.effect {
      if (line.length > 0 && line.charAt(0) == '\u0003') {
        //println("CTRL-C")
        proc.destroyForcibly()
      } else {
        proc.getOutputStream().write((line + "\r\n").getBytes())
        proc.getOutputStream().flush()
      }
    }
  }

  def writeStringToProcess2(proc: Process, line: String) = {
    ZIO.effect {
      var line1 = line
      if (line.equals("\r")) line1 = "\r\n"
      proc.getOutputStream().write(line1.getBytes())
      proc.getOutputStream().flush()
    }
  }

  def run(args: List[String]) = {

    val r = HttpRoutes.of {

      case req @ GET -> Root / "ts" =>
        if (req.isWebSocket) {
          val isWindows =
            System.getProperty("os.name").toLowerCase().startsWith("windows");
          val ctx = Websocket(false, 60 * 1000)
          val pb =
            if (isWindows == true) new ProcessBuilder("cmd")
            else new ProcessBuilder("sh")
          pb.redirectErrorStream(true)
          val proc = pb.start()

          val in_queue = ZQueue
            .unbounded[String]
            .tap { q =>
              (ctx
                .receiveTextAsStream(req)
                .map(fr => new String(fr.data.toArray))
                .tap(q.offer(_))
                .runDrain
                .catchAll(_ => ZIO(proc.destroyForcibly()) *> q.offer("")) *> q
                .offer("")).fork
            }
            .toManaged(q => q.shutdown)

          for {
            blockOnPrompt <- ZRef.make(false)
            _ <- ctx.accept(req)
            _ <-
              if (isWindows)
                writeStringToProcess(proc, "set PROMPT=$$")
              else
                writeStringToProcess(proc, "export PS1=$")
            _ <- in_queue.use(q =>
              (for {
                b <- blockOnPrompt.get
                opt <- if (b == false) q.poll else q.take.map(Some(_))
                isAlive <- ZIO.effect(proc.isAlive())
                _ <- ZIO
                  .fail(
                    new zhttp.WebSocketClosed(
                      "Client websocket closed or expired"
                    )
                  )
                  .when(isAlive == false)
                cmd <- opt match {
                  case Some(line) =>
                    writeStringToProcess(proc, line) *> ZIO.succeed(line)
                  case None => ZIO.succeed(new String(""))
                }
                _ <- ZIO.sleep(300.milliseconds)
                line_out <- readStringFromProcess(proc, cmd)
                //_        <- ZIO( println( line_out.getBytes().mkString( ";") ))
                //out      <- ZStream( line_out ).aggregate( ZTransducer.splitLines).runCollect
                //.aggregate( ZTransducer.foldLeft( "" )( (o, i) => o + i + "\r\n" ) ).runCollect
                _ <- ctx.sendOneString(req, line_out)
                _ <-
                  if (line_out.endsWith("$"))
                    blockOnPrompt.set(true)
                  else
                    blockOnPrompt.set(false)
              } yield (isAlive)).repeatUntil(_ == false)
            )

          } yield (Response.Ok())

        } else {
          ZIO(Response.Error(StatusCode.NotFound))
        }

      case GET -> Root / "term" => ZIO( Response.Error( StatusCode.SeeOther).asTextBody( "/term/term.html" ) )  

      case GET -> "term" /: file =>
        for {
          path <- FileUtils.serverFilePath_(file, "web")
        } yield (Response
          .Ok()
          .asStream(ZStream.fromFile(path).mapChunks(Chunk.single(_))))

      case GET -> Root / "health" =>
        ZIO(Response.Ok().asTextBody("Health Check Ok"))
    }

    type MyEnv = MyLogging

    val myHttp =
      new TcpServer[MyEnv](port = 8080, keepAlive = 2000, serverIP = "0.0.0.0")

    val myHttpRouter = new HttpRouter[MyEnv](r)

    val logger_L = MyLogging.make(
      maxLogSize = 1024 * 1024,
      maxLogFiles = 7,
      ("console" -> LogLevel.Trace),
      ("access" -> LogLevel.Info),
      ("my_application" -> LogLevel.Info)
    )

    myHttp.run(myHttpRouter.route).provideSomeLayer[ZEnv](logger_L).exitCode

  }
}
