object Main {
    def main(args: Array[String]) {
        // make backend implicits for asynchrony.
        import fs2.internal.NonFatal
        import java.nio.channels.AsynchronousChannelGroup
        import scala.concurrent.ExecutionContext
        object backendImplicits {
            import fs2._
            import java.util.concurrent.Executors
            implicit val tcpACG : AsynchronousChannelGroup = namedACG.namedACG("tcp")
            //                                                                                                        hehe
            implicit val Sch : Scheduler = Scheduler.fromScheduledExecutorService(
                Executors.newScheduledThreadPool(
                    16, threadFactoryFactoryProxyBeanFactory.mkThreadFactory("scheduler", daemon = true)
                )
            )
        }
        object namedACG {
            // Lifted verbatim from fs2 tests.
            import java.nio.channels.AsynchronousChannelGroup
            import java.lang.Thread.UncaughtExceptionHandler
            import java.nio.channels.spi.AsynchronousChannelProvider
            import java.util.concurrent.ThreadFactory
            import java.util.concurrent.atomic.AtomicInteger
            def namedACG(name:String):AsynchronousChannelGroup = {
                val idx = new AtomicInteger(0)
                AsynchronousChannelProvider.provider().openAsynchronousChannelGroup(
                    16
                    , new ThreadFactory {
                        def newThread(r: Runnable): Thread = {
                            val t = new Thread(r, s"fs2-AG-$name-${idx.incrementAndGet() }")
                            t.setDaemon(true)
                            t.setUncaughtExceptionHandler(
                                new UncaughtExceptionHandler {
                                    def uncaughtException(t: Thread, e: Throwable): Unit = {
                                        println("----------- UNHANDLED EXCEPTION ---------")
                                        e.printStackTrace()
                                    }
                                }
                            )
                            t
                        }
                    }
                )
            }
        }
        object threadFactoryFactoryProxyBeanFactory {
          import java.lang.Thread.UncaughtExceptionHandler
          import java.util.concurrent.{Executors, ThreadFactory}
          import java.util.concurrent.atomic.AtomicInteger
          def mkThreadFactory(name: String, daemon: Boolean, exitJvmOnFatalError: Boolean = true): ThreadFactory = {
            new ThreadFactory {
              val idx = new AtomicInteger(0)
              val defaultFactory = Executors.defaultThreadFactory()
              def newThread(r: Runnable): Thread = {
                val t = defaultFactory.newThread(r)
                t.setName(s"$name-${idx.incrementAndGet()}")
                t.setDaemon(daemon)
                t.setUncaughtExceptionHandler(new UncaughtExceptionHandler {
                    def uncaughtException(t: Thread, e: Throwable): Unit = {
                        ExecutionContext.defaultReporter(e)
                        if (exitJvmOnFatalError) {
                            e match {
                                case NonFatal(_) => ()
                                case fatal => System.exit(-1)
                            }
                        }
                    }
                }
                                             )
                t
              }
            }
          }
        }
        import backendImplicits._
        import cats.effect._
        import cats.effect.{IO, Sync}
        import fs2.{io, text, Stream}
        import fs2._
        import fs2.io.tcp._
        import java.nio.file.{Path, Paths}
        import scala.concurrent.ExecutionContext
        import scala.concurrent.duration._
        import scala.concurrent.ExecutionContext.Implicits.global
        import java.net.InetSocketAddress
        import signal.PSD._
        import scala.collection.immutable.Seq
        // For Second-Order-Section IIR Butterworth low pass filter
        import signal.Butter._
        import signal.SOSFilt._
        // For db attenuation
        import scala.math.pow

        // Choose source file for streaming.
        val fileToStream = Paths.get("/home/jovyan/work/Master thesis/data/2_no_header.csv")
        // Choose file path and name to save the preprocessed data
        val outputFilePathAndName = "/home/jovyan/work/Master thesis/data/#2_87_48dbfiltered_PSDs_fs2.csv"
        // Sampling rate in source file
        val Fs = 10000
        /// Port to send the processed data.
        val TCPPort = 54321

        // Low pass filter parameters
        val PSDStreamSamplingRate = 40
        val PSDStreamCutoffFrequency = 6.0
        val normalizedCutoff = PSDStreamCutoffFrequency / (PSDStreamSamplingRate / 2)
        val butterFilterCoeffs = butterSOSEven(4, normalizedCutoff)

        // Pre-computed noise tresholds used in lowPassFilterBinsWithTresholds
        val noiseTresholds87Path = "/home/jovyan/work/Master thesis/data/#2_87_noise_segments/max_PSD.csv"
        // Start and end index of streamed PSD
        // based on the selected freqiency range 296 - 3000 Hz .
        val startFreq = 296/8
        val endFreq = 3000/8

        // Specify the degree of attenuation of PSD bins that fall below
        // the noise treshold [db]
        //val noiseAttenuationdb = -20D
        //val noiseAttenuationdb = 0D // not any filtering
        val noiseAttenuationdb = -48D

        // Slice the noise tresholds to the wished frequency range.
        val noiseTresholds87Sliced = scala.io.Source.fromFile(noiseTresholds87Path)
          .mkString.split("\n")
          .map(_.toDouble)
          .to[scala.collection.immutable.Seq]
          .slice(startFreq, endFreq+1)

        def lowPassFilterBinsWithTresholds (buffer: Seq[Seq[Double]], tresholds: Seq[Double], attenuation: Double): Seq[Seq[Double]] = {
            // low pass filter each 15 sample long
            // constructed spike (in action potential)
            // detection based on tresholds.
            // Then, the low pass filtered bin spike
            // detections are used to attuneate the
            // actual unfiltered signal.
            // only the middle 5 samples are returned.
            // This is an attempt to have piece wise
            // smooth filtering by including 5
            // non-filtered samples
            // in both past and future and
            // hence having smooth transitions
            // between the filtered output of
            // this function.
            buffer.transpose.zipWithIndex.map{ case (binSequence: Seq[Double], index: Int) =>
                val currentTreshold = tresholds.apply(index)
                val attenuationGains = binSequence.map{ v: Double =>
                    if (v > currentTreshold) {
                        // do not attenuate
                        1D
                    }
                    else {
                        // attenuate
                        0D
                    }
                }
                val lowPassFilteredAttenuationGains = sosfilt(butterFilterCoeffs, attenuationGains)
                ///*
                binSequence.zip(lowPassFilteredAttenuationGains).map{case (p: Double, aGain: Double) =>
                    p*pow(10, (1D-aGain)*attenuation/10) // Sxx_out = Sxx_in * 10 ^ ( - (1-attenuationGain [0 t0 1])*(attenuation [db]/10) )
                }.slice(5-1,10-1)
                //
                //lowPassFilteredAttenuationGains.slice(5-1,10-1)

            }.transpose
        }

        def stridedSeqSeqSlide[F[_],I](windowWidth: Int, overlap: Int): Pipe[F,Seq[Double],Seq[Seq[Double]]] = {
            // This buffering acts similar as stridedSlide
            require(windowWidth > 0, "windowWidth must be > 0")
            require(windowWidth > overlap, "windowWidth must be wider than overlap")
            val stepsize = windowWidth - overlap

            def go(s: Stream[F, Seq[Double]], earlier: Seq[Seq[Double]]): Pull[F, Seq[Seq[Double]], Unit] = {
                s.pull.unconsN(stepsize.toLong, false).flatMap {
                    if (earlier.length == overlap) {
                        case Some((seg, tl)) => {
                            val forced = earlier ++ seg.force.toVector //.map(_.asInstanceOf[Double]) )
                            Pull.output1(forced) >> go(tl, forced.drop(stepsize))
                        }
                        case None => {
                            Pull.done
                        }
                    }
                    else {
                        case Some((seg, tl)) => {
                            val forced = earlier ++ seg.force.toVector //.map(_.asInstanceOf[Double]) )
                            go(tl, forced)
                        }
                        case None => {
                            Pull.done
                        }
                    }
                }
            }
            in => go(in, Seq()).stream
        }

        // (implicit ec: ExecutionContext, s: Scheduler)
//        def lowPassFilterFromSeqSeq[F[_],I]: Pipe[F,Seq[Seq[Double]],Seq[Seq[Double]]] = {
//            // low pass filter each 15 sample long
//            // constructed spike (in action potential)
//            // detection based on tresholds.
//            // Then, the low pass filtered bin spike
//            // detections are used to attuneate the
//            // actual unfiltered signal.
//            // only the middle 5 samples are returned.
//            // This is an attempt to have piece wise
//            // smooth filtering by including 5
//            // non-filtered samples
//            // in both past and future and
//            // hence having smooth transitions
//            // between the filtered output of
//            // this function.
//            def go(s: Stream[F,Seq[Seq[Double]]]): Pull[F,Seq[Seq[Double]],Unit] = {
//                println("go start")
//                s.pull.uncons1.map {
//                    case Some((segment, tl)) => {
//                        val filteredSeqSeq = segment.transpose.zipWithIndex.map{ case (binSequence: Seq[Double], index: Int) =>
//                            val currentTreshold = noiseTresholds87Sliced.apply(index)
//                            val attenuationGains = binSequence.map{ v: Double =>
//                                if (v > currentTreshold) {
//                                    // mark for attenuation
//                                    1D
//                                }
//                                else {
//                                    // mark for no attenuation
//                                    0D
//                                }
//                            }
//                            val lowPassFilteredAttenuationGains = sosfilt(butterFilterCoeffs, attenuationGains)
//                            ///*
//                            binSequence.zip(lowPassFilteredAttenuationGains).map{case (p: Double, aGain: Double) =>
//                                p*pow(10, (1D-aGain)*noiseAttenuationdb/10) // Sxx_out = Sxx_in * 10 ^ ( - (1-attenuationGain [0 t0 1])*(attenuation [db]/10) )
//                            }.slice(5-1,10-1)
//                            //*/
//                            //lowPassFilteredAttenuationGains.slice(5-1,10-1)
//                            }.transpose
//                        println("go end")
//                        Pull.output(Segment.seq(filteredSeqSeq)) >> go(tl) // bitshift. Call itself on tail
//                    }
//                    case None => {
//                        Pull.done
//                    }
//                }
//            }
//            in => go(in).stream
//        }

        def flattenSeqSeqSlide[F[_],I]: Pipe[F,Seq[Seq[Double]],Seq[Double]] = {
            def go(s: Stream[F,Seq[Seq[Double]]]): Pull[F,Seq[Double],Unit] = {
                // take 1 array, transform it to stream
                s.pull.uncons1.flatMap {
                    case Some((segment, tl)) => {
                        // resultat av constructor Option
                        Pull.output(Segment.seq(segment)) >> go(tl) // bitshift. Call itself on tail
                    }
                    case None => {
                        Pull.done
                    }
                }
            }
            in => go(in).stream
        }

        def lowPassFilter[F[_],I]: Pipe[F,Seq[Double],Seq[Double]] = {
            _
              .through(stridedSeqSeqSlide(15, 10))

              //.through(lowPassFilterFromSeqSeq)

              .through(_.map{ stridedSeqSeq: Seq[Seq[Double]] =>
                  lowPassFilterBinsWithTresholds(stridedSeqSeq, noiseTresholds87Sliced, noiseAttenuationdb)
                  })

              .through(flattenSeqSeqSlide)
            // SPØRSMÅL: Hjelper det å ha denne med i funksjoner? (implicit ec: ExecutionContext, s: Scheduler)
        }

        def tickSource[F[_]](period: FiniteDuration)(implicit s: Effect[F], t: Scheduler, ec: ExecutionContext): Stream[F,Unit] = {
            // Used on throttlerPipe to slow down the processing.
            t.fixedRate(period)
        }
        def chunkify[F[_],I]: Pipe[F,Seq[I],I] = { // Seq is list
            def go(s: Stream[F,Seq[I]]): Pull[F,I,Unit] = {
                // take 1 array, transform it to stream
                s.pull.uncons1.flatMap {
                    case Some((segment, tl)) => {
                        // resultat av constructor Option
                        Pull.output(Segment.seq(segment)) >> go(tl) // bitshift. Call itself on tail
                    }
                    case None => {
                        Pull.done
                    }
                }
            }
            in => go(in).stream
        }
        def vectorize[F[_],I](length: Int): Pipe[F,I,Vector[I]] = {
            // Partitions a stream vectors of length n .
            def go(s: Stream[F,I]): Pull[F,Vector[I],Unit] = {
                s.pull.unconsN(length.toLong, false).flatMap {
                    case Some((segment, tl)) => {
                        Pull.output1(segment.force.toVector) >> go(tl)
                    }
                    case None => {
                        Pull.done
                    }
                }
            }
            in => go(in).stream
        }
        def throttlerPipe[F[_]: Effect,I](Fs: Int, resolution: FiniteDuration)(implicit ec: ExecutionContext): Pipe[F,I,I] = {
            // Throttles the stream of Effects to the given time resolution.
            // Determined optimal time resolution to be
            // resolution: 0.125 seconds
            // when sampling rate is
            // Fs: 10000 Hz
            val ticksPerSecond = (1.second/resolution) // with a resolution of 0.125 seconds, ticksPerSecond will be 8
            val elementsPerTick = (Fs/ticksPerSecond).toInt // with a tickPerSecond of 8, this will be 1250 elementsPerTick
            _.through(vectorize(elementsPerTick)) // make vectors of segments of 1250 objects
                .zip(tickSource(resolution)) // here is the throttling. It is set to the given time resolution of 0.125 seconds.
                                             // This results in each segment (1250 object vector) being passed through
                                             // the pipe each 0.125 second.
                                             // This is the optimal near live / real-time pass-through speed of the vectors
                                             // given that the they (the segments segments) overlap 80 % in time. 
                                             // The 80 % overlap in time is later achieved using stridedSlide with 
                                             // windowWidth: 1250 and
                                             // overlap: 1000
                .through(_.map(_._1)) // remove the index from zip by only letting the actual data through
                .through(chunkify) // lastly, the throttled segments/vectors are flatMapped to a stream of integers
        }

        /**
        * Groups inputs in fixed size chunks by passing a "sliding window"
        * of size `windowWidth` and with an overlap `overlap` over them.
        *
        * @example {{{
        * scala> Stream(1, 2, 3, 4, 5, 6, 7).stridedSliding(3, 1).toList
        * res0: List[Vector[Int]] = List(Vector(1, 2, 3), Vector(3, 4, 5), Vector(5, 6, 7))
        * }}}
        * @throws scala.IllegalArgumentException if `n` <= 0
        */
        def stridedSlide[F[_],I](windowWidth: Int, overlap: Int): Pipe[F,I,Vector[I]] = {
            require(windowWidth > 0,       "windowWidth must be > 0")
            require(windowWidth > overlap, "windowWidth must be wider than overlap")
            val stepsize = windowWidth - overlap
            def go(s: Stream[F,I], earlier: Vector[I]): Pull[F,Vector[I],Unit] = {
                s.pull.unconsN(stepsize.toLong, false).flatMap {
                    if (earlier.length == overlap){
                        case Some((seg, tl)) => {
                            val forced = earlier ++ seg.force.toVector
                            Pull.output1(forced) >> go(tl, forced.drop(stepsize))
                        }
                        case None => {
                            Pull.done
                        }
                    }
                    else {
                        case Some((seg, tl)) => {
                            val forced = earlier ++ seg.force.toVector
                            go(tl, forced)
                        }
                        case None => {
                            Pull.done
                        }
                    }
                }
            }
            in => go(in, Vector()).stream
        }

        //(implicit ec: ExecutionContext, s: Scheduler)
        def readCSVToSlidingWindows[F[_]: Effect](fileToStream: Path, Fs: Int): Stream[F,Vector[Int]] = {
            // Reads the text lines of the filToStream csv file,
            // take the selected electrode voltage of the linem
            // then throttle, stream to live speed
            // and streams the lines to the pipe as a Vector[Int]
            // in 80 % overlapping windows of 1250 voltages.
            val selected_electrode_id = 50
            println(s"set to stream electrode ID=$selected_electrode_id")
            val reader = io.file.readAll[F](fileToStream, 4096)
                .through(text.utf8Decode)
                .through(text.lines)
                //.through(_.map{ csvLine => csvLine.split(",").map(_.toInt).toList.tail})
                .through(_.map{ csvLine => csvLine.split(",").tail.apply(selected_electrode_id).toInt}) // split each text (String) line 
                                                                                     // to an array of 61 Chars representing the
                                                                                     // TimeStamp and the electrode voltages, then      
                                                                                     // remove the TimeStamp using tail.
                                                                                     // Then select only electrode with ID=10 .
                                                                                     // The Char representing the integer value 
                                                                                     // of electrode with the selected ID
                                                                                     // is then converted to Int
                
                //.through(throttlerPipe(Fs, 0.125.second)) // make the stream near live speed by slowing it down
                                                          // to segments of 1250 integers being delivered each 0.125 second.
                                                          // This is near live streaming throughput with a sampling rate of 
                                                          // Fs: 10000 Hz
            
                .through(stridedSlide(1250, 1000)) // transform the stream to sliding windows of segments of 1250 objects
                                                   // with 80 % overlap. The parameters fit optimally together with 
                                                   // throttlerPipe(10000 Hz, 0.125 seconds) as used above
            
                .handleErrorWith{
                    case e: java.lang.NumberFormatException => { println("Record done"); Stream.empty}
                    case e: Exception => { println(s"Very bad error $e"); Stream.empty }
                    case _ => { println("I don't know what happened..."); Stream.empty }
                }
            reader
        }
        //(implicit ec: ExecutionContext, s: Scheduler)
        def computePowerSpectralDensity[F[_],I]: Pipe[F,Vector[Int],Seq[Double]] = {
            _.through(_.map { window =>
                val windowArray = window.map(_.toDouble).to[Seq]
                val N = windowArray.length
                // output of psd is a Seq[Double]
                psd(windowArray, Fs.toDouble)
                  .map{case (f, p) => (f, p*(N/Fs.toDouble))}.slice(startFreq, endFreq+1)
                  .map(_._2) // Only take Pxx, _._1 is the frequency range 296 - 3000 Hz .
            })
        }
        val dataStream = readCSVToSlidingWindows[IO](fileToStream, Fs)
          .through(computePowerSpectralDensity)
          .through(lowPassFilter)

        def sendStreamInTCP[F[_]](socket: Socket[F], dataStream: Stream[F, Vector[Int]]): Stream[F, Unit] = {
            dataStream
                //.through(_.map(_.toString)) // convert the 80 % overlapping Vectors to String
                .through(_.map(_.mkString(","))) // convert the 80 % overlapping Vectors to String
                .intersperse("\n") // add newline between the windows now stored as Strings
                .through(text.utf8Encode)
                .through(socket.writes(None))
                .handleErrorWith{
                    case e: java.io.IOException => { 
                        println(s"Connection ended because of $e")
                        println("now exiting system")
                        System.exit(0)                       
                        Stream.empty 
                    }
                    case _ => { println("I don't know what happened...")
                        //System.exit(0)
                        Stream.empty                        
                    }
                }
        }
        def writeStreamToFile[F[_]](filePathAndName: String, dataStream: Stream[F, Seq[Double]])(implicit F: Sync[F]): F[Unit] = {
            println("writing preprocessed data to")
            println(outputFilePathAndName)
            dataStream
              .through(_.map(_.mkString(";").replaceAllLiterally(".",","))) // convert the 80 % overlapping Vectors to String
              .intersperse("\n") // add newline between the windows now stored as Strings
              .through(text.utf8Encode)
              .through(io.file.writeAll(Paths.get(filePathAndName)))
              .compile.drain
        }
        //println(s"now starting the server on port $TCPPort")
        /*
        server[IO](new InetSocketAddress("localhost", TCPPort))
            .flatMap(stream => stream.flatMap(socket => sendStreamInTCP[IO](socket, dataStream)))
            .compile
            .drain
            .unsafeRunSync()
        */
        // version that terminates the program after having sent the file to one client.
        /*
        Stream.eval(fs2.async.signalOf[IO, Boolean](false)).flatMap { interruptSignal =>
            server[IO](new InetSocketAddress("localhost", TCPPort))
                .interruptWhen(interruptSignal)
                .flatMap(stream => stream.flatMap(socket => sendStreamInTCP[IO](socket, dataStream)) ++ Stream.eval_(interruptSignal.set(true)))
        }.compile.drain.unsafeRunSync()
        */
        val u: Unit = writeStreamToFile[IO](outputFilePathAndName, dataStream).unsafeRunSync()
    }
}
