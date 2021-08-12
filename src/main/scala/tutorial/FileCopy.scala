package tutorial

import cats.effect.{ExitCode, IO, IOApp, Sync}
import cats.effect.kernel.Resource
import cats.syntax.all._
import java.io._

object Main extends IOApp {
  // transfer will do the real work
  def transmit[F[_] : Sync](origin: InputStream, destination: OutputStream, buffer: Array[Byte], acc: Long): F[Long] =
    for {
      amount <- Sync[F].blocking(origin.read(buffer, 0, buffer.length))
      count <- if (amount > -1) Sync[F].blocking(destination.write(buffer, 0, amount)) >> transmit(origin, destination, buffer, acc + amount)
      else Sync[F].pure(acc) // End of read stream reached (by java.io.InputStream contract), nothing to write
    } yield count // Returns the actual amount of bytes transmitted // Returns the actual amount of bytes transmitted

  def transfer[F[_] : Sync](origin: InputStream, destination: OutputStream, bufferSize: Int): F[Long] =
    transmit[F](origin, destination, new Array[Byte](bufferSize), 0)

  def createInputStream[F[_] : Sync](f: File): Resource[F, InputStream] =
    Resource.make(Sync[F].blocking(new FileInputStream(f))) { is =>
      Sync[F].blocking(println("Closing input file"))
        .>>(Sync[F].blocking(is.close()))
        .>>(Sync[F].blocking(println("Closed input file")))
    }

  def createOutputStream[F[_] : Sync](f: File): Resource[F, OutputStream] =
    Resource.make(Sync[F].blocking(new FileOutputStream(f))) { os =>
      Sync[F].blocking(println("Closing os file"))
        .>>(Sync[F].blocking(os.close()))
        .>>(Sync[F].blocking(println("Closed os file")))
    }

  def copy[F[_] : Sync](origin: File, destination: File): F[Long] = {
    if (origin.isDirectory) {
      Sync[F].ifM(Sync[F].blocking(!destination.exists()))(
        Sync[F].ensure(Sync[F].blocking(destination.mkdir()))(new RuntimeException("Directory can't be created"))(identity).map(_ => ()),
        Sync[F].unit
      )
        .>>(Sync[F].blocking(origin.listFiles().toList))
        .flatMap { files =>
          files.traverse { file =>
            copy(file, new File(s"${destination.getPath}/${file.getName}"))
          }
        }
        .map(_.sum)
    } else {
      (createInputStream(origin), createOutputStream(destination))
        .tupled
        .use { case (in, out) => transfer[F](in, out, 1024 * 10) }
    }
  }

  def checkFilesDistinct[F[_] : Sync](in: File, out: File): F[Unit] = {
    Sync[F].whenA(in.equals(out))(Sync[F].raiseError(new IllegalArgumentException("in == out")))
  }

  def checkFilesAligned[F[_] : Sync](in: File, out: File): F[Unit] = {
    Sync[F].whenA(in.isDirectory && out.isFile)(Sync[F].raiseError(new IllegalArgumentException("can't copy folder into file")))
  }

  override def run(args: List[String]): IO[ExitCode] = {
    IO.pure((new File("./src"), new File("./srcCopy")))
      .flatTap(checkFilesDistinct[IO].tupled)
      .flatTap(_ => IO.blocking(Thread.sleep(5000)))
      .flatMap(copy[IO].tupled)
      .map(_ => ExitCode.Success)
  }
}